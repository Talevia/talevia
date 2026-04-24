package io.talevia.core.compaction

import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.ModelRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Coverage for [CompactionBudget] + [PerModelCompactionBudget]. Mirrors
 * the coverage of [PerModelCompactionThreshold] (sibling in this package)
 * — the two resolvers share the same "resolve via ModelInfo / fall back
 * on unknown" pattern so parallel tests keep the invariants matched.
 */
class CompactionBudgetTest {

    @Test fun defaultMatchesLegacyNumbers() {
        val d = CompactionBudget.DEFAULT
        assertEquals(2, d.protectUserTurns, "legacy protectUserTurns")
        assertEquals(40_000, d.pruneKeepTokens, "legacy pruneKeepTokens (40k)")
    }

    @Test fun budgetConstructorRejectsInvalidValues() {
        assertFailsWith<IllegalArgumentException> {
            CompactionBudget(protectUserTurns = 0, pruneKeepTokens = 40_000)
        }
        assertFailsWith<IllegalArgumentException> {
            CompactionBudget(protectUserTurns = 2, pruneKeepTokens = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            CompactionBudget(protectUserTurns = 2, pruneKeepTokens = -1)
        }
    }

    @Test fun perModelBudgetScalesByContextWindow() {
        val resolver = PerModelCompactionBudget(
            contextWindowByRef = mapOf(
                "anthropic" to "claude-sonnet-4-6" to 200_000,
                "openai" to "gpt-4o" to 128_000,
            ),
            keepRatio = 0.30,
        )
        val sonnet = resolver(ModelRef("anthropic", "claude-sonnet-4-6"))
        val gpt = resolver(ModelRef("openai", "gpt-4o"))
        assertEquals(60_000, sonnet.pruneKeepTokens, "200k × 0.30")
        assertEquals(38_400, gpt.pruneKeepTokens, "128k × 0.30")
        assertEquals(CompactionBudget.DEFAULT.protectUserTurns, sonnet.protectUserTurns)
    }

    @Test fun perModelBudgetFallsBackOnUnknownModel() {
        val resolver = PerModelCompactionBudget(
            contextWindowByRef = mapOf("anthropic" to "claude-sonnet-4-6" to 200_000),
        )
        val unknown = resolver(ModelRef("unknown-provider", "mystery-7b"))
        assertSame(CompactionBudget.DEFAULT, unknown, "unknown model must fall back to DEFAULT")
    }

    @Test fun perModelBudgetFallsBackOnZeroContextWindow() {
        // A provider that reported contextWindow=0 is broken metadata;
        // scaling would produce a zero budget that fails the
        // CompactionBudget init check. Fall back instead.
        val resolver = PerModelCompactionBudget(
            contextWindowByRef = mapOf("silly" to "tiny" to 0),
        )
        val budget = resolver(ModelRef("silly", "tiny"))
        assertSame(CompactionBudget.DEFAULT, budget)
    }

    @Test fun fromRegistryWiresEveryProviderModel() = runTest {
        val anth = FakeProvider(
            "anthropic",
            listOf(
                ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", contextWindow = 200_000, supportsTools = true),
                ModelInfo("claude-haiku-4-5", "Claude Haiku 4.5", contextWindow = 100_000, supportsTools = true),
            ),
        )
        val oai = FakeProvider(
            "openai",
            listOf(ModelInfo("gpt-4o", "GPT-4o", contextWindow = 128_000, supportsTools = true)),
        )
        val registry = ProviderRegistry(mapOf("anthropic" to anth, "openai" to oai), default = anth)
        val resolver = PerModelCompactionBudget.fromRegistry(registry, keepRatio = 0.30)
        assertEquals(60_000, resolver(ModelRef("anthropic", "claude-sonnet-4-6")).pruneKeepTokens)
        assertEquals(30_000, resolver(ModelRef("anthropic", "claude-haiku-4-5")).pruneKeepTokens)
        assertEquals(38_400, resolver(ModelRef("openai", "gpt-4o")).pruneKeepTokens)
        assertSame(CompactionBudget.DEFAULT, resolver(ModelRef("google", "gemini-2-pro")))
    }

    private class FakeProvider(
        override val id: String,
        private val models: List<ModelInfo>,
    ) : LlmProvider {
        override suspend fun listModels(): List<ModelInfo> = models
        override fun stream(request: LlmRequest): Flow<LlmEvent> = emptyFlow()
    }
}
