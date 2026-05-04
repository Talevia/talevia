package io.talevia.core.provider.pricing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the **exact rate values** in [LlmPricing.ENTRIES] (the
 * 2026-04 snapshot) + the per-provider counts. Cycle 310 audit:
 * existing [LlmPricingTest] (cycle 280 era) covers structural
 * invariants (well-formed / unique / non-negative) but does NOT
 * pin literal values — drift in any rate silently changes every
 * downstream cost estimate.
 *
 * Same audit-pattern fallback as cycles 207-309. Sister of cycle
 * 309's ProviderListModelsTest which pins the listModels rosters.
 *
 * Why pin literal rates: this is intentionally a tight coupling
 * to the 2026-04 snapshot. Reprice PRs SHOULD break this test —
 * that's the drift detection contract. The break should be
 * accompanied by an explicit table update in the same PR.
 *
 * Drift surface protected:
 *   - **Rate drift** (1.5 → 2.0) silently 33%-inflates every
 *     Opus cost estimate.
 *   - **Model id drift** in the entries (claude-opus-4-7 →
 *     claude-opus-5) silently breaks pricing lookup for every
 *     downstream consumer.
 *   - **Cross-coupling drift**: every entry in LlmPricing MUST
 *     have a corresponding entry in some provider's
 *     listModels() — drift to add a priced entry without a
 *     model-roster pair silently makes the entry unreachable.
 *     Cycle 309 found that Anthropic's "claude-haiku-4-5-20251001"
 *     in listModels does NOT match LlmPricing's
 *     "claude-haiku-4-5" — pin documents the divergence.
 *
 * §5 axis: §5.6 (test-debt covering pricing-table drift).
 */
class LlmPricingEntriesValueTest {

    // ── Exact entry count + per-provider count ──────────────

    @Test fun entriesHasExactlyNineRows() {
        // Marquee count pin: 9 rows (3 Anthropic + 4 OpenAI + 2 Google).
        // Drift to add / remove silently changes the priced
        // surface. Reprice PR adding a new model breaks this
        // intentionally — bump the count in lockstep.
        assertEquals(
            9,
            LlmPricing.all().size,
            "LlmPricing entry count MUST be 9 (3 Anthropic + 4 OpenAI + 2 Google)",
        )
    }

    @Test fun anthropicHasThreePricedEntries() {
        val anthropic = LlmPricing.all().filter { it.providerId == "anthropic" }
        assertEquals(3, anthropic.size, "Anthropic priced entries MUST be 3")
    }

    @Test fun openaiHasFourPricedEntries() {
        val openai = LlmPricing.all().filter { it.providerId == "openai" }
        assertEquals(4, openai.size, "OpenAI priced entries MUST be 4")
    }

    @Test fun geminiHasTwoPricedEntries() {
        // Cycle 312: providerId migrated "google" → "gemini"
        // to align with GeminiProvider.id. Pre-cycle-312 the
        // entries still answered to "google".
        val gemini = LlmPricing.all().filter { it.providerId == "gemini" }
        assertEquals(2, gemini.size, "Gemini priced entries MUST be 2")
    }

    // ── Anthropic exact rates ───────────────────────────────

    @Test fun claudeOpus47ExactRates() {
        // Marquee rate pin: drift in either rate silently
        // changes every Opus cost estimate. Reprice in
        // lockstep.
        val opus = LlmPricing.find("anthropic", "claude-opus-4-7")
        assertNotNull(opus)
        assertEquals(1.5, opus.centsPer1kInputTokens, "Opus input MUST be 1.5 ¢/1k")
        assertEquals(7.5, opus.centsPer1kOutputTokens, "Opus output MUST be 7.5 ¢/1k")
    }

    @Test fun claudeSonnet46ExactRates() {
        val sonnet = LlmPricing.find("anthropic", "claude-sonnet-4-6")
        assertNotNull(sonnet)
        assertEquals(0.3, sonnet.centsPer1kInputTokens)
        assertEquals(1.5, sonnet.centsPer1kOutputTokens)
    }

    @Test fun claudeHaiku45ExactRates() {
        // Pin: pricing entry uses model id "claude-haiku-4-5"
        // (NOT "claude-haiku-4-5-20251001" which is the
        // listModels id — banked divergence from cycle 309).
        val haiku = LlmPricing.find("anthropic", "claude-haiku-4-5")
        assertNotNull(haiku, "Haiku priced entry uses 'claude-haiku-4-5' (NOT date-suffixed)")
        assertEquals(0.1, haiku.centsPer1kInputTokens)
        assertEquals(0.5, haiku.centsPer1kOutputTokens)
    }

    // ── OpenAI exact rates ──────────────────────────────────

    @Test fun gpt54ExactRates() {
        val gpt54 = LlmPricing.find("openai", "gpt-5.4")
        assertNotNull(gpt54)
        assertEquals(0.25, gpt54.centsPer1kInputTokens)
        assertEquals(1.0, gpt54.centsPer1kOutputTokens)
    }

    @Test fun gpt54MiniExactRates() {
        val mini = LlmPricing.find("openai", "gpt-5.4-mini")
        assertNotNull(mini)
        assertEquals(0.015, mini.centsPer1kInputTokens, "gpt-5.4-mini input MUST be 0.015 ¢/1k")
        assertEquals(0.06, mini.centsPer1kOutputTokens)
    }

    @Test fun gpt4oExactRates() {
        val gpt4o = LlmPricing.find("openai", "gpt-4o")
        assertNotNull(gpt4o)
        assertEquals(0.25, gpt4o.centsPer1kInputTokens)
        assertEquals(1.0, gpt4o.centsPer1kOutputTokens)
    }

    @Test fun gpt4oMiniExactRates() {
        val mini = LlmPricing.find("openai", "gpt-4o-mini")
        assertNotNull(mini)
        assertEquals(0.015, mini.centsPer1kInputTokens)
        assertEquals(0.06, mini.centsPer1kOutputTokens)
    }

    // ── Google exact rates ──────────────────────────────────

    @Test fun gemini25ProExactRates() {
        val pro = LlmPricing.find("gemini", "gemini-2.5-pro")
        assertNotNull(pro)
        assertEquals(0.125, pro.centsPer1kInputTokens, "Gemini 2.5 Pro input MUST be 0.125 ¢/1k")
        assertEquals(0.5, pro.centsPer1kOutputTokens)
    }

    @Test fun gemini25FlashExactRates() {
        // Marquee cheapest-in-table pin: Gemini 2.5 Flash at
        // 0.0075 ¢/1k input is the cheapest entry in the
        // table. Drift would silently change which model
        // wins cost-sort comparisons.
        val flash = LlmPricing.find("gemini", "gemini-2.5-flash")
        assertNotNull(flash)
        assertEquals(
            0.0075,
            flash.centsPer1kInputTokens,
            "Gemini 2.5 Flash input MUST be 0.0075 ¢/1k (cheapest in table)",
        )
        assertEquals(0.03, flash.centsPer1kOutputTokens)
    }

    // ── Cross-coupling: cheapest / most-expensive monotonic

    @Test fun cheapestEntryIsGemini25Flash() {
        // Marquee cost-sort pin: Gemini 2.5 Flash is the
        // cheapest input rate in the table. Drift to a
        // cheaper Anthropic / OpenAI entry would silently
        // re-route cost-sort logic.
        val cheapest = LlmPricing.all().minBy { it.centsPer1kInputTokens }
        assertEquals(
            "gemini-2.5-flash",
            cheapest.modelId,
            "cheapest entry MUST be gemini-2.5-flash (drift in any rate that demotes it surfaces here)",
        )
        assertEquals("gemini", cheapest.providerId)
    }

    @Test fun mostExpensiveOutputEntryIsClaudeOpus47() {
        // Sister cost-sort pin: Opus has the highest output
        // rate (7.5 ¢/1k). Drift in any other rate above 7.5
        // surfaces here.
        val mostExpensive = LlmPricing.all().maxBy { it.centsPer1kOutputTokens }
        assertEquals("claude-opus-4-7", mostExpensive.modelId)
        assertEquals(7.5, mostExpensive.centsPer1kOutputTokens)
    }

    // ── Output >= input invariant (sanity, already covered) ─

    @Test fun everyEntryHasOutputRateAtLeastInputRate() {
        // Sister of LlmPricingTest's well-formed pin —
        // explicit per-entry assertion makes failure messages
        // identify the offending row.
        for (entry in LlmPricing.all()) {
            assertTrue(
                entry.centsPer1kOutputTokens >= entry.centsPer1kInputTokens,
                "${entry.providerId}/${entry.modelId}: output (${entry.centsPer1kOutputTokens}) " +
                    "MUST be >= input (${entry.centsPer1kInputTokens}) — probable transposition typo",
            )
        }
    }

    // ── Provider id consistency ────────────────────────────

    @Test fun everyEntryUsesOneOfThreeKnownProviderIds() {
        // Pin: provider ids in entries are exactly
        // anthropic / openai / gemini. Drift to add a 4th
        // provider via Entry would surface here. (Cycle 312
        // migrated "google" → "gemini".)
        val knownProviders = setOf("anthropic", "openai", "gemini")
        for (entry in LlmPricing.all()) {
            assertTrue(
                entry.providerId in knownProviders,
                "${entry.providerId} MUST be one of $knownProviders",
            )
        }
    }

    // ── estimateCostCents formula at exact published numbers

    @Test fun opusOneMillionInputTokensRoundsToFifteenHundredCents() {
        // Marquee numerical pin: 1M tokens × 1.5 ¢/1k = 1500 ¢
        // = $15. Drift in either the rate or the formula
        // surfaces here.
        val opus = LlmPricing.find("anthropic", "claude-opus-4-7")!!
        assertEquals(
            1500L,
            opus.estimateCostCents(inputTokens = 1_000_000, outputTokens = 0),
            "1M Opus input tokens MUST cost 1500¢ (1M × 1.5/1k)",
        )
    }

    @Test fun haikuOneMillionInputTokensRoundsToHundredCents() {
        // Sister numerical pin: Haiku's input rate (0.1) ×
        // 1M = 100 ¢ = $1.
        val haiku = LlmPricing.find("anthropic", "claude-haiku-4-5")!!
        assertEquals(100L, haiku.estimateCostCents(inputTokens = 1_000_000, outputTokens = 0))
    }

    @Test fun gpt4oMixedInputOutputRollupCalculation() {
        // Sister mixed-token pin: 10k input + 5k output × 0.25
        // / 1.0 ¢/1k = 2.5 + 5.0 = 7.5 → rounded half-up = 8 ¢.
        val gpt4o = LlmPricing.find("openai", "gpt-4o")!!
        assertEquals(
            8L,
            gpt4o.estimateCostCents(inputTokens = 10_000, outputTokens = 5_000),
            "gpt-4o 10k input + 5k output: (10*0.25)+(5*1.0) = 7.5 → rounded 8¢",
        )
    }
}
