package io.talevia.core.tool.builtin.aigc

import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.Resolution
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.ImageGenEngine
import io.talevia.core.platform.ImageGenRequest
import io.talevia.core.platform.MediaBlobWriter
import io.talevia.core.platform.MediaStorage
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
import kotlin.random.Random
import kotlin.time.Duration

/**
 * Generate an image via an [ImageGenEngine], persist the bytes through a
 * [MediaBlobWriter], register the result with [MediaStorage], and surface a
 * [io.talevia.core.AssetId] that downstream tools (e.g. `add_clip`) can use.
 *
 * Seed handling per VISION §3.1 "seed 显式，不是默认随机": if the caller does
 * not supply one we generate a [Random.nextLong] client-side BEFORE calling
 * the engine, so [Output.seed] is always non-null and provenance is always
 * complete. Not doing this would let the provider pick an opaque seed we
 * cannot replay, which is exactly the failure mode VISION §3.1 forbids.
 *
 * Permission: `"aigc.generate"` — defaults to ASK via
 * [io.talevia.core.permission.DefaultPermissionRuleset]; the server maps
 * ASK to deny, which is the correct headless default.
 */
class GenerateImageTool(
    private val engine: ImageGenEngine,
    private val storage: MediaStorage,
    private val blobWriter: MediaBlobWriter,
) : Tool<GenerateImageTool.Input, GenerateImageTool.Output> {

    @Serializable
    data class Input(
        val prompt: String,
        val model: String = "gpt-image-1",
        val width: Int = 1024,
        val height: Int = 1024,
        val seed: Long? = null,
    )

    @Serializable
    data class Output(
        val assetId: String,
        val width: Int,
        val height: Int,
        val providerId: String,
        val modelId: String,
        val modelVersion: String?,
        val seed: Long,
        val parameters: JsonObject,
    )

    override val id: String = "generate_image"
    override val helpText: String =
        "Generate an image from a text prompt via an AIGC provider and import it as a project asset. " +
            "Records seed + model in provenance so the generation can be replayed."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "Text description of the image to generate.")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Provider-scoped model id (default: gpt-image-1).")
            }
            putJsonObject("width") {
                put("type", "integer")
                put("description", "Output image width in pixels (default: 1024).")
            }
            putJsonObject("height") {
                put("type", "integer")
                put("description", "Output image height in pixels (default: 1024).")
            }
            putJsonObject("seed") {
                put("type", "integer")
                put("description", "Optional seed for reproducibility. If omitted the tool picks one client-side so provenance is still complete.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        // Always have a seed by the time we call the engine — see VISION §3.1.
        val seed = input.seed ?: Random.nextLong()

        val result = engine.generate(
            ImageGenRequest(
                prompt = input.prompt,
                modelId = input.model,
                width = input.width,
                height = input.height,
                seed = seed,
                n = 1,
            ),
        )
        val image = result.images.firstOrNull()
            ?: error("${engine.providerId} image-gen returned zero images")

        // Persist the PNG bytes, then import as an asset. Construct MediaMetadata
        // locally — probing a PNG through ffprobe would just echo back the
        // values we already have, at the cost of a subprocess.
        val source = blobWriter.writeBlob(image.pngBytes, "png")
        val asset = storage.import(source) { _ ->
            MediaMetadata(
                duration = Duration.ZERO,
                resolution = Resolution(image.width, image.height),
                frameRate = null,
            )
        }

        val prov = result.provenance
        val out = Output(
            assetId = asset.id.value,
            width = image.width,
            height = image.height,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            seed = prov.seed,
            parameters = prov.parameters,
        )
        return ToolResult(
            title = "generate image",
            outputForLlm = "Generated ${image.width}x${image.height} image (asset ${out.assetId}) " +
                "via ${prov.providerId}/${prov.modelId} seed=${prov.seed}",
            data = out,
        )
    }
}
