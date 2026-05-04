package io.talevia.core.provider.pricing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for the provider id constants in
 * `core/src/commonMain/kotlin/io/talevia/core/provider/pricing/LlmPricing.kt:87-89`.
 * Cycle 280 audit: 0 test refs against [LlmPricing.PROVIDER_ANTHROPIC],
 * [LlmPricing.PROVIDER_OPENAI], or [LlmPricing.PROVIDER_GEMINI].
 *
 * Same audit-pattern fallback as cycles 207-279.
 *
 * The three provider id constants are wire strings the
 * pricing layer consumes:
 *
 *   - [LlmPricing] uses them to register per-model entries
 *     (`Entry(PROVIDER_ANTHROPIC, "claude-opus-4-7", ...)`).
 *   - [io.talevia.core.cost.AigcPricing]'s per-modality rules
 *     compare `provenance.providerId` against the constants
 *     (e.g. `if (provenance.providerId != PROVIDER_OPENAI)
 *     return null` — non-OpenAI providers fall through to the
 *     "no pricing rule matched" sentinel).
 *
 * Drift signals:
 *   - **Capitalisation drift** (e.g. `"Anthropic"`) silently
 *     makes every pricing rule fail to match (provenance is
 *     lowercase per provider convention).
 *   - **Slug drift** (e.g. `"anthropic-claude"`) would
 *     silently route every model to "no pricing rule"
 *     fallback, surfacing only as missing cost numbers.
 *   - **Cross-constant collision** (drift to the same value)
 *     would silently merge price tables for distinct
 *     providers.
 *
 * Pins three correctness contracts:
 *
 *  1. **Exact lowercase wire-string values**:
 *     `"anthropic"`, `"openai"`, `"google"`. Drift surfaces
 *     here.
 *
 *  2. **Cross-constant uniqueness**: the three values are
 *     distinct (drift to merge two would silently combine
 *     price tables).
 *
 *  3. **Lowercase + non-blank invariant**: every constant
 *     is lowercase + non-blank (drift to mixed case /
 *     empty string would silently mismatch the
 *     provenance.providerId convention).
 */
class ProviderConstantsTest {

    @Test fun providerAnthropicIsLowercaseAnthropic() {
        assertEquals(
            "anthropic",
            LlmPricing.PROVIDER_ANTHROPIC,
            "PROVIDER_ANTHROPIC MUST be exactly 'anthropic' (lowercase wire string)",
        )
    }

    @Test fun providerOpenaiIsLowercaseOpenai() {
        assertEquals(
            "openai",
            LlmPricing.PROVIDER_OPENAI,
            "PROVIDER_OPENAI MUST be exactly 'openai' (lowercase, no hyphen)",
        )
    }

    @Test fun providerGeminiIsLowercaseGemini() {
        // Cycle 312 renamed PROVIDER_GEMINI → PROVIDER_GEMINI
        // and changed value "google" → "gemini" to align with
        // GeminiProvider.id ("gemini") + SecretKeys.GEMINI
        // canonical. Pre-cycle-312 pricing lookup silently
        // failed because LlmPricing used "google" while every
        // other layer used "gemini".
        assertEquals(
            "gemini",
            LlmPricing.PROVIDER_GEMINI,
            "PROVIDER_GEMINI MUST be exactly 'gemini' (cycle 312 alignment with GeminiProvider.id)",
        )
    }

    @Test fun threeProviderIdsAreDistinct() {
        // Marquee uniqueness pin: drift to merge two
        // constants (e.g. typo'd PROVIDER_GEMINI = "openai")
        // would silently combine the two providers' price
        // tables. Three distinct values is what the
        // dispatcher relies on.
        val ids = setOf(
            LlmPricing.PROVIDER_ANTHROPIC,
            LlmPricing.PROVIDER_OPENAI,
            LlmPricing.PROVIDER_GEMINI,
        )
        assertEquals(
            3,
            ids.size,
            "the 3 provider id constants MUST be distinct; got: $ids",
        )
    }

    @Test fun allProviderIdsAreLowercaseAndNonBlank() {
        // Pin: every constant matches the lowercase wire-
        // string convention. Drift to mixed-case /
        // whitespace surfaces here.
        for ((name, value) in mapOf(
            "PROVIDER_ANTHROPIC" to LlmPricing.PROVIDER_ANTHROPIC,
            "PROVIDER_OPENAI" to LlmPricing.PROVIDER_OPENAI,
            "PROVIDER_GEMINI" to LlmPricing.PROVIDER_GEMINI,
        )) {
            assertTrue(
                value.isNotBlank(),
                "$name MUST be non-blank; got: '$value'",
            )
            assertEquals(
                value.lowercase(),
                value,
                "$name MUST be lowercase; got: '$value'",
            )
            assertTrue(
                value.all { it.isLetterOrDigit() },
                "$name MUST be alphanumeric (no hyphens / spaces); got: '$value'",
            )
        }
    }

    @Test fun providerIdsMatchAigcPricingExpectations() {
        // Cross-side coupling pin: AigcPricing's per-modality
        // rules compare `provenance.providerId` against
        // string literals "openai" / "replicate". The
        // PROVIDER_OPENAI constant in LlmPricing MUST equal
        // the literal AigcPricing checks against, otherwise
        // a pricing rule would match in one layer but not
        // the other.
        // We can't import AigcPricing's PRIVATE
        // PROVIDER_OPENAI constant from here, but we CAN
        // pin that PROVIDER_OPENAI in LlmPricing has the
        // value (`"openai"`) that AigcPricing.kt:179 also
        // declares. If either drifts, the cross-layer
        // identity is broken.
        assertEquals("openai", LlmPricing.PROVIDER_OPENAI)
        // Anthropic and Google don't have AigcPricing rules
        // currently (only LLM pricing) but the constants are
        // still load-bearing for `AigcPricing.estimateCents`
        // when those providers grow AIGC-side rules.
        assertEquals("anthropic", LlmPricing.PROVIDER_ANTHROPIC)
        assertEquals("gemini", LlmPricing.PROVIDER_GEMINI)
    }
}
