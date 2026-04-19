package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.MediaSource
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.PathGuard
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.DurationUnit

class ImportMediaTool(
    private val storage: MediaStorage,
    private val engine: VideoEngine,
) : Tool<ImportMediaTool.Input, ImportMediaTool.Output> {

    @Serializable data class Input(val path: String)
    @Serializable data class Output(
        val assetId: String,
        val durationSeconds: Double,
        val width: Int? = null,
        val height: Int? = null,
        val videoCodec: String? = null,
        val audioCodec: String? = null,
    )

    override val id = "import_media"
    override val helpText = "Import a media file by path; probes its metadata and registers it as a project asset."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("media.import")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "Absolute path to a video or audio file on the local filesystem.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        // Import reads from an arbitrary path on disk — reject traversal before we
        // pass it to the platform storage layer.
        PathGuard.validate(input.path, requireAbsolute = true)
        val asset = storage.import(MediaSource.File(input.path)) { source -> engine.probe(source) }
        val out = Output(
            assetId = asset.id.value,
            durationSeconds = asset.metadata.duration.toDouble(DurationUnit.SECONDS),
            width = asset.metadata.resolution?.width,
            height = asset.metadata.resolution?.height,
            videoCodec = asset.metadata.videoCodec,
            audioCodec = asset.metadata.audioCodec,
        )
        return ToolResult(
            title = "import ${input.path.substringAfterLast('/')}",
            outputForLlm = "Imported asset ${out.assetId} (${out.durationSeconds}s, ${out.width}x${out.height})",
            data = out,
        )
    }
}
