package io.talevia.core.cost

import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Best-effort USD cost estimator for AIGC calls. VISION §5.2 ops lane — users
 * want to know "how much did this vlog cost me" and "how much has this project
 * burned this month" without us running a billing pipeline.
 *
 * **Deliberately imprecise.** Provider pricing drifts (OpenAI repriced gpt-image-1
 * at least twice in 2025, Replicate bills by GPU-second which varies by host
 * load). This table is a snapshot of published list prices; callers that need
 * invoice-accurate numbers should cross-check with the provider console.
 *
 * **Three-state return.** `estimateCents(...)` returns `null` when no pricing
 * rule matches — callers (lockfile, metrics, query tools) treat null as
 * "unknown cost, don't roll up", distinct from `0L` meaning "explicitly free"
 * (e.g. a cached result we re-served without billing). Never guess a number to
 * fill a null — the three-state distinction is load-bearing for users trying to
 * judge confidence in their budget numbers.
 *
 * **No global pricing config.** Rates live as private constants in this file
 * so a PR touching them is a one-file diff with a clear blast radius. When
 * providers reprice we update here and bump the decision doc pointer.
 */
object AigcPricing {

    /**
     * Compute the cost in cents for a completed AIGC dispatch.
     *
     * @param toolId Registered tool id (e.g. `generate_image`, `synthesize_speech`).
     * @param provenance Engine-returned provenance with providerId + modelId.
     * @param baseInputs The tool's raw input JSON — used to read dimensions,
     *   text length, duration, etc. Matches `LockfileEntry.baseInputs`.
     * @return Cost in USD cents, or `null` if we have no pricing rule for the
     *   specific provider + model + call shape.
     */
    fun estimateCents(
        toolId: String,
        provenance: GenerationProvenance,
        baseInputs: JsonObject = JsonObject(emptyMap()),
    ): Long? = when (toolId) {
        TOOL_GENERATE_IMAGE -> priceImage(provenance, baseInputs)
        TOOL_SYNTHESIZE_SPEECH -> priceTts(provenance, baseInputs)
        TOOL_GENERATE_VIDEO -> priceVideo(provenance, baseInputs)
        TOOL_GENERATE_MUSIC -> priceMusic(provenance, baseInputs)
        TOOL_UPSCALE_ASSET -> priceUpscale(provenance, baseInputs)
        else -> null
    }

    // -----------------------------------------------------------------
    // Per-modality rules. Each returns null for unknown providers/models.

    private fun priceImage(provenance: GenerationProvenance, input: JsonObject): Long? {
        if (provenance.providerId != PROVIDER_OPENAI) return null
        val width = input.intField("width") ?: return null
        val height = input.intField("height") ?: return null
        val square = width == height
        return when (provenance.modelId) {
            "gpt-image-1" -> if (square) 4L else 6L // $0.04 / $0.06
            "dall-e-3" -> if (square) 4L else 8L    // $0.04 / $0.08
            "dall-e-2" -> 2L                        // $0.02 (all sizes)
            else -> null
        }
    }

    private fun priceTts(provenance: GenerationProvenance, input: JsonObject): Long? {
        if (provenance.providerId != PROVIDER_OPENAI) return null
        val text = input.stringField("text") ?: return null
        val chars = text.length
        // Per-character $ rates → cents. Round half-up to nearest cent.
        val centsPerChar = when (provenance.modelId) {
            "tts-1" -> TTS1_CENTS_PER_CHAR
            "tts-1-hd" -> TTS1_HD_CENTS_PER_CHAR
            "gpt-4o-mini-tts" -> TTS1_CENTS_PER_CHAR // billed similarly to tts-1
            else -> return null
        }
        return (chars * centsPerChar + 0.5).toLong().coerceAtLeast(0L)
    }

    private fun priceVideo(provenance: GenerationProvenance, input: JsonObject): Long? {
        if (provenance.providerId != PROVIDER_OPENAI) return null
        // Sora-class models: price by requested duration.
        val durationSeconds = input.intField("durationSeconds")
            ?: input.intField("seconds")
            ?: return null
        val centsPerSecond = when (provenance.modelId) {
            "sora", "sora-turbo" -> 30L    // ~$0.30/s (placeholder tier)
            "sora-hd", "sora-1080p" -> 50L // higher tier
            else -> return null
        }
        return durationSeconds * centsPerSecond
    }

    private fun priceMusic(provenance: GenerationProvenance, input: JsonObject): Long? {
        // Replicate musicgen bills by GPU seconds; published list-price is
        // ~$0.003/s on the T4 / A40 tier. Without the predict_time metric in
        // provenance we approximate via requested duration.
        if (provenance.providerId != PROVIDER_REPLICATE) return null
        if (!provenance.modelId.startsWith("meta/musicgen")) return null
        val durationSeconds = input.intField("durationSeconds")
            ?: input.intField("seconds")
            ?: return null
        // 8× real-time GPU burn × $0.003/s ≈ $0.024/s → 2 cents/s (rounded down
        // to an integer so we don't over-report on short clips).
        return (durationSeconds * 2L).coerceAtLeast(1L)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun priceUpscale(provenance: GenerationProvenance, input: JsonObject): Long? {
        // Replicate real-esrgan tier — flat estimate per call. Exposure to the
        // user is "upscales cost roughly a nickel each"; the real invoice
        // depends on GPU minutes. Null rather than guess for other providers.
        if (provenance.providerId != PROVIDER_REPLICATE) return null
        if (!provenance.modelId.startsWith("nightmareai/real-esrgan")) return null
        return 5L // ~$0.05
    }

    // -----------------------------------------------------------------
    // Extractors tolerant of missing / wrongly-typed fields.

    private fun JsonObject.intField(name: String): Int? {
        val v = this[name] ?: return null
        if (v !is JsonPrimitive) return null
        return v.intOrNull ?: v.longOrNull?.toInt()
    }

    private fun JsonObject.stringField(name: String): String? {
        val v = this[name] ?: return null
        if (v !is JsonPrimitive || !v.isString) return null
        return v.content
    }

    // -----------------------------------------------------------------
    // Constants exposed to callers that need to key on the same slugs.

    const val TOOL_GENERATE_IMAGE = "generate_image"
    const val TOOL_SYNTHESIZE_SPEECH = "synthesize_speech"
    const val TOOL_GENERATE_VIDEO = "generate_video"
    const val TOOL_GENERATE_MUSIC = "generate_music"
    const val TOOL_UPSCALE_ASSET = "upscale_asset"

    private const val PROVIDER_OPENAI = "openai"
    private const val PROVIDER_REPLICATE = "replicate"

    // tts-1 lists at $15 / 1M chars → 0.0015 cents/char.
    private const val TTS1_CENTS_PER_CHAR = 0.0015
    private const val TTS1_HD_CENTS_PER_CHAR = 0.0030
}
