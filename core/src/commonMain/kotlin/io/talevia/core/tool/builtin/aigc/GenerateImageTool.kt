package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.source.consistency.FoldedPrompt
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.Duration

/**
 * Generate an image via an [ImageGenEngine], persist the bytes through a
 * [MediaBlobWriter], register the result with [MediaStorage], and surface a
 * [AssetId] that downstream tools (e.g. `add_clip`) can use.
 *
 * Seed + provenance handling (VISION §3.1): delegates to [AigcPipeline] so this tool
 * and every future AIGC tool share one implementation of "mint a seed if missing,
 * record the full generation parameters."
 *
 * Lockfile cache (VISION §3.1 "产物可 pin"): before calling the engine the tool
 * hashes `(tool id, effective prompt, model, seed, dimensions, bindings, negative,
 * referenceAssetIds, loraPins)` and looks the hash up in [Project.lockfile]. On a
 * hit it returns the cached asset without re-calling the provider — bit-identical
 * reuse for the common "same inputs, different turn" case. On a miss it generates,
 * persists, and appends a new entry. Every axis the engine *could* vary on must
 * be hashed, otherwise semantically distinct generations would collide on the key.
 *
 * Consistency bindings (VISION §3.3): see [AigcPipeline.foldPrompt] — identical
 * behavior to the previous implementation.
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
        val projectId: String? = null,
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
        val effectivePrompt: String,
        val appliedConsistencyBindingIds: List<String>,
        /** Merged negative prompt from bound style_bibles (null when no negatives were folded). */
        val negativePrompt: String? = null,
        /** Asset ids of reference images passed to the engine (empty when no character_ref supplied any). */
        val referenceAssetIds: List<String> = emptyList(),
        /** LoRA adapter ids that were pinned via bound character_refs (empty when none supplied one). */
        val loraAdapterIds: List<String> = emptyList(),
        /** True when this asset came from [Project.lockfile] rather than a fresh engine call. */
        val cacheHit: Boolean = false,
    )

    override val id: String = "generate_image"
    override val helpText: String =
        "Generate an image from a text prompt via an AIGC provider and import it as a project asset. " +
            "Records seed + model in the project lockfile so a second call with identical inputs is a cache hit. " +
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
                put("description", "Optional seed for reproducibility. If omitted the tool picks one client-side so provenance is still complete. Explicit seeds make cache hits meaningful.")
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Required when consistencyBindingIds is non-empty or when the project lockfile cache should be consulted.")
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
        val seed = AigcPipeline.ensureSeed(input.seed)
        val folded = resolveConsistency(input)

        val referenceAssetPaths = folded.referenceAssetIds.map { resolveAssetPath(it) }
        val loraKey = folded.loraPins.joinToString(",") { "${it.adapterId}@${it.weight}" }

        val inputHash = AigcPipeline.inputHash(
            listOf(
                "tool" to id,
                "model" to input.model,
                "w" to input.width.toString(),
                "h" to input.height.toString(),
                "seed" to seed.toString(),
                "prompt" to folded.effectivePrompt,
                "bindings" to folded.appliedNodeIds.joinToString(","),
                "neg" to (folded.negativePrompt ?: ""),
                "refs" to folded.referenceAssetIds.joinToString(","),
                "lora" to loraKey,
            ),
        )

        val pid = input.projectId?.let(::ProjectId)
        val store = projectStore
        if (pid != null && store != null) {
            val cached = AigcPipeline.findCached(store, pid, inputHash)
            if (cached != null) {
                return hit(cached, folded, input)
            }
        }

        val result = engine.generate(
            ImageGenRequest(
                prompt = folded.effectivePrompt,
                modelId = input.model,
                width = input.width,
                height = input.height,
                seed = seed,
                n = 1,
                negativePrompt = folded.negativePrompt,
                referenceAssetPaths = referenceAssetPaths,
                loraPins = folded.loraPins,
            ),
        )
        val image = result.images.firstOrNull()
            ?: error("${engine.providerId} image-gen returned zero images")

        val source = blobWriter.writeBlob(image.pngBytes, "png")
        val asset = storage.import(source) { _ ->
            MediaMetadata(
                duration = Duration.ZERO,
                resolution = Resolution(image.width, image.height),
                frameRate = null,
            )
        }

        if (pid != null && store != null) {
            AigcPipeline.record(
                store = store,
                projectId = pid,
                toolId = id,
                inputHash = inputHash,
                assetId = asset.id,
                provenance = result.provenance,
                sourceBinding = folded.appliedNodeIds.map { SourceNodeId(it) }.toSet(),
                baseInputs = JsonConfig.default.encodeToJsonElement(Input.serializer(), input).jsonObject,
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
            effectivePrompt = folded.effectivePrompt,
            appliedConsistencyBindingIds = folded.appliedNodeIds,
            negativePrompt = folded.negativePrompt,
            referenceAssetIds = folded.referenceAssetIds,
            loraAdapterIds = folded.loraPins.map { it.adapterId },
            cacheHit = false,
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

    private fun hit(
        entry: io.talevia.core.domain.lockfile.LockfileEntry,
        folded: FoldedPrompt,
        input: Input,
    ): ToolResult<Output> {
        val prov = entry.provenance
        val out = Output(
            assetId = entry.assetId.value,
            width = input.width,
            height = input.height,
            providerId = prov.providerId,
            modelId = prov.modelId,
            modelVersion = prov.modelVersion,
            seed = prov.seed,
            parameters = prov.parameters,
            effectivePrompt = folded.effectivePrompt,
            appliedConsistencyBindingIds = folded.appliedNodeIds,
            negativePrompt = folded.negativePrompt,
            referenceAssetIds = folded.referenceAssetIds,
            loraAdapterIds = folded.loraPins.map { it.adapterId },
            cacheHit = true,
        )
        return ToolResult(
            title = "generate image (cached)",
            outputForLlm = "Reused cached image ${out.assetId} (lockfile hit; seed=${prov.seed}, model=${prov.modelId})",
            data = out,
        )
    }

    private suspend fun resolveAssetPath(assetId: String): String =
        storage.resolve(AssetId(assetId))

    private suspend fun resolveConsistency(input: Input): FoldedPrompt {
        if (input.consistencyBindingIds.isEmpty()) {
            return io.talevia.core.domain.source.consistency
                .foldConsistencyIntoPrompt(input.prompt, emptyList())
        }
        val store = projectStore
            ?: error("consistencyBindingIds supplied but this GenerateImageTool has no ProjectStore wired")
        val pid = input.projectId
            ?: error("consistencyBindingIds require projectId to locate the source graph")
        val project = store.get(ProjectId(pid))
            ?: error("Project $pid not found when resolving consistency bindings")
        return AigcPipeline.foldPrompt(
            project = project,
            basePrompt = input.prompt,
            bindingIds = input.consistencyBindingIds.map { SourceNodeId(it) },
        )
    }
}
