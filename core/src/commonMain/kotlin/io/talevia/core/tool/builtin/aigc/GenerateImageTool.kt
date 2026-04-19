package io.talevia.core.tool.builtin.aigc

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.source.consistency.foldConsistencyIntoPrompt
import io.talevia.core.domain.source.consistency.resolveConsistencyBindings
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
 * Consistency bindings (VISION §3.3): when [Input.projectId] is present and
 * [Input.consistencyBindingIds] names source nodes of kind
 * `core.consistency.*`, the tool folds their textual descriptions into the
 * effective prompt and records the applied ids on the output (which downstream
 * tools — add_clip — wire into [io.talevia.core.domain.Clip.sourceBinding]).
 * This is the primary vehicle by which "same character in 50 shots" works:
 * changing one `CharacterRefBody` propagates through `Source.stale` to mark
 * every clip it conditioned as needing re-render.
 *
 * Permission: `"aigc.generate"` — defaults to ASK via
 * [io.talevia.core.permission.DefaultPermissionRuleset]; the server maps
 * ASK to deny, which is the correct headless default.
 */
class GenerateImageTool(
    private val engine: ImageGenEngine,
    private val storage: MediaStorage,
    private val blobWriter: MediaBlobWriter,
    private val projectStore: ProjectStore? = null,
) : Tool<GenerateImageTool.Input, GenerateImageTool.Output> {

    @Serializable
    data class Input(
        val prompt: String,
        val model: String = "gpt-image-1",
        val width: Int = 1024,
        val height: Int = 1024,
        val seed: Long? = null,
        /**
         * Optional project whose [io.talevia.core.domain.source.Source] holds the
         * consistency bindings named by [consistencyBindingIds]. Required when
         * [consistencyBindingIds] is non-empty; otherwise ignored.
         */
        val projectId: String? = null,
        /**
         * Ids of consistency source nodes to fold into the prompt (VISION §3.3).
         * Ids that don't resolve (missing node, wrong kind) are silently skipped
         * and reported via [Output.appliedConsistencyBindingIds].
         */
        val consistencyBindingIds: List<String> = emptyList(),
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
        /**
         * The prompt actually sent to the provider after consistency folding.
         * Equal to [Input.prompt] when no bindings were applied. Surfaced so the
         * agent can reason about "did my bindings take effect?".
         */
        val effectivePrompt: String,
        /**
         * Subset of [Input.consistencyBindingIds] that resolved to real nodes.
         * Downstream tools wiring this asset into a clip should pass this set
         * to [io.talevia.core.domain.Clip.sourceBinding].
         */
        val appliedConsistencyBindingIds: List<String>,
    )

    override val id: String = "generate_image"
    override val helpText: String =
        "Generate an image from a text prompt via an AIGC provider and import it as a project asset. " +
            "Records seed + model in provenance so the generation can be replayed. " +
            "Pass consistencyBindingIds to reuse character / style / brand source nodes across shots."
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
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Required when consistencyBindingIds is non-empty — tells the tool which project's source to read.")
            }
            putJsonObject("consistencyBindingIds") {
                put("type", "array")
                put("description", "Source node ids (kind core.consistency.*) to fold into the prompt — characters, style bibles, brand palettes.")
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        // Always have a seed by the time we call the engine — see VISION §3.1.
        val seed = input.seed ?: Random.nextLong()

        val folded = resolveConsistency(input)
        val finalPrompt = folded.effectivePrompt

        val result = engine.generate(
            ImageGenRequest(
                prompt = finalPrompt,
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
            effectivePrompt = finalPrompt,
            appliedConsistencyBindingIds = folded.appliedNodeIds,
        )
        val bindingTail = if (folded.appliedNodeIds.isEmpty()) ""
        else " [bindings: ${folded.appliedNodeIds.joinToString(", ")}]"
        return ToolResult(
            title = "generate image",
            outputForLlm = "Generated ${image.width}x${image.height} image (asset ${out.assetId}) " +
                "via ${prov.providerId}/${prov.modelId} seed=${prov.seed}$bindingTail",
            data = out,
        )
    }

    private suspend fun resolveConsistency(input: Input): io.talevia.core.domain.source.consistency.FoldedPrompt {
        if (input.consistencyBindingIds.isEmpty()) {
            return foldConsistencyIntoPrompt(input.prompt, emptyList())
        }
        val store = projectStore
            ?: error("consistencyBindingIds supplied but this GenerateImageTool has no ProjectStore wired")
        val pid = input.projectId
            ?: error("consistencyBindingIds require projectId to locate the source graph")
        val project = store.get(ProjectId(pid))
            ?: error("Project $pid not found when resolving consistency bindings")
        val ids = input.consistencyBindingIds.map { SourceNodeId(it) }
        val resolved = project.source.resolveConsistencyBindings(ids)
        return foldConsistencyIntoPrompt(input.prompt, resolved)
    }
}
