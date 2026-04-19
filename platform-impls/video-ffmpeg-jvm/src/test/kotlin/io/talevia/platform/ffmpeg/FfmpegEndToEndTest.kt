package io.talevia.platform.ffmpeg

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
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
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val media = InMemoryMediaStorage()
        val engine = FfmpegVideoEngine(pathResolver = media)
        val projects = SqlDelightProjectStore(db)
        val perms = AllowAllPermissionService()

        val projectId = ProjectId(Uuid.random().toString())
        projects.upsert(
            "smoke",
            Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
        )

        val registry = ToolRegistry().apply {
            register(ImportMediaTool(media, engine))
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
        val importA = registry["import_media"]!!.dispatch(buildJsonObject { put("path", inputA.absolutePath) }, ctx)
        val importB = registry["import_media"]!!.dispatch(buildJsonObject { put("path", inputB.absolutePath) }, ctx)
        val assetIdA = (importA.data as io.talevia.core.tool.builtin.video.ImportMediaTool.Output).assetId
        val assetIdB = (importB.data as io.talevia.core.tool.builtin.video.ImportMediaTool.Output).assetId

        registry["add_clip"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                put("assetId", assetIdA)
            },
            ctx,
        )
        registry["add_clip"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                put("assetId", assetIdB)
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
        driver.close()
    }

    private fun ffmpegOnPath(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun generateTestSource(target: File, pattern: String) {
        val args = listOf(
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "$pattern=duration=2:size=320x240:rate=24",
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
