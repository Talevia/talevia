package io.talevia.core.compaction

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.provider.anthropic.AnthropicProvider
import io.talevia.core.provider.gemini.GeminiProvider
import io.talevia.core.provider.openai.OpenAiProvider
import io.talevia.core.session.ModelRef
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-coupling pins between each in-tree [io.talevia.core.provider.LlmProvider]'s
 * `listModels()` `contextWindow` and the actual prune-keep budget
 * [PerModelCompactionBudget] computes for it. Sibling to cycle 314's
 * [PerModelCompactionThresholdRealProvidersTest] — that file pins
 * the *trigger* threshold (`window × 0.85`); this file pins the
 * *aggressiveness* budget (`window × 0.30`).
 *
 * **Why a separate budget pin?** Existing [CompactionBudgetTest]'s
 * `perModelBudgetScalesByContextWindow` covers the formula via
 * synthetic `FakeProvider` rosters; `fromRegistryWiresEveryProviderModel`
 * covers aggregation again with synthetic rosters. **0 of 9** real-
 * provider model ids are pinned to their resolved budget value. The
 * same drift class as cycle 314 — `contextWindow` typo / refactor /
 * roster reorder silently shifts the prune-keep number for production
 * sessions — but here the impact is *worse*: a bad budget doesn't just
 * over-trigger compaction, it makes each compaction pass leave too
 * little (or too much) headroom, defeating the whole point.
 *
 * **Pin shape**: same as cycle 314 — pin the **resolved budget** value
 * after `× ratio` and fallback, not the raw `contextWindow`. The two
 * scalars [CompactionBudget.DEFAULT_PRUNE_KEEP_RATIO] (0.30) and
 * [CompactionBudget.DEFAULT.protectUserTurns] (2) are themselves load-
 * bearing; pinning the resolved values catches drift in either axis.
 *
 * Why two ratio constants both pinned (`0.85` threshold + `0.30` budget):
 * the gap between them is what makes auto-compaction stable. If
 * threshold ratio = 0.85 and budget ratio = 0.85, every compaction
 * would land you exactly at the trigger — re-firing on the next turn.
 * The 0.55 gap (0.85 − 0.30) means each compaction actually relieves
 * pressure for ~50 % of the context window before the next trigger.
 * Drift in either ratio narrows or inverts that gap; pinning both
 * surfaces protects the load-bearing invariant.
 */
class PerModelCompactionBudgetRealProvidersTest {

    private fun anthropic() = AnthropicProvider(HttpClient(CIO), apiKey = "test")
    private fun openai() = OpenAiProvider(HttpClient(CIO), apiKey = "test")
    private fun gemini() = GeminiProvider(HttpClient(CIO), apiKey = "test")

    private fun registryOf(vararg providers: io.talevia.core.provider.LlmProvider): ProviderRegistry {
        val builder = ProviderRegistry.Builder()
        providers.forEach { builder.add(it) }
        return builder.build()
    }

    // ── Anthropic: 200k × 0.30 = 60_000 budget ─────────────

    @Test fun anthropicAllThreeModelsResolveTo60kBudget() = runTest {
        // Marquee per-provider pin: all 3 Anthropic models share
        // a 200k context window today, so all 3 share a 60_000
        // pruneKeepTokens budget. Drift in any single model's
        // contextWindow surfaces here as a one-line mismatch.
        // The protectUserTurns dimension stays at the default 2.
        val resolver = PerModelCompactionBudget.fromRegistry(registryOf(anthropic()))
        for (id in listOf("claude-opus-4-7", "claude-sonnet-4-6", "claude-haiku-4-5")) {
            val budget = resolver(ModelRef("anthropic", id))
            assertEquals(
                60_000,
                budget.pruneKeepTokens,
                "Anthropic '$id' pruneKeepTokens MUST be 60_000 (200k × 0.30); drift in " +
                    "either contextWindow or DEFAULT_PRUNE_KEEP_RATIO surfaces here",
            )
            assertEquals(
                2,
                budget.protectUserTurns,
                "Anthropic '$id' protectUserTurns MUST stay at default 2 (UX decision, " +
                    "not size-scaled per CompactionBudget kdoc)",
            )
        }
    }

    // ── OpenAI: tiered context windows → tiered budgets ───

    @Test fun openaiGpt4oFamilyResolvesTo38_400Budget() = runTest {
        // gpt-4o + gpt-4o-mini share 128k context → 38_400 budget.
        // Tightest budget in the registry; if 0.30 ratio drifted,
        // gpt-4o sessions would either thrash compaction (smaller
        // budget) or skip it (larger budget) more visibly than
        // gemini's huge contexts.
        val resolver = PerModelCompactionBudget.fromRegistry(registryOf(openai()))
        for (id in listOf("gpt-4o", "gpt-4o-mini")) {
            val budget = resolver(ModelRef("openai", id))
            assertEquals(
                38_400,
                budget.pruneKeepTokens,
                "OpenAI '$id' pruneKeepTokens MUST be 38_400 (128k × 0.30)",
            )
        }
    }

    @Test fun openaiGpt41ResolvesTo300_000Budget() = runTest {
        // gpt-4.1 advertises 1M context → 300_000 prune-keep budget.
        // Same context size as Gemini Flash; pin separately since the
        // OpenAi long-context tier is one of the most cost-impactful
        // sessions an agent can run, and a misconfigured budget here
        // wastes a lot of tokens per compaction.
        val resolver = PerModelCompactionBudget.fromRegistry(registryOf(openai()))
        val budget = resolver(ModelRef("openai", "gpt-4.1"))
        assertEquals(
            300_000,
            budget.pruneKeepTokens,
            "OpenAI 'gpt-4.1' pruneKeepTokens MUST be 300_000 (1M × 0.30; long-context tier)",
        )
    }

    // ── Gemini: largest in-tree context windows → largest budgets ──

    @Test fun geminiPro25ResolvesTo600_000Budget() = runTest {
        // Marquee long-context pin: gemini-2.5-pro at 2M → 600k
        // pruneKeepTokens. The biggest budget in the registry —
        // drift here matters proportionally for users who picked
        // gemini specifically for the 2M capacity. A dropped zero
        // (60k instead of 600k) would silently 10× the compaction
        // aggressiveness and waste users' context investment.
        val resolver = PerModelCompactionBudget.fromRegistry(registryOf(gemini()))
        val budget = resolver(ModelRef("gemini", "gemini-2.5-pro"))
        assertEquals(
            600_000,
            budget.pruneKeepTokens,
            "Gemini 'gemini-2.5-pro' pruneKeepTokens MUST be 600_000 (2M × 0.30; biggest " +
                "in-tree budget, drift here = visible cost regression)",
        )
    }

    @Test fun gemini25FlashAnd20FlashResolveTo300kBudget() = runTest {
        // Both Flash variants advertise 1M context → 300k budget.
        // Same value as gpt-4.1; separate pin so cross-provider
        // 1M-tier movements both surface.
        val resolver = PerModelCompactionBudget.fromRegistry(registryOf(gemini()))
        for (id in listOf("gemini-2.5-flash", "gemini-2.0-flash")) {
            val budget = resolver(ModelRef("gemini", id))
            assertEquals(
                300_000,
                budget.pruneKeepTokens,
                "Gemini '$id' pruneKeepTokens MUST be 300_000 (1M × 0.30)",
            )
        }
    }

    // ── Cross-provider: full registry composition ─────────

    @Test fun fullRegistryAcrossThreeProvidersResolvesAllNineModels() = runTest {
        // Tally pin: all 9 listModels entries resolve to the
        // expected (pruneKeepTokens, protectUserTurns) pair when
        // wired together. Catches refactor drift in `fromRegistry`
        // that drops a provider's models silently.
        val resolver = PerModelCompactionBudget.fromRegistry(
            registryOf(anthropic(), openai(), gemini()),
        )
        val expected = mapOf(
            ModelRef("anthropic", "claude-opus-4-7") to 60_000,
            ModelRef("anthropic", "claude-sonnet-4-6") to 60_000,
            ModelRef("anthropic", "claude-haiku-4-5") to 60_000,
            ModelRef("openai", "gpt-4o") to 38_400,
            ModelRef("openai", "gpt-4o-mini") to 38_400,
            ModelRef("openai", "gpt-4.1") to 300_000,
            ModelRef("gemini", "gemini-2.5-pro") to 600_000,
            ModelRef("gemini", "gemini-2.5-flash") to 300_000,
            ModelRef("gemini", "gemini-2.0-flash") to 300_000,
        )
        for ((ref, keepTokens) in expected) {
            val budget = resolver(ref)
            assertEquals(
                keepTokens,
                budget.pruneKeepTokens,
                "${ref.providerId}/${ref.modelId} pruneKeepTokens MUST be $keepTokens",
            )
            assertEquals(2, budget.protectUserTurns, "${ref.providerId}/${ref.modelId} protectUserTurns MUST be 2")
        }
    }

    // ── Isolation pins (sister to cycle 314's threshold isolation) ──

    @Test fun fallbackFiresOnDefaultForWrongProviderId() = runTest {
        // Cross-provider isolation: model id collision MUST fall
        // through to fallback (= CompactionBudget.DEFAULT), NOT
        // silently match an OpenAi entry under "gemini". Catches
        // refactor that collapses (providerId, modelId) key to
        // just modelId — same drift shape as cycle 312 "google" →
        // "gemini" id-collapse fix.
        val resolver = PerModelCompactionBudget.fromRegistry(
            registryOf(anthropic(), openai(), gemini()),
        )
        val budget = resolver(ModelRef("gemini", "gpt-4.1"))
        assertEquals(
            CompactionBudget.DEFAULT,
            budget,
            "Wrong-provider lookup MUST fall through to DEFAULT (40k / 2-turn), NOT match " +
                "the OpenAi gpt-4.1 entry",
        )
    }

    @Test fun fallbackFiresOnDefaultForUnknownModelOnRealProvider() = runTest {
        // Unknown model id on a known provider MUST fall through.
        val resolver = PerModelCompactionBudget.fromRegistry(
            registryOf(anthropic(), openai(), gemini()),
        )
        val budget = resolver(ModelRef("anthropic", "claude-opus-5"))
        assertEquals(
            CompactionBudget.DEFAULT,
            budget,
            "Unknown model id on a known provider MUST fall through to DEFAULT",
        )
    }

    // ── Constants pin (drift-protective floor for both ratios) ────

    @Test fun keepRatioConstantIsLoadBearing() {
        // Direct constant pin for the ratio multiplied through every
        // budget above. Drift here shifts every model's prune-keep
        // budget AT THE SAME TIME — the per-model tests above all
        // go red simultaneously, but this test surfaces the root
        // cause as one explicit failure rather than 9 derived ones.
        assertEquals(
            0.30,
            CompactionBudget.DEFAULT_PRUNE_KEEP_RATIO,
            "DEFAULT_PRUNE_KEEP_RATIO MUST be 0.30 (drift = either over-aggressive " +
                "compaction or under-relief that re-fires on the next turn)",
        )
    }

    @Test fun defaultBudgetMatchesLegacyNumbers() {
        // Sister pin to cycle 314's
        // ratioAndFallbackConstantsAreLoadBearing — `DEFAULT` is the
        // back-stop budget every unknown / wrong-provider lookup
        // falls through to. Drift in either field silently changes
        // unknown-model UX without breaking any per-model formula
        // pin.
        assertEquals(
            CompactionBudget(protectUserTurns = 2, pruneKeepTokens = 40_000),
            CompactionBudget.DEFAULT,
            "DEFAULT MUST be (2, 40_000) — legacy hardcoded numbers from the pre-cycle " +
                "40k-budget era",
        )
    }

    @Test fun thresholdAndBudgetRatiosLeaveAtLeastFiftyPercentRecoveryGap() {
        // The load-bearing invariant linking the two ratios: each
        // compaction MUST relieve pressure significantly below the
        // re-trigger point. If threshold = 0.85 and budget = 0.30,
        // the gap is 0.55 → each compaction recovers ~half the
        // window before the next trigger. Drift to budget = 0.80
        // would compact and immediately re-trigger; budget = 0.20
        // would over-trim recent context. Pin the gap explicitly
        // so a one-side ratio change is caught even if both stay
        // in [0,1].
        val gap = DEFAULT_COMPACTION_THRESHOLD_RATIO - CompactionBudget.DEFAULT_PRUNE_KEEP_RATIO
        kotlin.test.assertTrue(
            gap >= 0.50,
            "threshold ratio - budget ratio MUST be ≥ 0.50 to avoid trigger thrash; " +
                "got threshold=$DEFAULT_COMPACTION_THRESHOLD_RATIO, " +
                "budget=${CompactionBudget.DEFAULT_PRUNE_KEEP_RATIO}, gap=$gap",
        )
    }
}
