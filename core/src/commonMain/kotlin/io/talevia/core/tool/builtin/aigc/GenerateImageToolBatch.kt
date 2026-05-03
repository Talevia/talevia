package io.talevia.core.tool.builtin.aigc

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.bus.BusEvent
import io.talevia.core.cost.AigcPricing
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.Resolution
import io.talevia.core.platform.GeneratedImage
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.ImageGenRequest
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Native-batch path for [aigc-multi-variant-phase3-openai-native-n] (cycle 33).
 * Extracted from `GenerateImageTool.kt` (`debt-split-generate-image-tool`,
 * cycle 37) to keep the main file focused on the public Tool surface +
 * single-variant `execute()` orchestrator. The body is unchanged from
 * cycle 33's original; only the per-call `cachedProvenance` member-variable
 * stash has been turned into a local `provenance` val flowing through the
 * loop (the original was a `private var` member that should never have been
 * stateful on a stateless tool — extraction was the natural moment to fix
 * that latent bug).
 *
 * **Axis**: this file grows linearly with batch-related complexity (cache
 * pre-warm shapes, multi-variant cost accounting, partial-failure recovery).
 * The single-variant `execute()` path doesn't trickle into this file —
 * only batch concerns. New batch features = new branches here, not in
 * `GenerateImageTool.kt`.
 *
 * Used by [io.talevia.core.tool.builtin.aigc.dispatchImage] when
 * [GenerateImageTool.engine].`supportsNativeBatch` is true (currently
 * OpenAI only) and `variantCount > 1`. Engines without native batch fall
 * back to the sequential loop in [AigcGenerateToolDispatchers] — same
 * lockfile shape, more provider round-trips.
 *
 * Cache semantics: on entry, each variant's inputHash is checked against
 * the lockfile. Hits return the cached entry's Output for that variant;
 * only **misses** are forwarded to the single provider call (with
 * `n=missCount`). This handles a re-dispatch partially served from cache
 * cleanly. The provider's response is a `List<GeneratedImage>` of size
 * `missCount`; each is matched to the next missing variantIndex in order.
 * The cache-hit Output is interleaved at the right index in the returned
 * list.
 */
@OptIn(ExperimentalUuidApi::class)
internal suspend fun GenerateImageTool.executeBatch(
    input: GenerateImageTool.Input,
    ctx: ToolContext,
    variantCount: Int,
): List<ToolResult<GenerateImageTool.Output>> {
    require(variantCount >= 1) { "executeBatch variantCount must be ≥ 1" }
    val pid = ctx.resolveProjectId(input.projectId)
    AigcBudgetGuard.enforce(GenerateImageTool.ID, projectStore, pid, ctx)
    val seed = AigcPipeline.ensureSeed(input.seed)
    val folded = resolveConsistency(input, pid)
    val referenceAssetPaths = resolveReferenceAssetPaths(pid, folded.referenceAssetIds)
    val loraKey = folded.loraPins.joinToString(",") { "${it.adapterId}@${it.weight}" }

    // Per-variant inputHash list — same canonical fields across
    // variants, only `variantIndex` differs.
    val perVariantHashes: List<String> = (0 until variantCount).map { i ->
        AigcPipeline.inputHash(
            listOf(
                "tool" to GenerateImageTool.ID,
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
            variantIndex = i,
        )
    }

    // Cache probe per variant. Replay (`ctx.isReplay = true`) skips
    // every cache lookup so the provider gets called for every
    // variant — same semantic the single-variant `execute()` path
    // has when isReplay flips on.
    val cachedByIndex: Map<Int, io.talevia.core.domain.lockfile.LockfileEntry> = if (ctx.isReplay) {
        emptyMap()
    } else {
        buildMap {
            perVariantHashes.forEachIndexed { i, hash ->
                AigcPipeline.findCached(projectStore, pid, hash)?.let { put(i, it) }
            }
        }
    }
    // Surface cache-probe events for each variant before potentially
    // calling the provider so observers see the per-variant
    // hit/miss split.
    cachedByIndex.keys.forEach {
        ctx.publishEvent(io.talevia.core.bus.BusEvent.AigcCacheProbe(toolId = GenerateImageTool.ID, hit = true))
    }
    val missingIndices = (0 until variantCount).filter { it !in cachedByIndex }
    missingIndices.forEach {
        ctx.publishEvent(io.talevia.core.bus.BusEvent.AigcCacheProbe(toolId = GenerateImageTool.ID, hit = false))
    }

    // Provider call — only when there's at least one cache miss.
    val (freshImages, batchProvenance) = if (missingIndices.isEmpty()) {
        emptyList<GeneratedImage>() to null
    } else {
        val result = AigcPipeline.withProgress(
            ctx = ctx,
            jobId = "gen-image-batch-${perVariantHashes.first().take(8)}-n${missingIndices.size}",
            startMessage = "generating ${input.width}x${input.height} image batch ×${missingIndices.size} with ${input.model}",
            toolId = GenerateImageTool.ID,
            providerId = engine.providerId,
        ) {
            engine.generate(
                ImageGenRequest(
                    prompt = folded.effectivePrompt,
                    modelId = input.model,
                    width = input.width,
                    height = input.height,
                    seed = seed,
                    n = missingIndices.size,
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
        check(result.images.size == missingIndices.size) {
            "${engine.providerId} returned ${result.images.size} images for n=${missingIndices.size} batch — " +
                "engine claimed supportsNativeBatch but didn't honour the contract"
        }
        result.images to result.provenance
    }

    val baseInputs = JsonConfig.default.encodeToJsonElement(GenerateImageTool.Input.serializer(), input).jsonObject
    val results = MutableList<ToolResult<GenerateImageTool.Output>?>(variantCount) { null }

    // Pair fresh images with their corresponding miss indices in order.
    var imageCursor = 0
    for (i in 0 until variantCount) {
        val cached = cachedByIndex[i]
        if (cached != null) {
            results[i] = hit(cached, folded, input)
            continue
        }
        val image = freshImages[imageCursor++]
        val newAssetId = AssetId(Uuid.random().toString())
        val bundleSource = bundleBlobWriter.writeBlob(pid, newAssetId, image.pngBytes, "png")
        val newAsset = MediaAsset(
            id = newAssetId,
            source = bundleSource,
            metadata = MediaMetadata(
                duration = Duration.ZERO,
                resolution = Resolution(image.width, image.height),
                frameRate = null,
            ),
        )
        val variantHash = perVariantHashes[i]
        val provenance: GenerationProvenance = batchProvenance
            ?: error("provenance must be set when there's at least one fresh image")
        val costCents = AigcPricing.estimateCents(GenerateImageTool.ID, provenance, baseInputs)
        AigcPipeline.record(
            store = projectStore,
            projectId = pid,
            toolId = GenerateImageTool.ID,
            inputHash = variantHash,
            assetId = newAssetId,
            provenance = provenance,
            sourceBinding = folded.appliedNodeIds.map { SourceNodeId(it) }.toSet(),
            baseInputs = baseInputs,
            costCents = costCents,
            sessionId = ctx.sessionId,
            resolvedPrompt = folded.effectivePrompt,
            originatingMessageId = ctx.messageId,
            newAsset = newAsset,
            variantIndex = i,
        )
        ctx.publishEvent(
            BusEvent.AigcCostRecorded(
                sessionId = ctx.sessionId,
                projectId = pid,
                toolId = GenerateImageTool.ID,
                assetId = newAssetId.value,
                costCents = costCents,
            ),
        )
        val out = GenerateImageTool.Output(
            assetId = newAssetId.value,
            width = image.width,
            height = image.height,
            providerId = provenance.providerId,
            modelId = provenance.modelId,
            modelVersion = provenance.modelVersion,
            seed = provenance.seed,
            parameters = provenance.parameters,
            effectivePrompt = folded.effectivePrompt,
            appliedConsistencyBindingIds = folded.appliedNodeIds,
            negativePrompt = folded.negativePrompt,
            referenceAssetIds = folded.referenceAssetIds,
            loraAdapterIds = folded.loraPins.map { it.adapterId },
            cacheHit = false,
        )
        val bindingTail = if (folded.appliedNodeIds.isEmpty()) ""
        else " [bindings: ${folded.appliedNodeIds.joinToString(", ")}]"
        results[i] = ToolResult(
            title = "generate image (batch v$i)",
            outputForLlm = "Generated ${image.width}x${image.height} image (asset ${out.assetId}, variant $i) " +
                "via ${provenance.providerId}/${provenance.modelId} seed=${provenance.seed}$bindingTail",
            data = out,
        )
    }
    @Suppress("UNCHECKED_CAST")
    return results.toList() as List<ToolResult<GenerateImageTool.Output>>
}
