package io.talevia.core.agent

import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage for [ProviderRoutingPolicy] — M2 exit summary §3.1 follow-up
 * #3: cost-aware fallback ordering. Edges (§3a #9): registry-order
 * default preserves legacy semantics, cheapest-first ranks priced
 * providers by min (input + output) per-1k-token cost, unpriced
 * providers land at the tail with deterministic tie-break, primary is
 * never reintroduced into the returned list.
 *
 * Uses the [LlmPricing] 2026-04 snapshot directly — re-pricing PRs will
 * need to re-read the test's expected order along with the numeric
 * table, which is the whole point of keeping both in one place.
 */
class ProviderRoutingPolicyTest {

    private class FakeProvider(override val id: String) : LlmProvider {
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flowOf()
    }

    private val anthropic = FakeProvider("anthropic")
    private val openai = FakeProvider("openai")
    private val gemini = FakeProvider("gemini")
    private val unpricedA = FakeProvider("unpriced-a")
    private val unpricedB = FakeProvider("unpriced-b")

    // ---- RegistryOrderPolicy ----

    @Test fun registryOrderPreservesInputOrder() {
        val result = RegistryOrderPolicy.orderFallbacks(
            primary = anthropic,
            fallbacks = listOf(openai, gemini, unpricedA),
        )
        assertEquals(listOf(openai, gemini, unpricedA), result)
    }

    @Test fun registryOrderDropsPrimaryIfPresent() {
        val result = RegistryOrderPolicy.orderFallbacks(
            primary = anthropic,
            fallbacks = listOf(anthropic, openai, gemini),
        )
        assertEquals(listOf(openai, gemini), result)
    }

    @Test fun registryOrderOnEmptyReturnsEmpty() {
        val result = RegistryOrderPolicy.orderFallbacks(anthropic, emptyList())
        assertEquals(emptyList(), result)
    }

    @Test fun defaultPolicyEqualsRegistryOrder() {
        // ProviderRoutingPolicy.Default is the composition-root no-op —
        // adding fallback ordering to Agent must not silently shuffle
        // containers that passed no explicit policy.
        val default = ProviderRoutingPolicy.Default
        assertEquals(RegistryOrderPolicy, default)
    }

    // ---- CheapestFirstPolicy ----

    @Test fun cheapestFirstRanksByMinComboCost() {
        // From LlmPricing (2026-04 snapshot) the per-provider cheapest
        // (input + output) cents / 1k tokens:
        //   openai    — gpt-4o-mini at 0.015 + 0.06 = 0.075
        //   gemini    — gemini-2.5-flash at 0.0075 + 0.03 = 0.0375
        //   anthropic — claude-haiku-4-5 at 0.1 + 0.5 = 0.6
        // Expected ascending: gemini, openai, anthropic.
        val result = CheapestFirstPolicy.orderFallbacks(
            primary = FakeProvider("primary-only"),
            fallbacks = listOf(anthropic, openai, gemini),
        )
        assertEquals(listOf(gemini, openai, anthropic), result)
    }

    @Test fun cheapestFirstPutsUnpricedLast() {
        // Unpriced providers sort after all priced providers and keep
        // deterministic order among themselves (tie-break = provider id
        // alphabetic → unpriced-a before unpriced-b).
        val result = CheapestFirstPolicy.orderFallbacks(
            primary = FakeProvider("primary-only"),
            fallbacks = listOf(unpricedB, anthropic, unpricedA, gemini),
        )
        assertEquals(listOf(gemini, anthropic, unpricedA, unpricedB), result)
    }

    @Test fun cheapestFirstFiltersPrimary() {
        val result = CheapestFirstPolicy.orderFallbacks(
            primary = gemini,
            fallbacks = listOf(gemini, openai, anthropic),
        )
        // Primary removed; rest ordered by cost (openai < anthropic).
        assertEquals(listOf(openai, anthropic), result)
    }

    @Test fun cheapestFirstOnEmptyReturnsEmpty() {
        assertEquals(
            emptyList(),
            CheapestFirstPolicy.orderFallbacks(anthropic, emptyList()),
        )
    }

    @Test fun cheapestFirstIsDeterministic() {
        // Same inputs → same output across repeated calls (sanity check
        // that ordering doesn't leak iteration-order randomness from the
        // underlying groupBy over LlmPricing.all()).
        val input = listOf(anthropic, openai, gemini, unpricedA, unpricedB)
        val first = CheapestFirstPolicy.orderFallbacks(FakeProvider("p"), input)
        val second = CheapestFirstPolicy.orderFallbacks(FakeProvider("p"), input)
        assertEquals(first, second)
    }
}
