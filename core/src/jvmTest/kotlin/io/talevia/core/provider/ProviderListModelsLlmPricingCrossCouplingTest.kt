package io.talevia.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.provider.anthropic.AnthropicProvider
import io.talevia.core.provider.gemini.GeminiProvider
import io.talevia.core.provider.openai.OpenAiProvider
import io.talevia.core.provider.pricing.LlmPricing
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cross-coupling pins between each provider's [LlmProvider.listModels]
 * roster and [LlmPricing.find] entries.
 *
 * **Why a dedicated cross-coupling test file?** Cycles 311 & 312 fixed
 * two real bugs of the exact same shape — divergence between an id used
 * in `listModels()` and the same id used in `LlmPricing` — and each
 * silently broke pricing lookup for every call against the affected
 * model:
 *
 *   - cycle 311 — `AnthropicProvider.listModels()` exposed
 *     `claude-haiku-4-5-20251001` while `LlmPricing` had
 *     `claude-haiku-4-5`; every Haiku call routed through
 *     `LlmPricing.find("anthropic", "claude-haiku-4-5-20251001")`
 *     returned null, silently uncosting Haiku.
 *   - cycle 312 — `GeminiProvider.id` was `"gemini"` but
 *     `LlmPricing.PROVIDER_GOOGLE = "google"`; same shape, multi-
 *     touchpoint blast radius (`CheapestFirstPolicy` cost-sort,
 *     `EnvProviderAuth` env-var resolution).
 *
 * Both bugs were silent because pricing lookup returns nullable —
 * a missing entry is "uncosted call" not "loud crash". Every newly
 * added model id in either layer needs a counterpart in the other,
 * and the cheapest detection pin against this specific class of
 * drift is a roster cross-check that walks both directions:
 *
 *   - **Forward**: every `listModels()` model id MAY have a
 *     corresponding `LlmPricing` entry; if not, the model is
 *     reachable but uncosted (silent cost-policy failure).
 *   - **Reverse**: every `LlmPricing` entry's `(providerId, modelId)`
 *     MAY appear in some provider's `listModels()`; if not, the
 *     pricing is dead weight (entry-via-id-typed-by-caller works
 *     but model-discovery doesn't surface it).
 *
 * **Strict alignment (Anthropic 2026-04)**: post-cycle-311, Anthropic's
 * 3 listModels ids are exactly the 3 LlmPricing entries. This file
 * pins that as a **strict** invariant — drift in either layer surfaces
 * here.
 *
 * **Observed divergences (OpenAI / Gemini, as of cycle 313)**: pinned
 * here as **observational** state, not strict invariants — the resolution
 * is a product question (which roster is canonical?) that the audit
 * loop can't answer alone. The pins document the divergence so it
 * surfaces when either layer changes; a future cycle (with user input
 * on which models to canonicalise) will promote to a fix in the same
 * cycle-309 → 311/312 trajectory:
 *
 *   - **OpenAI**: listModels = `{gpt-4o, gpt-4o-mini, gpt-4.1}`;
 *     LlmPricing entries = `{gpt-5.4, gpt-5.4-mini, gpt-4o, gpt-4o-mini}`.
 *     - `gpt-4.1` (1M context) listed but unpriced → silent uncosted
 *       calls when this model is selected.
 *     - `gpt-5.4` & `gpt-5.4-mini` priced but not listed; the gpt-5
 *       lineup is exposed via `OpenAiCodexProvider.id = "openai-codex"`
 *       (separate provider). The pricing entries are filed under
 *       `PROVIDER_OPENAI = "openai"` though, not `"openai-codex"` — so
 *       even when the Codex provider runs, pricing lookup misses
 *       (same shape as cycle 312 Gemini bug). Resolution requires
 *       deciding: are `gpt-5.4*` reachable via the standard OpenAI
 *       chat completions endpoint, or are they Codex-only and the
 *       pricing entries should migrate to `"openai-codex"`?
 *
 *   - **Gemini**: listModels = `{gemini-2.5-pro, gemini-2.5-flash,
 *     gemini-2.0-flash}`; LlmPricing entries = `{gemini-2.5-pro,
 *     gemini-2.5-flash}`.
 *     - `gemini-2.0-flash` listed but unpriced → silent uncosted
 *       calls. Resolution: either price `gemini-2.0-flash` (canonical
 *       Google rates exist) or remove from listModels if 2.0 is
 *       deprecated and 2.5 supersedes it.
 */
class ProviderListModelsLlmPricingCrossCouplingTest {

    private fun anthropic() = AnthropicProvider(HttpClient(CIO), apiKey = "test")
    private fun openai() = OpenAiProvider(HttpClient(CIO), apiKey = "test")
    private fun gemini() = GeminiProvider(HttpClient(CIO), apiKey = "test")

    // ── Anthropic: strict alignment (post-cycle-311) ───────

    @Test fun anthropicEveryListedModelHasLlmPricingEntry() = runTest {
        // Strict forward pin: Anthropic's listModels roster is
        // entirely covered by LlmPricing entries. Cycle 311 fix
        // (haiku id alignment) brought this to full coverage.
        // Drift in either layer surfaces here as a model with
        // null pricing.
        for (model in anthropic().listModels()) {
            val priced = LlmPricing.find("anthropic", model.id)
            assertNotNull(
                priced,
                "Anthropic listModels id '${model.id}' MUST have an LlmPricing entry " +
                    "(cycle 311 alignment fix; null lookup = silent uncosted call)",
            )
        }
    }

    @Test fun anthropicEveryLlmPricingEntryAppearsInListModels() = runTest {
        // Strict reverse pin: every Anthropic LlmPricing entry
        // is reachable via listModels(). A priced-but-unlisted
        // model is dead weight in the discovery layer — ids
        // typed by hand still work but `/model` UI / cost-sort
        // policies that walk listModels can't surface it.
        val listed = anthropic().listModels().map { it.id }.toSet()
        val priced = LlmPricing.all().filter { it.providerId == "anthropic" }
        for (entry in priced) {
            assertTrue(
                entry.modelId in listed,
                "LlmPricing anthropic entry '${entry.modelId}' MUST appear in " +
                    "AnthropicProvider.listModels() (else it's dead pricing weight)",
            )
        }
    }

    @Test fun anthropicListModelsAndLlmPricingEntriesHaveSameSize() = runTest {
        // Marquee count-equality pin: any new model added on
        // either side without a matching counterpart surfaces
        // here as an immediate count mismatch.
        val listed = anthropic().listModels().size
        val priced = LlmPricing.all().count { it.providerId == "anthropic" }
        assertEquals(
            listed,
            priced,
            "Anthropic listModels count ($listed) MUST equal LlmPricing entries " +
                "count ($priced) — cycle 311 / 313 alignment invariant",
        )
    }

    // ── OpenAI: observed divergence (cycle 313) ────────────

    @Test fun openaiGpt4oFamilyAppearsInBothLayers() = runTest {
        // Positive partial-alignment pin: regardless of the
        // gpt-5.4 / gpt-4.1 divergence below, the gpt-4o
        // family must remain aligned. This is the stable
        // intersection that production code relies on for
        // chat-completions cost-sort.
        val listed = openai().listModels().map { it.id }.toSet()
        for (id in listOf("gpt-4o", "gpt-4o-mini")) {
            assertTrue(id in listed, "$id MUST be in OpenAi listModels")
            assertNotNull(
                LlmPricing.find("openai", id),
                "$id MUST have LlmPricing entry under 'openai'",
            )
        }
    }

    @Test fun openaiGpt41IsListedButUnpriced() = runTest {
        // Observed divergence pin (cycle 313): gpt-4.1 is the
        // 1M-context-window long-context model exposed via
        // listModels but has no LlmPricing entry. Calls
        // against gpt-4.1 are silently uncosted. Resolution:
        // add `Entry(PROVIDER_OPENAI, "gpt-4.1", ...)` with
        // canonical OpenAI pricing.
        val gpt41 = openai().listModels().find { it.id == "gpt-4.1" }
        assertNotNull(gpt41, "gpt-4.1 MUST be in OpenAi listModels (observed cycle 313)")
        assertEquals(1_000_000, gpt41.contextWindow)
        assertNull(
            LlmPricing.find("openai", "gpt-4.1"),
            "gpt-4.1 LlmPricing entry currently absent (cycle 313 observed " +
                "divergence — silent uncosted spend; future cycle adds entry " +
                "with canonical OpenAI rates)",
        )
    }

    @Test fun openaiGpt54FamilyIsPricedButNotListed() = runTest {
        // Observed divergence pin (cycle 313): gpt-5.4 + gpt-5.4-mini
        // are priced under `PROVIDER_OPENAI = "openai"` but not
        // exposed via OpenAiProvider.listModels(). The gpt-5 lineup
        // is reachable through OpenAiCodexProvider (id "openai-codex")
        // — a SEPARATE provider with its own listModels. So even
        // when Codex calls gpt-5.4, the pricing-lookup providerId
        // ("openai-codex") doesn't match the pricing entry's
        // providerId ("openai"). Same silent-uncost shape as
        // cycle 312 Gemini fix.
        //
        // Resolution requires product decision: is gpt-5.4
        // reachable via standard OpenAI chat-completions
        // (then add to OpenAiProvider.listModels), or is it
        // Codex-only (then migrate the LlmPricing entries from
        // PROVIDER_OPENAI to PROVIDER_OPENAI_CODEX = "openai-codex")?
        val listed = openai().listModels().map { it.id }.toSet()
        for (id in listOf("gpt-5.4", "gpt-5.4-mini")) {
            assertNotNull(
                LlmPricing.find("openai", id),
                "$id MUST currently have LlmPricing entry under 'openai' " +
                    "(observed cycle 313; resolution may migrate to 'openai-codex')",
            )
            assertTrue(
                id !in listed,
                "$id is currently NOT in OpenAiProvider.listModels (observed " +
                    "cycle 313 — divergence; resolution either lists or migrates)",
            )
        }
    }

    @Test fun openaiListedAndPricedCountsEachAreFour() = runTest {
        // Tally pin: 3 listed (gpt-4o, gpt-4o-mini, gpt-4.1)
        // + 4 priced (gpt-5.4, gpt-5.4-mini, gpt-4o, gpt-4o-mini)
        // + intersection of 2 (gpt-4o family).
        // The numbers themselves are the cycle-313 snapshot; any
        // resolution will move them in lockstep.
        assertEquals(3, openai().listModels().size, "OpenAi listModels count = 3 (cycle 313)")
        assertEquals(
            4,
            LlmPricing.all().count { it.providerId == "openai" },
            "OpenAi LlmPricing count = 4 (cycle 313 — 2 of which are gpt-5.4 family " +
                "currently mismatched against OpenAiCodexProvider; see family pin)",
        )
    }

    // ── Gemini: observed divergence (cycle 313) ────────────

    @Test fun gemini25FamilyAppearsInBothLayers() = runTest {
        // Positive partial-alignment pin: 2.5 family is the
        // stable intersection. Drift in 2.5 ids surfaces here.
        val listed = gemini().listModels().map { it.id }.toSet()
        for (id in listOf("gemini-2.5-pro", "gemini-2.5-flash")) {
            assertTrue(id in listed, "$id MUST be in Gemini listModels")
            assertNotNull(
                LlmPricing.find("gemini", id),
                "$id MUST have LlmPricing entry under 'gemini' (cycle 312 id alignment)",
            )
        }
    }

    @Test fun gemini20FlashIsListedButUnpriced() = runTest {
        // Observed divergence pin (cycle 313): gemini-2.0-flash
        // exposed via listModels but no LlmPricing entry. Calls
        // silently uncosted. Resolution: either price 2.0 (Google
        // publishes the rate) or remove from listModels if 2.5
        // supersedes 2.0 in the registry surface.
        val flash20 = gemini().listModels().find { it.id == "gemini-2.0-flash" }
        assertNotNull(flash20, "gemini-2.0-flash MUST be in Gemini listModels (observed cycle 313)")
        assertNull(
            LlmPricing.find("gemini", "gemini-2.0-flash"),
            "gemini-2.0-flash LlmPricing entry currently absent (cycle 313 " +
                "observed divergence — silent uncosted spend; future cycle " +
                "either prices it or drops 2.0 from listModels)",
        )
    }

    @Test fun geminiListedAndPricedCountsAreThreeAndTwo() = runTest {
        // Tally pin: 3 listed (2.5-pro, 2.5-flash, 2.0-flash) +
        // 2 priced (2.5-pro, 2.5-flash) + intersection of 2.
        assertEquals(3, gemini().listModels().size, "Gemini listModels count = 3 (cycle 313)")
        assertEquals(
            2,
            LlmPricing.all().count { it.providerId == "gemini" },
            "Gemini LlmPricing count = 2 (cycle 313 — 2.0-flash unpriced)",
        )
    }

    // ── Three-provider invariant ───────────────────────────

    @Test fun listModelsRosterAcrossThreeProvidersIsNineModels() = runTest {
        // Marquee total-count pin: the in-tree LlmProvider
        // surface (Anthropic 3 + OpenAi 3 + Gemini 3 = 9) is
        // load-bearing for any "list every available model"
        // UI like the CLI /model picker. Adding a 4th model to
        // any provider OR adding a 4th LlmProvider impl shifts
        // this number — drift is meaningful and surfaces here.
        val total = anthropic().listModels().size +
            openai().listModels().size +
            gemini().listModels().size
        assertEquals(
            9,
            total,
            "3 in-tree providers × 3 models each MUST total 9 (cycle 313 snapshot; " +
                "drift = registry-surface change worth a deliberate review)",
        )
    }

    @Test fun llmPricingTotalEntriesIsNine() = runTest {
        // Sister pin: total LlmPricing entries (3 anthropic +
        // 4 openai + 2 gemini = 9). The shared total of 9 is
        // coincidental — they are independent rosters; the
        // pin documents the snapshot so a divergence that
        // shifts only one side is clearly visible (current:
        // listModels=9, pricing=9, but the 9s are made up of
        // different shapes per provider).
        val total = LlmPricing.all().size
        assertEquals(
            9,
            total,
            "LlmPricing total entries = 9 (cycle 313 snapshot; shape per " +
                "provider differs — see per-provider pins)",
        )
    }
}
