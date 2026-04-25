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
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Opt-in user-asset smoke. Runs only when env vars
 * `TALEVIA_SMOKE_INPUT_VIDEO` + `TALEVIA_SMOKE_OUTPUT_DIR` are set —
 * otherwise no-op so this never trips CI / regular dev runs.
 *
 * Drives the import → add-clip → export pipeline directly (no LLM in the
 * loop) against a real video file the developer points at, producing a
 * real `.mp4` in the supplied output directory. Used to validate the
 * end-to-end render path against actual user footage after the assetId-
 * leakage fix in `ImportMediaTool` / `AssetsQuery`.
 */
@OptIn(ExperimentalUuidApi::class)
class UserAssetsSmokeTest {

    @Test
    fun importAddExportProducesPlayableMp4() = runTest(timeout = kotlin.time.Duration.parse("120s")) {
        val inputVideo = System.getenv("TALEVIA_SMOKE_INPUT_VIDEO")?.takeIf { it.isNotBlank() }
            ?: return@runTest
        val outputDir = System.getenv("TALEVIA_SMOKE_OUTPUT_DIR")?.takeIf { it.isNotBlank() }
            ?: return@runTest
        if (!ffmpegOnPath()) return@runTest

        val inputFile = File(inputVideo)
        require(inputFile.exists()) { "input video not found: $inputVideo" }
        val outDir = File(outputDir).apply { mkdirs() }
        val output = File(outDir, "vlog_smoke.mp4")
        if (output.exists()) output.delete()

        val engine = FfmpegVideoEngine(
            pathResolver = io.talevia.core.platform.MediaPathResolver {
                error("per-render resolver passed via render(resolver=...)")
            },
        )
        val projects = ProjectStoreTestKit.create()
        val perms = AllowAllPermissionService()

        val pid = ProjectId(Uuid.random().toString())
        projects.upsert(
            "user-assets-smoke",
            Project(id = pid, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P),
        )

        val registry = ToolRegistry().apply {
            register(ImportMediaTool(engine, projects))
            register(ClipActionTool(projects))
            register(ExportTool(projects, engine))
        }
        val ctx = ToolContext(
            sessionId = SessionId("smoke"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { },
            messages = emptyList(),
        )

        val import = registry["import_media"]!!.dispatch(
            buildJsonObject {
                put("path", inputFile.absolutePath)
                put("projectId", pid.value)
                put("copy_into_bundle", false)
            },
            ctx,
        )
        val assetId = (import.data as ImportMediaTool.Output).assetId
        assertTrue(assetId.isNotEmpty(), "import_media must return an assetId")

        registry["clip_action"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("action", "add")
                putJsonArray("addItems") {
                    addJsonObject {
                        put("assetId", assetId)
                        put("sourceStartSeconds", 0.0)
                        put("durationSeconds", 5.0)
                    }
                }
            },
            ctx,
        )

        registry["export"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("outputPath", output.absolutePath)
                put("width", 1920)
                put("height", 1080)
                put("frameRate", 30)
            },
            ctx,
        )

        assertTrue(output.exists(), "expected rendered file at ${output.absolutePath}")
        assertTrue(
            output.length() > 10_000,
            "rendered file is suspiciously small (${output.length()} bytes)",
        )
        println("Smoke OK: wrote ${output.absolutePath} (${output.length()} bytes)")
    }

    private fun ffmpegOnPath(): Boolean = runCatching {
        ProcessBuilder("ffmpeg", "-version")
            .redirectErrorStream(true)
            .start()
            .also { it.inputStream.readAllBytes(); it.waitFor() }
            .exitValue() == 0
    }.getOrDefault(false)
}
