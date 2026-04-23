package io.talevia.platform.ffmpeg

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.Clip
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.AddSubtitlesTool
import io.talevia.core.tool.builtin.video.AddTransitionTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * End-to-end smoke: generate two test videos with `ffmpeg -f lavfi`, then drive
 * the four core video tools (import → add → add → export) directly. Asserts that
 * the rendered file exists and is non-trivially sized.
 *
 * Skipped automatically when `ffmpeg` is not on PATH (CI/dev machine without it).
 */
@OptIn(ExperimentalUuidApi::class)
class FfmpegEndToEndTest {
    private lateinit var workDir: File
    private lateinit var inputA: File
    private lateinit var inputB: File
    private lateinit var output: File

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("talevia-ffmpeg-").toFile()
        inputA = File(workDir, "a.mp4")
        inputB = File(workDir, "b.mp4")
        output = File(workDir, "out.mp4")
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun importTwoClipsAndExport() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        if (!ffmpegOnPath()) return@runTest

        // 1. Generate two 2-second test sources with audio.
        generateTestSource(inputA, "testsrc")
        generateTestSource(inputB, "testsrc2")

        // 2. Wire the agent's view of the world.
        val bus = EventBus()
        val media = InMemoryMediaStorage()
        val engine = FfmpegVideoEngine(pathResolver = media)
        val projects = ProjectStoreTestKit.create()
        val perms = AllowAllPermissionService()

        val projectId = ProjectId(Uuid.random().toString())
        projects.upsert(
            "smoke",
            Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
        )

        val registry = ToolRegistry().apply {
            register(ImportMediaTool(media, engine, projects))
            register(AddClipTool(projects, media))
            register(ExportTool(projects, engine))
        }

        val ctx = ToolContext(
            sessionId = SessionId("test"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { /* swallow render-progress parts */ },
            messages = emptyList(),
        )

        // 3. Drive the chain. AssetIds come back from import_media; MediaPathResolver
        //    (InMemoryMediaStorage) resolves them to the original file paths at render time.
        val importA = registry["import_media"]!!.dispatch(
            buildJsonObject {
                put("path", inputA.absolutePath)
                put("projectId", projectId.value)
            },
            ctx,
        )
        val importB = registry["import_media"]!!.dispatch(
            buildJsonObject {
                put("path", inputB.absolutePath)
                put("projectId", projectId.value)
            },
            ctx,
        )
        val assetIdA = (importA.data as io.talevia.core.tool.builtin.video.ImportMediaTool.Output).assetId
        val assetIdB = (importB.data as io.talevia.core.tool.builtin.video.ImportMediaTool.Output).assetId

        registry["add_clips"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                putJsonArray("items") {
                    addJsonObject { put("assetId", assetIdA) }
                    addJsonObject { put("assetId", assetIdB) }
                }
            },
            ctx,
        )

        registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                put("outputPath", output.absolutePath)
                put("width", 320)
                put("height", 240)
                put("frameRate", 24)
            },
            ctx,
        )

        // 4. Assert the render landed.
        assertTrue(output.exists(), "output mp4 should exist at ${output.absolutePath}")
        assertTrue(output.length() > 1024, "output mp4 should be non-trivial in size (${output.length()} bytes)")
    }

    /**
     * Regression: verifies the filtergraph with `drawtext` survives round-trip
     * through real ffmpeg. Catches escape-quoting bugs that unit tests can
     * only see statically (colons, single quotes, commas mangling the graph).
     */
    @Test
    fun renderWithSubtitleProducesVideo() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        if (!ffmpegOnPath()) return@runTest
        // drawtext needs libfreetype linked in — some minimal ffmpeg builds
        // (e.g. homebrew's default bottle on macOS) ship without it and fail
        // with "No such filter: 'drawtext'". Skip so the rest of the E2E
        // suite can still catch regressions on those environments.
        if (!drawtextFilterAvailable()) return@runTest

        generateTestSource(inputA, "testsrc")

        val media = InMemoryMediaStorage()
        val engine = FfmpegVideoEngine(pathResolver = media)
        val projects = ProjectStoreTestKit.create()
        val perms = AllowAllPermissionService()

        val projectId = ProjectId(Uuid.random().toString())
        projects.upsert(
            "subs-smoke",
            Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
        )

        val registry = ToolRegistry().apply {
            register(ImportMediaTool(media, engine, projects))
            register(AddClipTool(projects, media))
            register(AddSubtitlesTool(projects))
            register(ExportTool(projects, engine))
        }

        val ctx = ToolContext(
            sessionId = SessionId("test"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { },
            messages = emptyList(),
        )

        val import = registry["import_media"]!!.dispatch(
            buildJsonObject {
                put("path", inputA.absolutePath)
                put("projectId", projectId.value)
            },
            ctx,
        )
        val assetId = (import.data as io.talevia.core.tool.builtin.video.ImportMediaTool.Output).assetId
        registry["add_clips"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                putJsonArray("items") {
                    addJsonObject { put("assetId", assetId) }
                }
            },
            ctx,
        )
        // Subtitle text exercises filtergraph escaping: colon, single quote, comma.
        registry["add_subtitles"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                putJsonArray("subtitles") {
                    add(
                        buildJsonObject {
                            put("text", "hi: it's, you")
                            put("timelineStartSeconds", 0.2)
                            put("durationSeconds", 1.5)
                        },
                    )
                }
                put("fontSize", 36f)
                put("color", "#FFFF00")
                put("backgroundColor", "#000000")
            },
            ctx,
        )
        registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                put("outputPath", output.absolutePath)
                put("width", 320)
                put("height", 240)
                put("frameRate", 24)
            },
            ctx,
        )

        assertTrue(output.exists(), "output mp4 should exist at ${output.absolutePath}")
        assertTrue(output.length() > 1024, "output mp4 should be non-trivial (${output.length()} bytes)")
    }

    /**
     * Regression: verifies that AddTransitionTool's Effect-track transition clip
     * becomes a real `fade` filter on the neighbouring video clips. Catches
     * regressions in `transitionFadesFor` (wrong boundary match) or
     * `buildFadeChain` (malformed filtergraph segment).
     */
    @Test
    fun renderWithTransitionProducesVideo() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        if (!ffmpegOnPath()) return@runTest

        generateTestSource(inputA, "testsrc")
        generateTestSource(inputB, "testsrc2")

        val media = InMemoryMediaStorage()
        val engine = FfmpegVideoEngine(pathResolver = media)
        val projects = ProjectStoreTestKit.create()
        val perms = AllowAllPermissionService()

        val projectId = ProjectId(Uuid.random().toString())
        projects.upsert(
            "trans-smoke",
            Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
        )

        val registry = ToolRegistry().apply {
            register(ImportMediaTool(media, engine, projects))
            register(AddClipTool(projects, media))
            register(AddTransitionTool(projects))
            register(ExportTool(projects, engine))
        }

        val ctx = ToolContext(
            sessionId = SessionId("test"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { },
            messages = emptyList(),
        )

        val importA = registry["import_media"]!!.dispatch(
            buildJsonObject {
                put("path", inputA.absolutePath)
                put("projectId", projectId.value)
            },
            ctx,
        )
        val importB = registry["import_media"]!!.dispatch(
            buildJsonObject {
                put("path", inputB.absolutePath)
                put("projectId", projectId.value)
            },
            ctx,
        )
        val assetIdA = (importA.data as io.talevia.core.tool.builtin.video.ImportMediaTool.Output).assetId
        val assetIdB = (importB.data as io.talevia.core.tool.builtin.video.ImportMediaTool.Output).assetId

        val addBoth = registry["add_clips"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                putJsonArray("items") {
                    addJsonObject { put("assetId", assetIdA) }
                    addJsonObject { put("assetId", assetIdB) }
                }
            },
            ctx,
        )
        val addResults = (addBoth.data as io.talevia.core.tool.builtin.video.AddClipTool.Output).results
        val clipIdA = addResults[0].clipId
        val clipIdB = addResults[1].clipId

        registry["add_transitions"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                putJsonArray("items") {
                    addJsonObject {
                        put("fromClipId", clipIdA)
                        put("toClipId", clipIdB)
                        put("transitionName", "fade")
                        put("durationSeconds", 0.5)
                    }
                }
            },
            ctx,
        )

        registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                put("outputPath", output.absolutePath)
                put("width", 320)
                put("height", 240)
                put("frameRate", 24)
            },
            ctx,
        )

        assertTrue(output.exists(), "output mp4 should exist at ${output.absolutePath}")
        assertTrue(output.length() > 1024, "output mp4 should be non-trivial (${output.length()} bytes)")
    }

    /**
     * Progressive export preview (VISION §5.4). Drives `engine.render` directly
     * on a 4-second timeline, asserts ffmpeg's side-output pipeline actually
     * emits at least one `RenderProgress.Preview` event with a ratio inside
     * [0, 1] and a path under the output's parent directory. Goes direct-to-
     * engine instead of through `ExportTool` so the test isolates the preview
     * contract from the tool's render cache and per-clip branches.
     */
    @Test
    fun renderEmitsProgressivePreviewEvents() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        if (!ffmpegOnPath()) return@runTest

        generateTestSourceWithDuration(inputA, "testsrc", durationSeconds = 4)
        val assetId = AssetId("a1")
        val resolver = object : io.talevia.core.platform.MediaPathResolver {
            override suspend fun resolve(assetId: AssetId): String = inputA.absolutePath
        }
        val engine = FfmpegVideoEngine(pathResolver = resolver)

        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(kotlin.time.Duration.ZERO, kotlin.time.Duration.parse("4s")),
                            sourceRange = TimeRange(kotlin.time.Duration.ZERO, kotlin.time.Duration.parse("4s")),
                            assetId = assetId,
                        ),
                    ),
                ),
            ),
            duration = kotlin.time.Duration.parse("4s"),
        )
        val out = OutputSpec(
            targetPath = output.absolutePath,
            resolution = Resolution(320, 240),
            frameRate = 24,
        )

        val events = engine.render(timeline, out).toList()
        val previews = events.filterIsInstance<RenderProgress.Preview>()
        assertTrue(
            previews.isNotEmpty(),
            "engine must emit at least one Preview event during a 4-second render; got events: " +
                events.joinToString(", ") { it::class.simpleName ?: "?" },
        )
        for (p in previews) {
            assertTrue(p.ratio in 0f..1f, "Preview ratio out of bounds: ${p.ratio}")
            assertTrue(
                p.thumbnailPath.startsWith(output.parentFile.absolutePath),
                "Preview thumbnailPath should live next to the export target: ${p.thumbnailPath}",
            )
            assertTrue(
                p.thumbnailPath.endsWith(".jpg"),
                "Preview thumbnailPath should be a .jpg: ${p.thumbnailPath}",
            )
        }
        assertTrue(
            events.any { it is RenderProgress.Completed },
            "render must still complete alongside preview events",
        )
        // Sidecar must be cleaned up after the flow terminates (Completed/Failed).
        val sidecarFiles = output.parentFile.listFiles { f -> f.name.startsWith(".talevia-preview-") && f.name.endsWith(".jpg") }
        assertTrue(
            sidecarFiles == null || sidecarFiles.isEmpty(),
            "preview sidecar must be deleted after render completes; found: ${sidecarFiles?.toList()}",
        )
    }

    /**
     * VISION §5.3 — the exported mp4 must carry a [ProvenanceManifest] back
     * to its source Project + Timeline hash, baked into the container's
     * `comment` metadata by `FfmpegVideoEngine.render` via
     * `-metadata comment=<manifest>`. This regression drives the full loop
     * through real ffmpeg: export → probe → decode, asserting the
     * round-trip produces the same manifest `ExportTool` minted on input.
     */
    @Test
    fun exportStampsProvenanceManifestProbeRoundTrips() = runTest(timeout = kotlin.time.Duration.parse("60s")) {
        if (!ffmpegOnPath()) return@runTest

        generateTestSource(inputA, "testsrc")

        val media = InMemoryMediaStorage()
        val engine = FfmpegVideoEngine(pathResolver = media)
        val projects = ProjectStoreTestKit.create()
        val perms = AllowAllPermissionService()

        val projectId = ProjectId(Uuid.random().toString())
        projects.upsert(
            "prov-smoke",
            Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
        )

        val registry = ToolRegistry().apply {
            register(ImportMediaTool(media, engine, projects))
            register(AddClipTool(projects, media))
            register(ExportTool(projects, engine))
        }

        val ctx = ToolContext(
            sessionId = SessionId("test"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { },
            messages = emptyList(),
        )

        val import = registry["import_media"]!!.dispatch(
            buildJsonObject {
                put("path", inputA.absolutePath)
                put("projectId", projectId.value)
            },
            ctx,
        )
        val assetId = (import.data as io.talevia.core.tool.builtin.video.ImportMediaTool.Output).assetId
        registry["add_clips"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                putJsonArray("items") {
                    addJsonObject { put("assetId", assetId) }
                }
            },
            ctx,
        )
        val exportResult = registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                put("outputPath", output.absolutePath)
                put("width", 320)
                put("height", 240)
                put("frameRate", 24)
            },
            ctx,
        )
        val exportOutput = exportResult.data as io.talevia.core.tool.builtin.video.ExportTool.Output
        val expected = exportOutput.provenance
        assertTrue(expected != null, "ExportTool must surface the provenance manifest it baked")
        assertTrue(output.exists(), "output mp4 should exist at ${output.absolutePath}")

        // Probe the output via the same engine and decode the manifest back.
        val probed = engine.probe(io.talevia.core.domain.MediaSource.File(output.absolutePath))
        val comment = probed.comment
        assertTrue(
            comment != null,
            "probe must surface the comment tag ffmpeg wrote via -metadata comment=<manifest>",
        )
        assertTrue(
            comment.startsWith(io.talevia.core.domain.render.ProvenanceManifest.MANIFEST_PREFIX),
            "comment must begin with the Talevia manifest prefix: $comment",
        )
        val decoded = io.talevia.core.domain.render.ProvenanceManifest.decodeFromComment(comment)
        assertTrue(decoded != null, "decodeFromComment must parse the comment ffmpeg round-tripped")
        kotlin.test.assertEquals(expected, decoded, "probe → decode must reproduce what ExportTool baked")
    }

    private fun ffmpegOnPath(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun drawtextFilterAvailable(): Boolean = runCatching {
        val proc = ProcessBuilder("ffmpeg", "-hide_banner", "-filters")
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor()
        output.lineSequence().any { line -> line.trim().startsWith("T") && " drawtext " in line }
    }.getOrDefault(false)

    private fun generateTestSource(target: File, pattern: String) {
        generateTestSourceWithDuration(target, pattern, durationSeconds = 2)
    }

    private fun generateTestSourceWithDuration(target: File, pattern: String, durationSeconds: Int) {
        val args = listOf(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "$pattern=duration=$durationSeconds:size=320x240:rate=24",
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
