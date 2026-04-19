package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaStorage
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Extract a single frame from a video asset and register the resulting image
 * as a new asset. Pairs with [io.talevia.core.tool.builtin.ml.DescribeAssetTool]
 * (video content understanding) and [io.talevia.core.tool.builtin.aigc.GenerateImageTool]
 * (reference image input) — both of those operate on stills, so this tool closes
 * the video→image edge that VISION §5.2 ML lane needs.
 *
 * Implementation: delegates to [VideoEngine.thumbnail], which every platform
 * engine (FFmpeg on JVM, AVFoundation on iOS, Media3 on Android) already
 * implements for timeline preview. That means no new engine surface — just
 * plumbing the bytes through [MediaBlobWriter] and [MediaStorage.import].
 *
 * Permission: `"media.import"` — this is a local-only derivation with no
 * network egress, same category as the source import_media tool.
 */
class ExtractFrameTool(
    private val engine: VideoEngine,
    private val storage: MediaStorage,
    private val blobWriter: MediaBlobWriter,
) : Tool<ExtractFrameTool.Input, ExtractFrameTool.Output> {

    @Serializable data class Input(
        val assetId: String,
        val timeSeconds: Double,
    )

    @Serializable data class Output(
        val sourceAssetId: String,
        val frameAssetId: String,
        val timeSeconds: Double,
        val width: Int?,
        val height: Int?,
    )

    override val id: String = "extract_frame"
    override val helpText: String =
        "Extract a single frame from a video asset at [timeSeconds] and import it as a new image asset. " +
            "Use it to feed video content into describe_asset or as a reference image for generate_image. " +
            "Fails if the timestamp is outside the source clip duration."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("media.import")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("assetId") {
                put("type", "string")
                put("description", "Asset id of a video (or any timed media) returned by import_media / generate_video.")
            }
            putJsonObject("timeSeconds") {
                put("type", "number")
                put("description", "Timestamp within the source asset, in seconds. Must be in [0, asset.duration].")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("assetId"), JsonPrimitive("timeSeconds"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val assetId = AssetId(input.assetId)
        val asset = storage.get(assetId)
            ?: error("Unknown assetId ${input.assetId}")

        require(input.timeSeconds >= 0.0) { "timeSeconds must be >= 0 (got ${input.timeSeconds})" }
        val duration = asset.metadata.duration
        val time = input.timeSeconds.seconds
        // Allow exactly-at-end grabs; some engines clamp, but reject explicitly-past-end.
        if (duration > Duration.ZERO) {
            require(time <= duration) {
                "timeSeconds ${input.timeSeconds} exceeds source duration ${duration.toDouble(DurationUnit.SECONDS)}"
            }
        }

        val bytes = engine.thumbnail(assetId, asset.source, time)
        val source = blobWriter.writeBlob(bytes, "png")
        val frameAsset = storage.import(source) { _ ->
            MediaMetadata(
                duration = Duration.ZERO,
                resolution = asset.metadata.resolution,
                frameRate = null,
            )
        }

        val out = Output(
            sourceAssetId = input.assetId,
            frameAssetId = frameAsset.id.value,
            timeSeconds = input.timeSeconds,
            width = asset.metadata.resolution?.width,
            height = asset.metadata.resolution?.height,
        )
        val dims = if (out.width != null && out.height != null) " ${out.width}x${out.height}" else ""
        return ToolResult(
            title = "extract frame @ ${input.timeSeconds}s",
            outputForLlm = "Extracted frame at ${input.timeSeconds}s from ${input.assetId} " +
                "-> new image asset ${out.frameAssetId}$dims",
            data = out,
        )
    }
}
