package io.talevia.platform.ffmpeg

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.video.ClipActionTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Critical-path runtime guard: per-clip incremental render must
 *
 *  1. fingerprint each clip → write a per-clip mezzanine on miss,
 *  2. find the same fingerprint in `Project.clipRenderCache` on a re-export and
 *     reuse the cached mezzanine without calling `renderClip` again,
 *  3. concat-demux the (some-fresh, some-cached) mezzanine list to produce
 *     **byte-identical** final mp4 bytes across the two exports.
 *
 * The handler-level [io.talevia.core.tool.builtin.video.ExportToolTest] suite
 * pins the dispatcher's branching against `FakePerClipEngine`. That test
 * proves "ExportTool calls `renderClip` 3 times then 0 times" but not
 * "FFmpeg's renderClip + concat path is deterministic enough that the
 * cache-hit run lands the same bytes as the fresh-render run." Without this
 * end-to-end check, a regression in `FfmpegVideoEngine.renderClip` — say a
 * stray timestamp / encoder setting / muxer field — would silently corrupt
 * cache hits in production while every unit test still passed.
 *
 * Mirrors the skip pattern in [FfmpegEndToEndTest] / [ExportDeterminismTest]:
 * test auto-skips when `ffmpeg` is not on PATH so CI environments without
 * the binary don't fail this suite. Runs under
 * `:platform-impls:video-ffmpeg-jvm:test` only — the `core` jvmTest suite
 * uses the in-memory `FakePerClipEngine`.
 */
@OptIn(ExperimentalUuidApi::class)
class PerClipIncrementalRenderTest {
    private lateinit var workDir: File
    private lateinit var input: File
    private lateinit var outputA: File
    private lateinit var outputB: File

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("talevia-perclip-").toFile()
        input = File(workDir, "src.mp4")
        outputA = File(workDir, "out-a.mp4")
        outputB = File(workDir, "out-b.mp4")
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    /**
     * Render → re-render with cache hits → bit-identical output.
     *
     * Render 1: empty `Project.clipRenderCache`, per-clip path emits 3 fresh
     * mezzanines. Tool reports `perClipCacheHits=0, perClipCacheMisses=3`.
     *
     * Render 2: `forceRender=true` so the **whole-export** [RenderCache]
     * doesn't short-circuit at the top of `ExportTool.execute` (the
     * RenderCache is keyed on (project + profile) and would just hand back
     * the prior bytes without ever exercising the per-clip path we want to
     * verify here). Under `forceRender`, control falls through to
     * `runPerClipRender`, which consults `clipRenderCache` per clip — and
     * since render 1 just persisted entries for the same 3 fingerprints,
     * we expect 3 hits / 0 misses. Same `FfmpegVideoEngine.engineId`
     * across runs ensures the fingerprint matches; same `OutputSpec`
     * (resolution / fps / codec) ensures every other axis of the
     * fingerprint matches too.
     *
     * Bit-identity is the load-bearing assertion: the only way render 2
     * could diverge in bytes is if the concat-demuxer fast-path renders
     * the cached mezzanines into a different container than render 1's
     * fresh mezzanines. That would silently break every cache-hit export
     * in production — exactly the regression a unit test against
     * `FakePerClipEngine` cannot catch.
     */
    @Test
    fun threeClipRerunHitsCacheAndProducesBitIdenticalBytes() = runTest(timeout = kotlin.time.Duration.parse("180s")) {
        if (!ffmpegOnPath()) return@runTest

        // testsrc is deterministic given identical args (same duration / size /
        // rate). 2-second source gives us room for 3 × 0.5s slices with margin.
        generateTestSource(input)

        val fixture = buildFixture()

        // First export — empty clipRenderCache, all 3 clips miss.
        val first = fixture.registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", fixture.projectId.value)
                put("outputPath", outputA.absolutePath)
                put("width", 320)
                put("height", 240)
                put("frameRate", 24)
            },
            fixture.ctx,
        )
        val firstOut = first.data as ExportTool.Output
        assertEquals(0, firstOut.perClipCacheHits, "first export: empty per-clip cache means zero hits")
        assertEquals(
            3, firstOut.perClipCacheMisses,
            "first export: every clip missed (got hits=${firstOut.perClipCacheHits}, misses=${firstOut.perClipCacheMisses})",
        )
        assertTrue(outputA.exists(), "first export did not land")
        assertTrue(outputA.length() > 1024, "first export too small (${outputA.length()} bytes)")

        // Second export — `forceRender=true` bypasses the whole-export
        // RenderCache so we re-enter `runPerClipRender`; the per-clip
        // mezzanines from render 1 are still on disk + still in
        // clipRenderCache, so all 3 fingerprints hit.
        val second = fixture.registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", fixture.projectId.value)
                put("outputPath", outputB.absolutePath)
                put("width", 320)
                put("height", 240)
                put("frameRate", 24)
                put("forceRender", true)
            },
            fixture.ctx,
        )
        val secondOut = second.data as ExportTool.Output
        assertEquals(
            3, secondOut.perClipCacheHits,
            "second export: every clip should hit the per-clip cache (got hits=${secondOut.perClipCacheHits}, misses=${secondOut.perClipCacheMisses})",
        )
        assertEquals(0, secondOut.perClipCacheMisses, "second export: zero re-renders expected")

        assertTrue(outputB.exists(), "second export did not land")
        assertEquals(
            outputA.length(), outputB.length(),
            "exports diverge in size — concat-demuxer not deterministic across cache-hit / fresh-render runs",
        )
        val hashA = sha256(outputA)
        val hashB = sha256(outputB)
        assertEquals(
            hashA, hashB,
            "per-clip incremental render not byte-identical between fresh-render and cache-hit runs.\n" +
                "A=${outputA.absolutePath} sha256=$hashA\nB=${outputB.absolutePath} sha256=$hashB",
        )
    }

    private data class Fixture(
        val projectId: ProjectId,
        val registry: ToolRegistry,
        val ctx: ToolContext,
    )

    private suspend fun buildFixture(): Fixture {
        val engine = FfmpegVideoEngine(
            pathResolver = io.talevia.core.platform.MediaPathResolver {
                error("per-render resolver must be passed via render(resolver=...)")
            },
        )
        val projects = ProjectStoreTestKit.create()
        val perms = AllowAllPermissionService()

        val projectId = ProjectId(Uuid.random().toString())
        projects.upsert(
            "perclip-incremental",
            Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
        )

        val registry = ToolRegistry().apply {
            register(ImportMediaTool(engine, projects))
            register(ClipActionTool(projects))
            register(ExportTool(projects, engine))
        }

        val ctx = ToolContext(
            sessionId = SessionId("perclip-incr"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { },
            messages = emptyList(),
        )

        val import = registry["import_media"]!!.dispatch(
            buildJsonObject {
                put("path", input.absolutePath)
                put("projectId", projectId.value)
                // Reference-mode: avoid copying the testsrc into the bundle,
                // matching FfmpegEndToEndTest. The fingerprint is invariant
                // under MediaSource (clip serialization references assetId
                // which stays identical across reads).
                put("copy_into_bundle", false)
            },
            ctx,
        )
        val assetId = (import.data as ImportMediaTool.Output).assetId

        // Three sequential clips of the same source asset, each playing a
        // distinct 0.5s slice. Three distinct `sourceRange.start` values
        // mean three distinct `Clip.Video` JSON serializations → three
        // distinct mezzanine fingerprints. Slicing rather than 3 separate
        // imports keeps the test fast (one ffmpeg generation pass).
        registry["clip_action"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                put("action", "add")
                putJsonArray("addItems") {
                    addJsonObject {
                        put("assetId", assetId)
                        put("sourceStartSeconds", 0.0)
                        put("durationSeconds", 0.5)
                    }
                    addJsonObject {
                        put("assetId", assetId)
                        put("sourceStartSeconds", 0.5)
                        put("durationSeconds", 0.5)
                    }
                    addJsonObject {
                        put("assetId", assetId)
                        put("sourceStartSeconds", 1.0)
                        put("durationSeconds", 0.5)
                    }
                }
            },
            ctx,
        )

        return Fixture(projectId, registry, ctx)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ffmpegOnPath(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun generateTestSource(target: File) {
        val args = listOf(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=2:size=320x240:rate=24",
            "-f", "lavfi", "-i", "anullsrc=cl=stereo:r=44100",
            "-shortest",
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            "-c:a", "aac",
            target.absolutePath,
        )
        val proc = ProcessBuilder(args).redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }
        check(proc.waitFor() == 0) { "ffmpeg failed to generate $target" }
    }
}
