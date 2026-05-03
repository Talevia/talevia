package io.talevia.core.tool.builtin.aigc

import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * Per-kind dispatch bodies for [AigcGenerateTool]. Extracted from the main
 * file (`debt-split-aigc-generate-tool`, cycle 31) to keep
 * `AigcGenerateTool.kt` focused on its public surface — sealed Input,
 * unified Output, JSON Schema wiring, the `execute()` switch — while the
 * 4 ~50-line dispatch bodies live next to one another here so a new
 * AIGC kind = +1 dispatch helper in this file + 1 more `when` arm in
 * `execute()`.
 *
 * **Axis.** This file grows linearly with the number of AIGC kinds
 * (image / video / music / speech today; future kinds: voice clone,
 * style transfer, super-res). A new kind doubles all four parts:
 * Input variant + Output projection + dispatch body + schema branch —
 * but each lives in its semantically-correct file. Adding kind #5
 * adds ~80 LOC to **this** file, ~30 to AigcGenerateTool.kt's class
 * body, and ~50 to the schema file. The split makes that linear cost
 * predictable instead of pushing AigcGenerateTool.kt past 800 LOC and
 * triggering R.5 #4 P0 split.
 *
 * Kept `internal` so callers outside this package go through
 * [AigcGenerateTool.execute] — these are private impl helpers.
 */

/**
 * Common loop body for every kind. Calls [perVariant] for each
 * `variantIndex in 0 until variantCount` with the per-variant
 * [ToolContext] (set via [ToolContext.forVariant]). Returns a list of
 * the underlying tool's [ToolResult]s in variant order; the first
 * element corresponds to variant 0 and feeds the top-level Output
 * fields below.
 */
internal suspend inline fun <O> runVariants(
    variantCount: Int,
    ctx: ToolContext,
    perVariant: (variantIndex: Int, ctx: ToolContext) -> ToolResult<O>,
): List<ToolResult<O>> {
    val results = mutableListOf<ToolResult<O>>()
    for (i in 0 until variantCount) {
        results.add(perVariant(i, ctx.forVariant(i)))
    }
    return results
}

internal suspend fun dispatchImage(
    image: ImageAigcGenerator?,
    input: AigcGenerateTool.Input.Image,
    ctx: ToolContext,
): ToolResult<AigcGenerateTool.Output> {
    val tool = image ?: error("aigc_generate(kind=image) — image generation engine not configured")
    val inner = GenerateImageTool.Input(
        prompt = input.prompt,
        model = input.model,
        width = input.width,
        height = input.height,
        seed = input.seed,
        projectId = input.projectId,
        consistencyBindingIds = input.consistencyBindingIds,
    )
    // `aigc-multi-variant-phase3-openai-native-n` (cycle 33): when the
    // image-gen provider supports a native `n` parameter (currently
    // OpenAI image-gen) and the user requested multiple variants,
    // route through [ImageAigcGenerator.generateBatch] which issues
    // **one** provider call for N images. Other providers (Replicate
    // / future Stability / etc.) fall back to the proven sequential
    // N×1 loop — same lockfile shape, more provider round-trips.
    val results = if (input.variantCount > 1 && tool.engine.supportsNativeBatch) {
        tool.generateBatch(inner, ctx, input.variantCount)
    } else {
        runVariants(input.variantCount, ctx) { _, variantCtx ->
            tool.generate(inner, variantCtx)
        }
    }
    val first = results.first().data
    val variants = results.mapIndexed { i, r ->
        AigcGenerateTool.VariantSummary(
            variantIndex = i,
            assetId = r.data.assetId,
            width = r.data.width,
            height = r.data.height,
        )
    }
    return ToolResult(
        title = results.first().title,
        outputForLlm = composeMultiVariantSummary(results.first().outputForLlm, variants, "image"),
        data = AigcGenerateTool.Output(
            assetId = first.assetId,
            kind = "image",
            providerId = first.providerId,
            modelId = first.modelId,
            modelVersion = first.modelVersion,
            seed = first.seed,
            parameters = first.parameters,
            effectivePrompt = first.effectivePrompt,
            appliedConsistencyBindingIds = first.appliedConsistencyBindingIds,
            cacheHit = first.cacheHit,
            width = first.width,
            height = first.height,
            negativePrompt = first.negativePrompt,
            referenceAssetIds = first.referenceAssetIds,
            loraAdapterIds = first.loraAdapterIds,
            variantCount = input.variantCount,
            variants = variants,
        ),
    )
}

internal suspend fun dispatchVideo(
    video: VideoAigcGenerator?,
    input: AigcGenerateTool.Input.Video,
    ctx: ToolContext,
): ToolResult<AigcGenerateTool.Output> {
    val tool = video ?: error("aigc_generate(kind=video) — video generation engine not configured")
    val inner = GenerateVideoTool.Input(
        prompt = input.prompt,
        model = input.model,
        width = input.width,
        height = input.height,
        durationSeconds = input.durationSeconds,
        seed = input.seed,
        projectId = input.projectId,
        consistencyBindingIds = input.consistencyBindingIds,
    )
    val results = runVariants(input.variantCount, ctx) { _, variantCtx ->
        tool.generate(inner, variantCtx)
    }
    val first = results.first().data
    val variants = results.mapIndexed { i, r ->
        AigcGenerateTool.VariantSummary(
            variantIndex = i,
            assetId = r.data.assetId,
            width = r.data.width,
            height = r.data.height,
            durationSeconds = r.data.durationSeconds,
        )
    }
    return ToolResult(
        title = results.first().title,
        outputForLlm = composeMultiVariantSummary(results.first().outputForLlm, variants, "video"),
        data = AigcGenerateTool.Output(
            assetId = first.assetId,
            kind = "video",
            providerId = first.providerId,
            modelId = first.modelId,
            modelVersion = first.modelVersion,
            seed = first.seed,
            parameters = first.parameters,
            effectivePrompt = first.effectivePrompt,
            appliedConsistencyBindingIds = first.appliedConsistencyBindingIds,
            cacheHit = first.cacheHit,
            width = first.width,
            height = first.height,
            durationSeconds = first.durationSeconds,
            negativePrompt = first.negativePrompt,
            referenceAssetIds = first.referenceAssetIds,
            loraAdapterIds = first.loraAdapterIds,
            variantCount = input.variantCount,
            variants = variants,
        ),
    )
}

internal suspend fun dispatchMusic(
    music: MusicAigcGenerator?,
    input: AigcGenerateTool.Input.Music,
    ctx: ToolContext,
): ToolResult<AigcGenerateTool.Output> {
    val tool = music ?: error("aigc_generate(kind=music) — music generation engine not configured")
    val inner = GenerateMusicTool.Input(
        prompt = input.prompt,
        durationSeconds = input.durationSeconds,
        model = input.model,
        seed = input.seed,
        projectId = input.projectId,
        consistencyBindingIds = input.consistencyBindingIds,
    )
    val results = runVariants(input.variantCount, ctx) { _, variantCtx ->
        tool.generate(inner, variantCtx)
    }
    val first = results.first().data
    val variants = results.mapIndexed { i, r ->
        AigcGenerateTool.VariantSummary(
            variantIndex = i,
            assetId = r.data.assetId,
            durationSeconds = r.data.durationSeconds,
        )
    }
    return ToolResult(
        title = results.first().title,
        outputForLlm = composeMultiVariantSummary(results.first().outputForLlm, variants, "music"),
        data = AigcGenerateTool.Output(
            assetId = first.assetId,
            kind = "music",
            providerId = first.providerId,
            modelId = first.modelId,
            modelVersion = first.modelVersion,
            seed = first.seed,
            parameters = first.parameters,
            effectivePrompt = first.effectivePrompt,
            appliedConsistencyBindingIds = first.appliedConsistencyBindingIds,
            cacheHit = first.cacheHit,
            durationSeconds = first.durationSeconds,
            variantCount = input.variantCount,
            variants = variants,
        ),
    )
}

internal suspend fun dispatchSpeech(
    speech: SpeechAigcGenerator?,
    input: AigcGenerateTool.Input.Speech,
    ctx: ToolContext,
): ToolResult<AigcGenerateTool.Output> {
    val tool = speech ?: error("aigc_generate(kind=speech) — TTS engine not configured")
    val inner = SynthesizeSpeechTool.Input(
        text = input.prompt,
        voice = input.voice,
        model = input.model,
        format = input.format,
        speed = input.speed,
        projectId = input.projectId,
        consistencyBindingIds = input.consistencyBindingIds,
        language = input.language,
    )
    val results = runVariants(input.variantCount, ctx) { _, variantCtx ->
        tool.generate(inner, variantCtx)
    }
    val first = results.first().data
    val variants = results.mapIndexed { i, r ->
        AigcGenerateTool.VariantSummary(
            variantIndex = i,
            assetId = r.data.assetId,
        )
    }
    return ToolResult(
        title = results.first().title,
        outputForLlm = composeMultiVariantSummary(results.first().outputForLlm, variants, "speech"),
        data = AigcGenerateTool.Output(
            assetId = first.assetId,
            kind = "speech",
            providerId = first.providerId,
            modelId = first.modelId,
            modelVersion = first.modelVersion,
            seed = input.seed ?: 0L, // TTS Output has no seed; reflect input or default
            parameters = first.parameters,
            effectivePrompt = input.prompt, // TTS Output's text is in `voice` description; prompt = text
            appliedConsistencyBindingIds = first.appliedConsistencyBindingIds,
            cacheHit = first.cacheHit,
            durationSeconds = null,
            voice = first.voice,
            format = first.format,
            language = first.language,
            variantCount = input.variantCount,
            variants = variants,
        ),
    )
}

/**
 * For multi-variant dispatches, append a one-line summary of all
 * generated variants after the underlying tool's first-variant
 * outputForLlm. Single-variant dispatches return the underlying
 * line verbatim. Keeps the LLM-facing token cost ≈ 80 chars per
 * extra variant — well below the 500-token §3a threshold.
 */
internal fun composeMultiVariantSummary(
    firstLine: String,
    variants: List<AigcGenerateTool.VariantSummary>,
    kind: String,
): String {
    if (variants.size <= 1) return firstLine
    val rest = variants.drop(1).joinToString(", ") { "v${it.variantIndex}=${it.assetId}" }
    return "$firstLine + ${variants.size - 1} more $kind variants: $rest"
}
