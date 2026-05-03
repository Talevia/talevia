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
import io.talevia.core.platform.BundleBlobWriter
import io.talevia.core.platform.BundleMediaPathResolver
import io.talevia.core.platform.VideoGenEngine
import io.talevia.core.platform.VideoGenRequest
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Generate a short video via a [VideoGenEngine], persist the bytes into the
 * project bundle via a [BundleBlobWriter], append the resulting [MediaAsset]
 * to `Project.assets`, and surface an [AssetId] that `add_clip` can drop onto
 * a video track. Closes the AIGC video lane from VISION §2 ("文生视频")
 * alongside [AigcImageGenerator] (text-to-image) and [AigcSpeechGenerator]
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
 * `project_query(select=stale_clips)` / `clip_action(action="replace")`.
 *
 * Permission: `"aigc.generate"` — same bucket as image / TTS gen because all
 * three incur external cost + a cache-coherent seed.
 */
class AigcVideoGenerator(
    private val engine: VideoGenEngine,
    private val bundleBlobWriter: BundleBlobWriter,
    private val projectStore: ProjectStore,
) : VideoAigcGenerator {

    companion object {
        const val ID = "generate_video"
    }

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

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun generate(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        AigcBudgetGuard.enforce(ID, projectStore, pid, ctx)
        val seed = AigcPipeline.ensureSeed(input.seed)
        val folded = resolveConsistency(input, pid)

        val referenceAssetPaths = resolveReferenceAssetPaths(pid, folded.referenceAssetIds)
        val loraKey = folded.loraPins.joinToString(",") { "${it.adapterId}@${it.weight}" }

        val inputHash = AigcPipeline.inputHash(
            listOf(
                "tool" to ID,
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
            variantIndex = ctx.variantIndex,
        )

        if (!ctx.isReplay) {
            val cached = AigcPipeline.findCached(projectStore, pid, inputHash)
            ctx.publishEvent(io.talevia.core.bus.BusEvent.AigcCacheProbe(toolId = ID, hit = cached != null))
            if (cached != null) {
                return hit(cached, folded, input)
            }
        }

        val result = AigcPipeline.withProgress(
            ctx = ctx,
            jobId = "gen-video-${inputHash.take(8)}",
            startMessage = "generating ${input.durationSeconds}s video " +
                "(${input.width}x${input.height}) with ${input.model}",
            toolId = ID,
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
        val costCents = AigcPricing.estimateCents(ID, result.provenance, baseInputs)
        AigcPipeline.record(
            store = projectStore,
            projectId = pid,
            toolId = ID,
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
            variantIndex = ctx.variantIndex,
        )
        ctx.publishEvent(
            BusEvent.AigcCostRecorded(
                sessionId = ctx.sessionId,
                projectId = pid,
                toolId = ID,
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
