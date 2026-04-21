package io.talevia.platform.ffmpeg

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.video.AddClipTool
import io.talevia.core.tool.builtin.video.ExportTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * Regression guard: the same `Project` + same `OutputProfile` must export
 * to **byte-identical** mp4 bytes across two invocations. `RenderCache`
 * assumes bit-equality under project-hash + profile-hash; if ffmpeg
 * actually emits different bytes (encoder timestamps, libx264 thread
 * non-determinism, muxer metadata), a cache hit would return stale bytes
 * that drift from what a fresh re-render would produce.
 *
 * This test exports a fixed 2-second test-source project twice, computes
 * SHA-256 of each output, and asserts equality. The assertion turns
 * silent bit-drift into a loud failure.
 *
 * Skipped automatically when `ffmpeg` is not on PATH (CI / dev machines
 * without it). Mirrors the skip pattern in [FfmpegEndToEndTest].
 *
 * See `docs/decisions/2026-04-21-export-variant-deterministic-hash.md`
 * for the list of ffmpeg flags this test transitively validates.
 */
@OptIn(ExperimentalUuidApi::class)
class ExportDeterminismTest {
    private lateinit var workDir: File
    private lateinit var input: File
    private lateinit var outputA: File
    private lateinit var outputB: File

    @BeforeTest
    fun setUp() {
        workDir = Files.createTempDirectory("talevia-ffmpeg-det-").toFile()
        input = File(workDir, "src.mp4")
        outputA = File(workDir, "out-a.mp4")
        outputB = File(workDir, "out-b.mp4")
    }

    @AfterTest
    fun tearDown() {
        workDir.deleteRecursively()
    }

    @Test
    fun sameProjectTwiceProducesBitIdenticalMp4() = runTest(timeout = kotlin.time.Duration.parse("120s")) {
        if (!ffmpegOnPath()) return@runTest

        // 1. Generate a fixed, reproducible test source. testsrc pattern
        // is deterministic given identical args (same duration + size + rate).
        generateTestSource(input)

        // 2. Export once, snapshot the hash, export again, compare.
        val hashA = exportAndHash(outputA)
        val hashB = exportAndHash(outputB)

        assertTrue(outputA.exists(), "export A did not land")
        assertTrue(outputB.exists(), "export B did not land")
        assertTrue(outputA.length() > 1024, "export A too small (${outputA.length()} bytes)")
        assertEquals(outputA.length(), outputB.length(), "exports diverge in size")
        assertEquals(
            hashA,
            hashB,
            "exports diverge byte-for-byte despite identical inputs — RenderCache assumptions break.\n" +
                "A=${outputA.absolutePath} sha256=$hashA\nB=${outputB.absolutePath} sha256=$hashB",
        )
    }

    private suspend fun exportAndHash(output: File): String {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        try {
            val media = InMemoryMediaStorage()
            val engine = FfmpegVideoEngine(pathResolver = media)
            val projects = SqlDelightProjectStore(db)
            val perms = AllowAllPermissionService()

            val projectId = ProjectId(Uuid.random().toString())
            projects.upsert(
                "determinism",
                Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
            )

            val registry = ToolRegistry().apply {
                register(ImportMediaTool(media, engine))
                register(AddClipTool(projects, media))
                register(ExportTool(projects, engine))
            }

            val ctx = ToolContext(
                sessionId = SessionId("det"),
                messageId = MessageId("m"),
                callId = CallId("c"),
                askPermission = { perms.check(emptyList(), it) },
                emitPart = { },
                messages = emptyList(),
            )

            val import = registry["import_media"]!!.dispatch(
                buildJsonObject { put("path", input.absolutePath) },
                ctx,
            )
            val assetId = (import.data as ImportMediaTool.Output).assetId
            registry["add_clip"]!!.dispatch(
                buildJsonObject {
                    put("projectId", projectId.value)
                    put("assetId", assetId)
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
            return sha256(output)
        } finally {
            driver.close()
        }
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
