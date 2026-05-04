package io.talevia.core.cost

import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Per-modality coverage gaps for [AigcPricing.estimateCents] —
 * `core/src/commonMain/kotlin/io/talevia/core/cost/AigcPricing.kt:42`.
 * Cycle 288 audit: [AigcPricingTest] (11 tests) covers the main
 * happy paths; this file pins the remaining tier / provider /
 * floor cases.
 *
 * Same audit-pattern fallback as cycles 207-287.
 *
 * Coverage gaps closed:
 *
 *   priceImage:
 *     - dall-e-3 square (4¢) + non-square (8¢)
 *     - dall-e-2 flat (2¢)
 *     - non-OpenAI provider → null
 *
 *   priceTts:
 *     - gpt-4o-mini-tts (billed as tts-1)
 *     - non-OpenAI provider → null
 *     - half-up rounding boundary (text length where raw cost
 *       falls < 0.5¢ rounds to 0; ≥ 0.5¢ rounds to 1)
 *     - non-zero floor via coerceAtLeast(0L)
 *
 *   priceVideo:
 *     - sora-turbo @ 30¢/s
 *     - sora-hd @ 50¢/s
 *     - sora-1080p @ 50¢/s
 *     - "seconds" alternative field name path
 *     - non-OpenAI provider → null
 *     - unknown sora-derivative → null
 *
 *   priceMusic:
 *     - non-replicate provider → null
 *     - non-meta/musicgen model → null
 *     - coerceAtLeast(1L) short-clip floor (1s → 2 cents,
 *       0s → 1 cent NOT 0)
 *
 *   priceUpscale:
 *     - non-replicate provider → null
 *     - non-real-esrgan model → null
 *
 * Drift signals:
 *   - **Tier collapse**: drift to charge dall-e-3 the same as
 *     gpt-image-1 silently mis-estimates dall-e-3 generations.
 *   - **Provider gate hole**: drift to skip the
 *     `provenance.providerId != PROVIDER_OPENAI` check returns a
 *     fabricated price for an unknown provider — violates the
 *     three-state null-vs-zero invariant.
 *   - **Floor drift**: drift on `coerceAtLeast(1L)` for music
 *     would let 0-second clips report 0¢ (free), conflating
 *     "explicitly free" with "explicitly priced trivially".
 */
class AigcPricingPerModalityCoverageTest {

    private fun provenance(
        providerId: String = "openai",
        modelId: String = "gpt-image-1",
    ): GenerationProvenance = GenerationProvenance(
        providerId = providerId,
        modelId = modelId,
        modelVersion = null,
        seed = 0,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 0,
    )

    // ── priceImage tiers ────────────────────────────────────

    @Test fun dallE3SquareCharges4Cents() {
        val input = buildJsonObject { put("width", 1024); put("height", 1024) }
        assertEquals(
            4L,
            AigcPricing.estimateCents("generate_image", provenance(modelId = "dall-e-3"), input),
            "dall-e-3 square MUST charge 4¢ (=$0.04 list)",
        )
    }

    @Test fun dallE3NonSquareCharges8Cents() {
        // Marquee tier-collapse pin: dall-e-3 non-square is
        // 8¢ (NOT 6¢ like gpt-image-1) — drift would silently
        // halve the estimate for landscape/portrait dall-e-3.
        val input = buildJsonObject { put("width", 1792); put("height", 1024) }
        assertEquals(
            8L,
            AigcPricing.estimateCents("generate_image", provenance(modelId = "dall-e-3"), input),
            "dall-e-3 non-square MUST charge 8¢ (NOT 6¢ like gpt-image-1)",
        )
    }

    @Test fun dallE2FlatCharges2CentsRegardlessOfShape() {
        // Pin: dall-e-2 has flat $0.02 across all sizes.
        val square = buildJsonObject { put("width", 1024); put("height", 1024) }
        val rect = buildJsonObject { put("width", 512); put("height", 1024) }
        assertEquals(
            2L,
            AigcPricing.estimateCents("generate_image", provenance(modelId = "dall-e-2"), square),
            "dall-e-2 square MUST charge 2¢",
        )
        assertEquals(
            2L,
            AigcPricing.estimateCents("generate_image", provenance(modelId = "dall-e-2"), rect),
            "dall-e-2 rect MUST also charge 2¢ (flat across shapes)",
        )
    }

    @Test fun nonOpenaiProviderReturnsNullForImage() {
        // Marquee provider-gate pin: non-openai provider
        // MUST short-circuit before model lookup. Drift here
        // would let an unknown provider receive a fabricated
        // OpenAI rate.
        val input = buildJsonObject { put("width", 1024); put("height", 1024) }
        assertNull(
            AigcPricing.estimateCents(
                "generate_image",
                provenance(providerId = "anthropic", modelId = "claude-vision-image"),
                input,
            ),
            "non-openai provider MUST return null (three-state invariant)",
        )
    }

    // ── priceTts tiers + boundaries ─────────────────────────

    @Test fun gpt4oMiniTtsBilledAsTts1() {
        // Pin: gpt-4o-mini-tts charges the same per-char rate
        // as tts-1 (per source line 79). Drift to a different
        // rate would silently mis-estimate every gpt-4o-mini-tts
        // call.
        val text = "a".repeat(10_000)
        val input = buildJsonObject { put("text", text) }
        assertEquals(
            15L,
            AigcPricing.estimateCents(
                "synthesize_speech",
                provenance(modelId = "gpt-4o-mini-tts"),
                input,
            ),
            "gpt-4o-mini-tts MUST be billed at tts-1 rate (15¢ for 10K chars)",
        )
    }

    @Test fun nonOpenaiProviderReturnsNullForTts() {
        val input = buildJsonObject { put("text", "hi") }
        assertNull(
            AigcPricing.estimateCents(
                "synthesize_speech",
                provenance(providerId = "elevenlabs", modelId = "tts-1"),
                input,
            ),
            "non-openai provider MUST return null for TTS",
        )
    }

    @Test fun ttsHalfUpRoundingBoundary() {
        // Pin: per source `(chars * centsPerChar + 0.5).toLong()`
        // round half-up. tts-1 rate = 0.0015 cents/char.
        // 333 chars × 0.0015 = 0.4995 → +0.5 = 0.9995 → toLong=0.
        // 334 chars × 0.0015 = 0.501  → +0.5 = 1.001  → toLong=1.
        val justUnder = buildJsonObject { put("text", "a".repeat(333)) }
        val justOver = buildJsonObject { put("text", "a".repeat(334)) }
        assertEquals(
            0L,
            AigcPricing.estimateCents(
                "synthesize_speech",
                provenance(modelId = "tts-1"),
                justUnder,
            ),
            "333 chars × 0.0015 + 0.5 = 0.9995 → toLong=0 (just-under boundary)",
        )
        assertEquals(
            1L,
            AigcPricing.estimateCents(
                "synthesize_speech",
                provenance(modelId = "tts-1"),
                justOver,
            ),
            "334 chars × 0.0015 + 0.5 = 1.001 → toLong=1 (just-over boundary)",
        )
    }

    @Test fun ttsCoerceAtLeastZeroFloorOnEmptyText() {
        // Pin: per source `coerceAtLeast(0L)`. Empty-string
        // text is technically 0 chars × rate = 0¢. Floor
        // pin: result MUST be ≥ 0L, never negative.
        val input = buildJsonObject { put("text", "") }
        val cents = AigcPricing.estimateCents(
            "synthesize_speech",
            provenance(modelId = "tts-1"),
            input,
        )
        assertNotNull(cents, "empty-text TTS still has a defined price (0¢)")
        assertEquals(0L, cents, "empty-text TTS MUST be 0¢ (never negative)")
    }

    @Test fun unknownTtsModelReturnsNull() {
        // Pin: unknown TTS model returns null — drift to
        // fall-through to a default rate would fabricate
        // prices.
        val input = buildJsonObject { put("text", "hello") }
        assertNull(
            AigcPricing.estimateCents(
                "synthesize_speech",
                provenance(modelId = "tts-future-edition"),
                input,
            ),
            "unknown TTS model MUST return null",
        )
    }

    // ── priceVideo tiers ────────────────────────────────────

    @Test fun soraTurboSameRateAsSora() {
        // Pin: sora and sora-turbo share the 30¢/s rate.
        val input = buildJsonObject { put("durationSeconds", 5) }
        assertEquals(
            150L,
            AigcPricing.estimateCents(
                "generate_video",
                provenance(modelId = "sora-turbo"),
                input,
            ),
            "sora-turbo @ 30¢/s × 5s = 150¢",
        )
    }

    @Test fun soraHdHigherTier50CentsPerSecond() {
        // Marquee tier pin: sora-hd is 50¢/s (NOT 30¢/s like
        // sora). Drift would silently mis-estimate every HD
        // render.
        val input = buildJsonObject { put("durationSeconds", 4) }
        assertEquals(
            200L,
            AigcPricing.estimateCents(
                "generate_video",
                provenance(modelId = "sora-hd"),
                input,
            ),
            "sora-hd @ 50¢/s × 4s = 200¢ (NOT 120¢ like sora)",
        )
    }

    @Test fun sora1080pAlsoHigherTier() {
        // Sister tier pin: sora-1080p shares sora-hd's 50¢/s.
        val input = buildJsonObject { put("durationSeconds", 6) }
        assertEquals(
            300L,
            AigcPricing.estimateCents(
                "generate_video",
                provenance(modelId = "sora-1080p"),
                input,
            ),
            "sora-1080p @ 50¢/s × 6s = 300¢",
        )
    }

    @Test fun videoSecondsFieldNameAlsoAccepted() {
        // Pin: per source `intField("durationSeconds") ?:
        // intField("seconds") ?: return null`. Drift to drop
        // the "seconds" alias would silently make older
        // input shapes return null.
        val input = buildJsonObject { put("seconds", 5) }
        assertEquals(
            150L,
            AigcPricing.estimateCents(
                "generate_video",
                provenance(modelId = "sora"),
                input,
            ),
            "video MUST accept 'seconds' as alternative to 'durationSeconds'",
        )
    }

    @Test fun nonOpenaiProviderReturnsNullForVideo() {
        val input = buildJsonObject { put("durationSeconds", 5) }
        assertNull(
            AigcPricing.estimateCents(
                "generate_video",
                provenance(providerId = "runway", modelId = "sora"),
                input,
            ),
            "non-openai provider MUST return null for video (model name match alone is not enough)",
        )
    }

    @Test fun unknownVideoModelReturnsNull() {
        val input = buildJsonObject { put("durationSeconds", 5) }
        assertNull(
            AigcPricing.estimateCents(
                "generate_video",
                provenance(modelId = "veo-3"),
                input,
            ),
            "unknown OpenAI video model MUST return null (only sora-* tiers priced)",
        )
    }

    // ── priceMusic ──────────────────────────────────────────

    @Test fun nonReplicateProviderReturnsNullForMusic() {
        val input = buildJsonObject { put("durationSeconds", 15) }
        assertNull(
            AigcPricing.estimateCents(
                "generate_music",
                provenance(providerId = "openai", modelId = "meta/musicgen"),
                input,
            ),
            "non-replicate provider MUST return null for music",
        )
    }

    @Test fun nonMetaMusicgenModelReturnsNull() {
        // Pin: per source `if (!provenance.modelId.startsWith
        // ("meta/musicgen")) return null`. Drift to a
        // looser prefix (e.g. "musicgen") would let other
        // model authors' work get billed at meta/musicgen
        // rates.
        val input = buildJsonObject { put("durationSeconds", 15) }
        assertNull(
            AigcPricing.estimateCents(
                "generate_music",
                provenance(providerId = "replicate", modelId = "stability/audio-stable-1"),
                input,
            ),
            "non-meta/musicgen model on replicate MUST return null",
        )
        assertNull(
            AigcPricing.estimateCents(
                "generate_music",
                provenance(providerId = "replicate", modelId = "musicgen-fork-by-someone-else"),
                input,
            ),
            "wrong-prefix model MUST return null (drift to 'musicgen' prefix would surface here)",
        )
    }

    @Test fun musicCoerceAtLeastOneFloorOnTrivialDuration() {
        // Marquee floor pin: per source
        // `(durationSeconds * 2L).coerceAtLeast(1L)`.
        // 0 seconds × 2 = 0 → coerceAtLeast(1L) = 1.
        // Drift to drop the floor would conflate "free" with
        // "trivially priced", violating the three-state
        // invariant.
        val zeroDuration = buildJsonObject { put("durationSeconds", 0) }
        assertEquals(
            1L,
            AigcPricing.estimateCents(
                "generate_music",
                provenance(providerId = "replicate", modelId = "meta/musicgen:abc"),
                zeroDuration,
            ),
            "0s music MUST floor to 1¢ (never 0¢; coerceAtLeast(1L))",
        )

        // 1s × 2¢/s = 2¢ — already above floor.
        val oneSec = buildJsonObject { put("durationSeconds", 1) }
        assertEquals(
            2L,
            AigcPricing.estimateCents(
                "generate_music",
                provenance(providerId = "replicate", modelId = "meta/musicgen:abc"),
                oneSec,
            ),
            "1s music MUST charge 2¢ (above floor; floor only kicks in at 0s)",
        )
    }

    // ── priceUpscale ────────────────────────────────────────

    @Test fun nonReplicateProviderReturnsNullForUpscale() {
        assertNull(
            AigcPricing.estimateCents(
                "upscale_asset",
                provenance(providerId = "openai", modelId = "nightmareai/real-esrgan"),
                JsonObject(emptyMap()),
            ),
            "non-replicate provider MUST return null for upscale",
        )
    }

    @Test fun nonRealEsrganModelOnReplicateReturnsNull() {
        // Pin: per source `if
        // (!provenance.modelId.startsWith("nightmareai/
        // real-esrgan")) return null`. Drift to a looser
        // prefix would price unrelated upscalers at the
        // real-esrgan rate.
        assertNull(
            AigcPricing.estimateCents(
                "upscale_asset",
                provenance(providerId = "replicate", modelId = "tencent/gfpgan"),
                JsonObject(emptyMap()),
            ),
            "non-real-esrgan upscaler MUST return null",
        )
        assertNull(
            AigcPricing.estimateCents(
                "upscale_asset",
                provenance(providerId = "replicate", modelId = "real-esrgan-by-someone-else"),
                JsonObject(emptyMap()),
            ),
            "wrong-prefix model MUST return null (drift to 'real-esrgan' anywhere prefix would surface here)",
        )
    }
}
