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
 * `listModels()` `contextWindow` and the actual auto-compaction trigger
 * value [PerModelCompactionThreshold] computes for it. The sibling
 * [PerModelCompactionThresholdTest] verifies the formula
 * (`contextWindow × ratio`) against synthetic `FakeProvider` rosters
 * — but the **real-provider rosters** (Anthropic + OpenAi + Gemini)
 * have no test that asserts the resolved threshold values for the
 * actual model ids agents call against in production.
 *
 * **Why this matters**: silent drift in `contextWindow` on any
 * in-tree provider would silently change auto-compaction cadence
 * for every session using that model. Examples of how that drift
 * could surface:
 *
 * - A copy-paste typo flipping `contextWindow = 200_000` →
 *   `contextWindow = 100_000` halves the compaction trigger silently
 *   from 170k → 85k — sessions compact ~2× more often, the agent
 *   loses ~70k of working context per turn, and there's no loud
 *   failure to point at.
 * - A new "extended context" model added with the wrong window value
 *   would either compact too aggressively (wasting expensive cache
 *   entries) or never compact at all (running into provider-side hard
 *   limits and 4xx errors).
 *
 * Same drift-class as cycles 309/310 listModels rate pins and cycle
 * 313 listModels × LlmPricing cross-coupling — a downstream system
 * derives behaviour from a hardcoded provider-side table that no
 * test pins against the resolved value.
 *
 * **Pin shape**: pin the **resolved threshold** (after `× ratio` and
 * fallback), not the raw `contextWindow`. The ratio constant
 * [DEFAULT_COMPACTION_THRESHOLD_RATIO] (0.85) and fallback
 * [DEFAULT_COMPACTION_TOKEN_THRESHOLD] (120k) are themselves load-
 * bearing — drifting either silently shifts every model's compaction
 * cadence — so pinning the resolved value catches both axis
 * simultaneously.
 */
class PerModelCompactionThresholdRealProvidersTest {

    private fun anthropic() = AnthropicProvider(HttpClient(CIO), apiKey = "test")
    private fun openai() = OpenAiProvider(HttpClient(CIO), apiKey = "test")
    private fun gemini() = GeminiProvider(HttpClient(CIO), apiKey = "test")

    private fun registryOf(vararg providers: io.talevia.core.provider.LlmProvider): ProviderRegistry {
        val builder = ProviderRegistry.Builder()
        providers.forEach { builder.add(it) }
        return builder.build()
    }

    // ── Anthropic: 200k context × 0.85 = 170k threshold ────

    @Test fun anthropicAllThreeModelsResolveTo170kThreshold() = runTest {
        // Marquee per-provider pin: all 3 Anthropic models share
        // a 200k context window today, so all 3 share a 170k
        // compaction threshold. Drift on any one model surfaces
        // here as a single-line mismatch.
        val resolver = PerModelCompactionThreshold.fromRegistry(registryOf(anthropic()))
        for (id in listOf("claude-opus-4-7", "claude-sonnet-4-6", "claude-haiku-4-5")) {
            assertEquals(
                170_000,
                resolver(ModelRef("anthropic", id)),
                "Anthropic '$id' threshold MUST be 170_000 (200k × 0.85); drift in either " +
                    "contextWindow or DEFAULT_COMPACTION_THRESHOLD_RATIO surfaces here",
            )
        }
    }

    // ── OpenAI: heterogeneous context windows ──────────────

    @Test fun openaiGpt4oResolvesTo108_800Threshold() = runTest {
        // gpt-4o + gpt-4o-mini share a 128k context window, so
        // both share 108_800 threshold. Pin the gpt-4o family
        // separately from gpt-4.1 because the long-context model
        // breaks the OpenAI uniformity that's true for Anthropic.
        val resolver = PerModelCompactionThreshold.fromRegistry(registryOf(openai()))
        for (id in listOf("gpt-4o", "gpt-4o-mini")) {
            assertEquals(
                108_800,
                resolver(ModelRef("openai", id)),
                "OpenAI '$id' threshold MUST be 108_800 (128k × 0.85)",
            )
        }
    }

    @Test fun openaiGpt41ResolvesTo850_000Threshold() = runTest {
        // gpt-4.1 advertises 1M context — 850k threshold. Pinned
        // separately because the long-context window is the
        // single most cost-impactful drift surface (drifting
        // 1M → 200k silently caps the agent's working context
        // at 1/5 of what users paid for in the model selection).
        val resolver = PerModelCompactionThreshold.fromRegistry(registryOf(openai()))
        assertEquals(
            850_000,
            resolver(ModelRef("openai", "gpt-4.1")),
            "OpenAI 'gpt-4.1' threshold MUST be 850_000 (1M × 0.85; long-context tier)",
        )
    }

    // ── Gemini: largest in-tree context windows ───────────

    @Test fun geminiPro25ResolvesTo1_700_000Threshold() = runTest {
        // Marquee long-context pin: gemini-2.5-pro at 2M is the
        // biggest context window in the registry. Threshold of
        // 1.7M means an agent can absorb very long inputs before
        // compacting — drift here matters a LOT for cost and
        // for users who picked gemini specifically for the 2M
        // capacity.
        val resolver = PerModelCompactionThreshold.fromRegistry(registryOf(gemini()))
        assertEquals(
            1_700_000,
            resolver(ModelRef("gemini", "gemini-2.5-pro")),
            "Gemini 'gemini-2.5-pro' threshold MUST be 1_700_000 (2M × 0.85; the largest " +
                "context window in the registry, drift here = visible UX regression)",
        )
    }

    @Test fun gemini25FlashAnd20FlashResolveTo850kThreshold() = runTest {
        // Both Flash variants advertise 1M context → 850k
        // threshold. Same value as gpt-4.1 — pinned separately
        // so cross-provider 1M-tier movements both surface.
        val resolver = PerModelCompactionThreshold.fromRegistry(registryOf(gemini()))
        for (id in listOf("gemini-2.5-flash", "gemini-2.0-flash")) {
            assertEquals(
                850_000,
                resolver(ModelRef("gemini", id)),
                "Gemini '$id' threshold MUST be 850_000 (1M × 0.85)",
            )
        }
    }

    // ── Cross-provider: full registry composition ─────────

    @Test fun fullRegistryAcrossThreeProvidersResolvesAllNineModels() = runTest {
        // Tally pin: with all 3 providers wired (the production
        // composition), every one of the 9 listModels entries
        // resolves to a non-fallback threshold. If any future
        // refactor breaks `fromRegistry`'s aggregation logic
        // (e.g. dropping a provider's models), we catch it here
        // as one or more entries falling through to 120k
        // fallback instead of their proper computed values.
        val resolver = PerModelCompactionThreshold.fromRegistry(
            registryOf(anthropic(), openai(), gemini()),
        )
        val expected = mapOf(
            ModelRef("anthropic", "claude-opus-4-7") to 170_000,
            ModelRef("anthropic", "claude-sonnet-4-6") to 170_000,
            ModelRef("anthropic", "claude-haiku-4-5") to 170_000,
            ModelRef("openai", "gpt-4o") to 108_800,
            ModelRef("openai", "gpt-4o-mini") to 108_800,
            ModelRef("openai", "gpt-4.1") to 850_000,
            ModelRef("gemini", "gemini-2.5-pro") to 1_700_000,
            ModelRef("gemini", "gemini-2.5-flash") to 850_000,
            ModelRef("gemini", "gemini-2.0-flash") to 850_000,
        )
        for ((ref, threshold) in expected) {
            assertEquals(
                threshold,
                resolver(ref),
                "${ref.providerId}/${ref.modelId} MUST resolve to $threshold (cross-provider " +
                    "registry composition pin)",
            )
        }
    }

    @Test fun fallbackFiresFor120kOnWrongProviderId() = runTest {
        // Cross-provider isolation pin: model id collision
        // doesn't cause the wrong window to be picked up.
        // gpt-4.1 IS a real OpenAi model id. Asking for it
        // under "gemini" providerId MUST fall through to
        // fallback (120k), NOT silently match OpenAI's 850k
        // threshold. Catches a refactor that accidentally
        // collapses the (providerId, modelId) key to just
        // modelId.
        val resolver = PerModelCompactionThreshold.fromRegistry(
            registryOf(anthropic(), openai(), gemini()),
        )
        assertEquals(
            DEFAULT_COMPACTION_TOKEN_THRESHOLD,
            resolver(ModelRef("gemini", "gpt-4.1")),
            "Wrong-provider lookup MUST fall through to fallback (120k), NOT match the " +
                "OpenAi gpt-4.1 entry — catches collapsed-key drift",
        )
    }

    @Test fun fallbackFiresOn120kForUnknownModelOnRealProvider() = runTest {
        // Sister isolation pin: a recognised provider id with
        // an unknown model id falls through to fallback. The
        // 200k Anthropic Opus IS in the registry; asking for
        // a not-yet-released "claude-opus-5" surfaces as
        // fallback. Drift to e.g. matching the closest-name
        // model would be a major correctness regression.
        val resolver = PerModelCompactionThreshold.fromRegistry(
            registryOf(anthropic(), openai(), gemini()),
        )
        assertEquals(
            DEFAULT_COMPACTION_TOKEN_THRESHOLD,
            resolver(ModelRef("anthropic", "claude-opus-5")),
            "Unknown model id on a known provider MUST fall through to fallback",
        )
    }

    // ── Constants pin (drift-protective floor) ────────────

    @Test fun ratioAndFallbackConstantsAreLoadBearing() = runTest {
        // Direct constant pins for the two values multiplied
        // through every threshold above. If either drifts, it
        // shifts every model's threshold AT THE SAME TIME —
        // the per-model tests above all go red simultaneously,
        // but this test surfaces the root cause as one explicit
        // failure rather than 9 derived ones.
        assertEquals(
            0.85,
            DEFAULT_COMPACTION_THRESHOLD_RATIO,
            "DEFAULT_COMPACTION_THRESHOLD_RATIO MUST be 0.85 (OpenCode parity; drift = " +
                "every model compacts at a different cadence than the docs say)",
        )
        assertEquals(
            120_000,
            DEFAULT_COMPACTION_TOKEN_THRESHOLD,
            "DEFAULT_COMPACTION_TOKEN_THRESHOLD MUST be 120_000 (legacy Agent default; " +
                "drift = unknown models silently get a different headroom)",
        )
    }
}
