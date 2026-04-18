package io.talevia.core.tool.builtin.video

import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.session.Part
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.DurationUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ExportTool(
    private val store: ProjectStore,
    private val engine: VideoEngine,
    private val clock: Clock = Clock.System,
) : Tool<ExportTool.Input, ExportTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val outputPath: String,
        val width: Int? = null,
        val height: Int? = null,
        val frameRate: Int? = null,
        val videoCodec: String = "h264",
        val audioCodec: String = "aac",
    )
    @Serializable data class Output(
        val outputPath: String,
        val durationSeconds: Double,
        val resolutionWidth: Int,
        val resolutionHeight: Int,
    )

    override val id = "export"
    override val description = "Render the project's timeline to a media file at outputPath. Streams progress as render-progress parts."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("media.export.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("outputPath") { put("type", "string"); put("description", "Absolute path where the rendered file should be written.") }
            putJsonObject("width") { put("type", "integer") }
            putJsonObject("height") { put("type", "integer") }
            putJsonObject("frameRate") { put("type", "integer") }
            putJsonObject("videoCodec") { put("type", "string") }
            putJsonObject("audioCodec") { put("type", "string") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("outputPath"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val project = store.get(ProjectId(input.projectId)) ?: error("Project ${input.projectId} not found")
        val timeline = project.timeline
        val width = input.width ?: timeline.resolution.width
        val height = input.height ?: timeline.resolution.height
        val fps = input.frameRate ?: timeline.frameRate.numerator / timeline.frameRate.denominator

        val output = OutputSpec(
            targetPath = input.outputPath,
            resolution = Resolution(width, height),
            frameRate = fps,
            videoCodec = input.videoCodec,
            audioCodec = input.audioCodec,
        )

        val jobId = Uuid.random().toString()
        var failure: String? = null
        engine.render(timeline, output).collect { ev ->
            val partId = PartId(Uuid.random().toString())
            when (ev) {
                is RenderProgress.Started -> ctx.emitPart(
                    Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = 0f, message = "started"),
                )
                is RenderProgress.Frames -> ctx.emitPart(
                    Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = ev.ratio, message = ev.message),
                )
                is RenderProgress.Completed -> ctx.emitPart(
                    Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = 1f, message = "completed"),
                )
                is RenderProgress.Failed -> {
                    failure = ev.message
                    ctx.emitPart(
                        Part.RenderProgress(partId, ctx.messageId, ctx.sessionId, clock.now(), jobId = ev.jobId, ratio = 0f, message = "failed: ${ev.message}"),
                    )
                }
            }
        }
        if (failure != null) error("export failed: $failure")

        val duration = timeline.duration.toDouble(DurationUnit.SECONDS)
        val out = Output(input.outputPath, duration, width, height)
        return ToolResult(
            title = "export → ${input.outputPath.substringAfterLast('/')}",
            outputForLlm = "Exported timeline (${duration}s, ${width}x${height}) to ${input.outputPath}",
            data = out,
        )
    }
}
