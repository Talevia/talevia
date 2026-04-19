package io.talevia.core.tool.builtin.ml

import io.talevia.core.AssetId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.VisionEngine
import io.talevia.core.platform.VisionRequest
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

/**
 * Describe an imported image asset via a [VisionEngine] (VISION §5.2 ML lane).
 * Mirror of [TranscribeAssetTool] for the vision modality — ASR turns audio →
 * timestamped text, this turns an image → free-form description.
 *
 * Use cases the agent should hit this for:
 *  - "What's in this photo?" after the user imports one.
 *  - "Pick the best shot from these imports" — describe each, then reason.
 *  - Auto-scaffolding a character_ref: describe a reference image, lift the
 *    description into `define_character_ref(visualDescription=...)`.
 *
 * Permission: `"ml.describe"` — defaults to ASK because the image is uploaded
 * to a third-party provider; user consent parallels the AIGC / ASR lanes.
 * Headless server resolves ASK to deny by design.
 *
 * Caching: v1 does not consult the project lockfile — `LockfileEntry` keys
 * assets, not derived text. If repeat-describe becomes a real workflow,
 * materialize the description as a JSON asset and key a lockfile entry off it
 * (same open we left on [TranscribeAssetTool]).
 *
 * Scope: images only. The engine fails loudly on non-image files — do NOT
 * attempt to frame-grab videos here; that requires a VideoEngine sidecar and
 * belongs in a separate tool if it ever becomes a real need.
 */
class DescribeAssetTool(
    private val engine: VisionEngine,
    private val resolver: MediaPathResolver,
) : Tool<DescribeAssetTool.Input, DescribeAssetTool.Output> {

    @Serializable data class Input(
        val assetId: String,
        val prompt: String? = null,
        val model: String = "gpt-4o-mini",
    )

    @Serializable data class Output(
        val assetId: String,
        val providerId: String,
        val modelId: String,
        val prompt: String?,
        val text: String,
    )

    override val id: String = "describe_asset"
    override val helpText: String =
        "Describe an imported image asset via a vision provider (default model: gpt-4o-mini). " +
            "Returns free-form text — subject, setting, colors, visible text. " +
            "Pass `prompt` to focus the description ('what brand is on the mug?', 'is there a person in frame?'). " +
            "Images only (png/jpg/webp/gif); non-image assets fail loudly. " +
            "Image bytes are uploaded to the provider — the user is asked to confirm before each call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("ml.describe")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("assetId") {
                put("type", "string")
                put("description", "Asset id returned by import_media or generate_image.")
            }
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "Optional focus question. Omit for a generic describe.")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Provider-scoped vision model id (default: gpt-4o-mini).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("assetId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val assetId = AssetId(input.assetId)
        val path = resolver.resolve(assetId)
        val result = engine.describe(
            VisionRequest(
                imagePath = path,
                modelId = input.model,
                prompt = input.prompt?.takeIf { it.isNotBlank() },
            ),
        )
        val out = Output(
            assetId = input.assetId,
            providerId = result.provenance.providerId,
            modelId = result.provenance.modelId,
            prompt = input.prompt?.takeIf { it.isNotBlank() },
            text = result.text,
        )
        val preview = result.text.take(160).replace('\n', ' ')
        val tail = if (preview.length < result.text.length) "…" else ""
        return ToolResult(
            title = "describe asset",
            outputForLlm = "Described asset ${input.assetId} via ${result.provenance.providerId}/${result.provenance.modelId}: " +
                "\"$preview$tail\"",
            data = out,
        )
    }
}
