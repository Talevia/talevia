package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Extract a single frame from a video asset and register the resulting image
 * as a new project asset. Pairs with [io.talevia.core.tool.builtin.ml.DescribeAssetTool]
 * (video content understanding) and [io.talevia.core.tool.builtin.aigc.GenerateImageTool]
 * (reference image input) — both of those operate on stills, so this tool closes
 * the video→image edge that VISION §5.2 ML lane needs.
 *
 * Implementation: delegates to [VideoEngine.thumbnail], which every platform
 * engine (FFmpeg on JVM, AVFoundation on iOS, Media3 on Android) already
 * implements for timeline preview. Bytes are persisted into the project
 * bundle via [BundleBlobWriter] so the extracted frame travels alongside
 * the project on `git push` / `cp -R`.
 *
 * Permission: `"media.import"` — this is a local-only derivation with no
 * network egress, same category as `import_media`.
 */
class ExtractFrameTool(
    private val engine: VideoEngine,
    private val projects: ProjectStore,
    private val bundleBlobWriter: BundleBlobWriter,
) : Tool<ExtractFrameTool.Input, ExtractFrameTool.Output> {

    @Serializable data class Input(
        val assetId: String,
        val timeSeconds: Double,
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
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
        "Extract a single frame from a video asset at [timeSeconds] and register it as a new " +
            "image asset in the project bundle's media/ directory. Use it to feed video content " +
            "into describe_asset or as a reference image for generate_image. Fails if the " +
            "timestamp is outside the source clip duration."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("media.import")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

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
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to use the session's current project (set via switch_project).",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("assetId"), JsonPrimitive("timeSeconds"))))
        put("additionalProperties", false)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        val assetId = AssetId(input.assetId)
        val project = projects.get(pid) ?: error("project ${pid.value} not found")
        val asset = project.assets.firstOrNull { it.id == assetId }
            ?: error("asset ${input.assetId} not found in project ${pid.value}; import_media first.")

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
        val frameAssetId = AssetId(Uuid.random().toString())
        val frameSource = bundleBlobWriter.writeBlob(pid, frameAssetId, bytes, "png")
        val frameAsset = MediaAsset(
            id = frameAssetId,
            source = frameSource,
            metadata = MediaMetadata(
                duration = Duration.ZERO,
                resolution = asset.metadata.resolution,
                frameRate = null,
            ),
        )
        projects.mutate(pid) { p -> p.copy(assets = p.assets + frameAsset) }

        val out = Output(
            sourceAssetId = input.assetId,
            frameAssetId = frameAssetId.value,
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
