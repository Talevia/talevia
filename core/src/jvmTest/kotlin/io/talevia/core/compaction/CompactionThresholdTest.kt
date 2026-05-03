package io.talevia.core.compaction

import io.talevia.core.session.ModelRef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests for [PerModelCompactionThreshold] — the per-model
 * auto-compaction trigger resolver. Cycle 78 audit found this class
 * had no direct test (cycle 76 audit method); the agent loop calls
 * `(ref) -> Int` every turn to know whether to fire compaction, so a
 * regression here would silently change compaction cadence for every
 * model in the registry.
 */
class CompactionThresholdTest {

    @Test fun knownModelReturnsContextWindowTimesRatio() {
        // 0.85 × 200_000 = 170_000 (Claude 200k context; OpenCode-aligned).
        // 0.85 × 128_000 = 108_800 (typical mid-tier model).
        val t = PerModelCompactionThreshold(
            contextWindowByRef = mapOf(
                "anthropic" to "claude-opus-4-7" to 200_000,
                "openai" to "gpt-5" to 128_000,
            ),
        )
        assertEquals(170_000, t(ModelRef("anthropic", "claude-opus-4-7")))
        assertEquals(108_800, t(ModelRef("openai", "gpt-5")))
    }

    @Test fun unknownProviderFallsBackToDefault() {
        // Unknown provider id — return DEFAULT_COMPACTION_TOKEN_THRESHOLD
        // (120_000) per the kdoc's "legacy behavior, so nothing regresses
        // when a model is unrecognised" contract.
        val t = PerModelCompactionThreshold(
            contextWindowByRef = mapOf("anthropic" to "claude-opus-4-7" to 200_000),
        )
        assertEquals(
            DEFAULT_COMPACTION_TOKEN_THRESHOLD,
            t(ModelRef("unknown-provider", "any-model")),
        )
    }

    @Test fun unknownModelOnKnownProviderFallsBackToDefault() {
        // Provider exists in the map but model id doesn't — same fallback.
        // Avoids false-confidence: an unknown model on a known provider
        // doesn't get the provider's first model's window.
        val t = PerModelCompactionThreshold(
            contextWindowByRef = mapOf("anthropic" to "claude-opus-4-7" to 200_000),
        )
        assertEquals(
            DEFAULT_COMPACTION_TOKEN_THRESHOLD,
            t(ModelRef("anthropic", "future-model-x")),
        )
    }

    @Test fun customRatioIsApplied() {
        // Pin: ratio is configurable. A test setup might want 0.5 to
        // trigger compaction earlier; the resolver must honour it.
        val t = PerModelCompactionThreshold(
            contextWindowByRef = mapOf("p" to "m" to 100_000),
            ratio = 0.5,
        )
        assertEquals(50_000, t(ModelRef("p", "m")))
    }

    @Test fun customFallbackIsApplied() {
        // Pin: fallback is configurable independently. Server containers
        // might set a stricter fallback (e.g. 60k) for unknown models to
        // bound LLM context cost.
        val t = PerModelCompactionThreshold(
            contextWindowByRef = emptyMap(),
            fallback = 60_000,
        )
        assertEquals(60_000, t(ModelRef("unknown", "unknown")))
    }

    @Test fun emptyMapAlwaysReturnsFallback() {
        // Edge: a registry with zero known models (test rig that doesn't
        // wire any providers). Every lookup falls through to fallback.
        val t = PerModelCompactionThreshold(contextWindowByRef = emptyMap())
        assertEquals(
            DEFAULT_COMPACTION_TOKEN_THRESHOLD,
            t(ModelRef("any", "any")),
        )
    }

    @Test fun ratioRoundsDownToInt() {
        // 0.85 × 17 = 14.45 → toInt() truncates to 14, not rounds to 14.
        // Pin the truncation behaviour so a future "round to nearest"
        // refactor wouldn't silently change the threshold by 1.
        val t = PerModelCompactionThreshold(
            contextWindowByRef = mapOf("p" to "m" to 17),
        )
        assertEquals(14, t(ModelRef("p", "m")))
    }

    @Test fun providerAndModelKeyTuple() {
        // Pin: lookup is by (providerId, modelId) tuple, not modelId
        // alone. Two providers can ship the same model id (e.g. an
        // OpenAI-compatible third party) and the resolver must
        // discriminate.
        val t = PerModelCompactionThreshold(
            contextWindowByRef = mapOf(
                "openai" to "gpt-4" to 100_000,
                "third-party" to "gpt-4" to 50_000,
            ),
        )
        assertEquals(85_000, t(ModelRef("openai", "gpt-4")))
        assertEquals(42_500, t(ModelRef("third-party", "gpt-4")))
    }
}
