package io.talevia.core.compaction

import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.ModelRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for [PerModelCompactionThreshold] — the per-model auto-
 * compaction trigger resolver wired into [io.talevia.core.agent.Agent]
 * from each AppContainer. Edges (§3a #9): known model scales to
 * `contextWindow × ratio`, unknown model falls through to the provided
 * fallback, wrong providerId also falls through (no cross-provider
 * model-id collisions).
 */
class PerModelCompactionThresholdTest {

    private class FakeProvider(
        override val id: String,
        private val models: List<ModelInfo>,
    ) : LlmProvider {
        override suspend fun listModels(): List<ModelInfo> = models
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flowOf()
    }

    private fun registryOf(vararg providers: LlmProvider): ProviderRegistry {
        val builder = ProviderRegistry.Builder()
        providers.forEach { builder.add(it) }
        return builder.build()
    }

    @Test fun knownModelScalesByRatio() = runTest {
        val provider = FakeProvider(
            id = "fake",
            models = listOf(
                ModelInfo("big-model", "Big Model", contextWindow = 200_000, supportsTools = true),
                ModelInfo("small-model", "Small Model", contextWindow = 64_000, supportsTools = true),
            ),
        )
        val resolver = PerModelCompactionThreshold.fromRegistry(registryOf(provider), ratio = 0.85)

        assertEquals(170_000, resolver(ModelRef("fake", "big-model")))
        assertEquals(54_400, resolver(ModelRef("fake", "small-model")))
    }

    @Test fun unknownModelFallsThroughToDefault() = runTest {
        val provider = FakeProvider(
            id = "fake",
            models = listOf(
                ModelInfo("known", "Known", contextWindow = 100_000, supportsTools = true),
            ),
        )
        val resolver = PerModelCompactionThreshold.fromRegistry(registryOf(provider))

        // Provider wired, but the model id isn't in its listModels() output.
        assertEquals(
            DEFAULT_COMPACTION_TOKEN_THRESHOLD,
            resolver(ModelRef("fake", "never-heard-of-this")),
        )
    }

    @Test fun wrongProviderIdAlsoFallsThrough() = runTest {
        // Two providers share a model id — resolver must not cross-wire them.
        val providerA = FakeProvider(
            id = "a",
            models = listOf(ModelInfo("gpt-shared", "shared", contextWindow = 100_000, supportsTools = true)),
        )
        val providerB = FakeProvider(
            id = "b",
            models = listOf(ModelInfo("gpt-shared", "shared", contextWindow = 200_000, supportsTools = true)),
        )
        val resolver = PerModelCompactionThreshold.fromRegistry(registryOf(providerA, providerB))

        assertEquals(85_000, resolver(ModelRef("a", "gpt-shared")))
        assertEquals(170_000, resolver(ModelRef("b", "gpt-shared")))
        // Wrong providerId — no such (c, gpt-shared) pair.
        assertEquals(DEFAULT_COMPACTION_TOKEN_THRESHOLD, resolver(ModelRef("c", "gpt-shared")))
    }

    @Test fun emptyRegistryAlwaysReturnsFallback() = runTest {
        val resolver = PerModelCompactionThreshold.fromRegistry(registryOf())
        assertEquals(
            DEFAULT_COMPACTION_TOKEN_THRESHOLD,
            resolver(ModelRef("any", "any")),
        )
    }

    @Test fun customFallbackOverridesDefault() = runTest {
        val provider = FakeProvider(id = "p", models = emptyList())
        val resolver = PerModelCompactionThreshold.fromRegistry(
            registry = registryOf(provider),
            fallback = 99_999,
        )
        // Unknown model → custom fallback, not the library default.
        assertEquals(99_999, resolver(ModelRef("p", "nope")))
    }

    @Test fun ratioTruncatesToInt() = runTest {
        // 100_000 × 0.333 = 33_300.0 → truncated to 33_300. Guards against
        // accidentally rounding up (would cause off-by-one on tight tests).
        val provider = FakeProvider(
            id = "p",
            models = listOf(ModelInfo("m", "m", contextWindow = 100_000, supportsTools = true)),
        )
        val resolver = PerModelCompactionThreshold.fromRegistry(registryOf(provider), ratio = 0.333)
        assertEquals(33_300, resolver(ModelRef("p", "m")))
    }
}
