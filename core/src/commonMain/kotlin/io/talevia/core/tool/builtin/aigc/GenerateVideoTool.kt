package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.bus.BusEvent
import io.talevia.core.cost.AigcPricing
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.source.consistency.FoldedPrompt
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.BundleMediaPathResolver
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VideoGenRequest
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
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
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Generate a short video via a [VideoGenEngine], persist the bytes into the
 * project bundle via a [BundleBlobWriter], append the resulting [MediaAsset]
 * to `Project.assets`, and surface an [AssetId] that `add_clip` can drop onto
 * a video track. Closes the AIGC video lane from VISION §2 ("文生视频")
 * alongside [GenerateImageTool] (text-to-image) and [SynthesizeSpeechTool]
 * (text-to-speech).
 *
 * Bytes land at `<bundleRoot>/media/<assetId>.mp4` so the generated clip
 * travels with the project bundle.
 *
 * Seed + provenance handling (VISION §3.1): delegates to [AigcPipeline] so this
 * tool and every future AIGC tool share one implementation of "mint a seed if
 * missing, record the full generation parameters."
 *
 * Lockfile cache (VISION §3.1 "产物可 pin"): before calling the engine the tool
 * hashes `(tool id, model, seed, dimensions, duration, effective prompt,
 * bindings, negative, referenceAssetIds, loraPins)` and looks the hash up in
 * [io.talevia.core.domain.Project.lockfile]. On a hit it returns the cached
 * asset without re-billing the provider. Duration is hashed because a 4-second
 * and an 8-second render at otherwise identical inputs are semantically
 * distinct outputs.
 *
 * Consistency bindings (VISION §3.3): same folding semantics as image gen — a
 * bound character_ref's visual description + optional reference images +
 * optional LoRA pin flow into the request. The clip's `sourceBinding` is
 * populated via the lockfile so later edits to bound nodes cascade through
 * `find_stale_clips` / `clip_action(action="replace")`.
 *
 * Permission: `"aigc.generate"` — same bucket as image / TTS gen because all
 * three incur external cost + a cache-coherent seed.
 */
class GenerateVideoTool(
    private val engine: VideoGenEngine,
    private val bundleBlobWriter: BundleBlobWriter,
    private val projectStore: ProjectStore,
) : Tool<GenerateVideoTool.Input, GenerateVideoTool.Output> {

    @Serializable
    data class Input(
        val prompt: String,
        val model: String = "sora-2",
        val width: Int = 1280,
        val height: Int = 720,
        /**
         * Target duration in seconds. Provider-side constraints apply; see
         * engine impls. Default 5s lands inside Sora 2's supported range.
         */
        val durationSeconds: Double = 5.0,
        val seed: Long? = null,
        val projectId: String? = null,
        val consistencyBindingIds: List<String>? = null,
    )

    @Serializable
    data class Output(
        val assetId: String,
        val width: Int,
        val height: Int,
        val durationSeconds: Double,
        val providerId: String,
        val modelId: String,
        val modelVersion: String?,
        val seed: Long,
        val parameters: JsonObject,
        val effectivePrompt: String,
        val appliedConsistencyBindingIds: List<String>,
        /** Merged negative prompt from bound style_bibles (null when no negatives were folded). */
        val negativePrompt: String? = null,
        /** Asset ids of reference clips/images passed to the engine (empty when none supplied any). */
        val referenceAssetIds: List<String> = emptyList(),
        /** LoRA adapter ids that were pinned via bound character_refs (empty when none supplied one). */
        val loraAdapterIds: List<String> = emptyList(),
        /** True when this asset came from [io.talevia.core.domain.Project.lockfile] rather than a fresh engine call. */
        val cacheHit: Boolean = false,
    )

    override val id: String = "generate_video"
    override val helpText: String =
        "Generate a short video from a text prompt via an AIGC provider and import it as a project asset. " +
            "Bytes land in the project bundle's media/ directory so the asset travels with the project. " +
            "Records seed + model in the project lockfile so a second call with identical inputs is a cache hit. " +
            "Pass consistencyBindingIds to reuse character / style / brand source nodes across shots. " +
            "Drop the returned assetId onto a video track via add_clip."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("aigc.generate")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("prompt") {
                put("type", "string")
                put("description", "Text description of the video to generate.")
            }
            putJsonObject("model") {
                put("type", "string")
                put("description", "Provider-scoped model id (default: sora-2).")
            }
            putJsonObject("width") {
                put("type", "integer")
                put("description", "Output video width in pixels (default: 1280).")
            }
            putJsonObject("height") {
                put("type", "integer")
                put("description", "Output video height in pixels (default: 720).")
            }
            putJsonObject("durationSeconds") {
                put("type", "number")
                put("description", "Target duration in seconds. Default 5. Provider may clamp/round to a supported value (Sora 2 accepts 4, 8, 12).")
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
                put(
                    "description",
                    "Source node ids (kind core.consistency.*) to fold into the prompt. " +
                        "null (default) = auto-fold all project consistency nodes; [] = explicitly no binding; " +
                        "non-empty = fold only the listed nodes.",
                )
                putJsonObject("items") { put("type", "string") }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("prompt"))))
        put("additionalProperties", false)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        AigcBudgetGuard.enforce(id, projectStore, pid, ctx)
        val seed = AigcPipeline.ensureSeed(input.seed)
        val folded = resolveConsistency(input, pid)

        val referenceAssetPaths = resolveReferenceAssetPaths(pid, folded.referenceAssetIds)
        val loraKey = folded.loraPins.joinToString(",") { "${it.adapterId}@${it.weight}" }

        val inputHash = AigcPipeline.inputHash(
            listOf(
                "tool" to id,
                "model" to input.model,
                "w" to input.width.toString(),
                "h" to input.height.toString(),
                "dur" to input.durationSeconds.toString(),
                "seed" to seed.toString(),
                "prompt" to folded.effectivePrompt,
                "bindings" to folded.appliedNodeIds.joinToString(","),
                "neg" to (folded.negativePrompt ?: ""),
                "refs" to folded.referenceAssetIds.joinToString(","),
                "lora" to loraKey,
            ),
        )

        if (!ctx.isReplay) {
            val cached = AigcPipeline.findCached(projectStore, pid, inputHash)
            ctx.publishEvent(io.talevia.core.bus.BusEvent.AigcCacheProbe(toolId = id, hit = cached != null))
            if (cached != null) {
                return hit(cached, folded, input)
            }
        }

        val result = AigcPipeline.withProgress(
            ctx = ctx,
            jobId = "gen-video-${inputHash.take(8)}",
            startMessage = "generating ${input.durationSeconds}s video " +
                "(${input.width}x${input.height}) with ${input.model}",
            toolId = id,
            providerId = engine.providerId,
        ) {
            engine.generate(
                VideoGenRequest(
                    prompt = folded.effectivePrompt,
                    modelId = input.model,
                    width = input.width,
                    height = input.height,
                    durationSeconds = input.durationSeconds,
                    seed = seed,
                    negativePrompt = folded.negativePrompt,
                    referenceAssetPaths = referenceAssetPaths,
                    loraPins = folded.loraPins,
                ),
                onWarmup = { phase ->
                    ctx.publishEvent(
                        BusEvent.ProviderWarmup(
                            sessionId = ctx.sessionId,
                            providerId = engine.providerId,
                            phase = phase,
                            epochMs = Clock.System.now().toEpochMilliseconds(),
                        ),
                    )
                },
            )
        }
        val video = result.videos.firstOrNull()
            ?: error("${engine.providerId} video-gen returned zero videos")

        val newAssetId = AssetId(Uuid.random().toString())
        val bundleSource = bundleBlobWriter.writeBlob(pid, newAssetId, video.mp4Bytes, "mp4")
        val newAsset = MediaAsset(
            id = newAssetId,
            source = bundleSource,
            metadata = MediaMetadata(
                duration = video.durationSeconds.seconds,
                resolution = Resolution(video.width, video.height),
                frameRate = null,
            ),
        )

        val baseInputs = JsonConfig.default.encodeToJsonElement(Input.serializer(), input).jsonObject
        val costCents = AigcPricing.estimateCents(id, result.provenance, baseInputs)
        AigcPipeline.record(
            store = projectStore,
            projectId = pid,
            toolId = id,
            inputHash = inputHash,
            assetId = newAssetId,
            provenance = result.provenance,
            sourceBinding = folded.appliedNodeIds.map { SourceNodeId(it) }.toSet(),
            baseInputs = baseInputs,
            costCents = costCents,
            sessionId = ctx.sessionId,
            resolvedPrompt = folded.effectivePrompt,
            originatingMessageId = ctx.messageId,
            newAsset = newAsset,
        )
        ctx.publishEvent(
            BusEvent.AigcCostRecorded(
                sessionId = ctx.sessionId,
                projectId = pid,
                toolId = id,
                assetId = newAssetId.value,
                costCents = costCents,
            ),
        )

        val prov = result.provenance
        val out = Output(
            assetId = newAssetId.value,
            width = video.width,
            height = video.height,
            durationSeconds = video.durationSeconds,
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
            title = "generate video",
            outputForLlm = "Generated ${video.width}x${video.height} ${video.durationSeconds}s video " +
                "(asset ${out.assetId}) via ${prov.providerId}/${prov.modelId} seed=${prov.seed}$bindingTail",
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
            durationSeconds = input.durationSeconds,
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
            title = "generate video (cached)",
            outputForLlm = "Reused cached video ${out.assetId} (lockfile hit; seed=${prov.seed}, model=${prov.modelId})",
            data = out,
        )
    }

    private suspend fun resolveReferenceAssetPaths(
        pid: ProjectId,
        assetIds: List<String>,
    ): List<String> {
        if (assetIds.isEmpty()) return emptyList()
        val project = projectStore.get(pid)
            ?: error("project ${pid.value} not found when resolving reference assets")
        val bundleRoot = projectStore.pathOf(pid)
            ?: error(
                "project ${pid.value} has no registered bundle path; reference asset resolution " +
                    "requires a file-backed ProjectStore — open or create the bundle first.",
            )
        val resolver = BundleMediaPathResolver(project, bundleRoot)
        return assetIds.map { resolver.resolve(AssetId(it)) }
    }

    private suspend fun resolveConsistency(input: Input, pid: ProjectId): FoldedPrompt {
        val bindingIds = input.consistencyBindingIds
        if (bindingIds != null && bindingIds.isEmpty()) {
            return io.talevia.core.domain.source.consistency
                .foldConsistencyIntoPrompt(input.prompt, emptyList())
        }
        val project = projectStore.get(pid)
            ?: error("Project ${pid.value} not found when resolving consistency bindings")
        return AigcPipeline.foldPrompt(
            project = project,
            basePrompt = input.prompt,
            bindingIds = bindingIds?.map { SourceNodeId(it) },
        )
    }
}
