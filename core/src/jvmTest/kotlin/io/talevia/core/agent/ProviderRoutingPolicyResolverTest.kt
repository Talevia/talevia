package io.talevia.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Coverage for [resolveProviderRoutingPolicy] — the env-var → policy
 * mapping used at container boot.
 */
class ProviderRoutingPolicyResolverTest {

    @Test fun nullDefaultsToRegistryOrder() {
        // Null is the "no env var set" case — must preserve pre-policy
        // behaviour so an existing deployment without the flag isn't
        // silently re-routed.
        assertSame(RegistryOrderPolicy, resolveProviderRoutingPolicy(null))
    }

    @Test fun emptyAndBlankCollapseToRegistryOrder() {
        assertSame(RegistryOrderPolicy, resolveProviderRoutingPolicy(""))
        assertSame(RegistryOrderPolicy, resolveProviderRoutingPolicy("   "))
    }

    @Test fun cheapestFirstAliasesAllResolve() {
        // All three aliases must hit CheapestFirstPolicy so operators
        // don't have to remember an exact spelling.
        assertSame(CheapestFirstPolicy, resolveProviderRoutingPolicy("cheapest-first"))
        assertSame(CheapestFirstPolicy, resolveProviderRoutingPolicy("cheapest"))
        assertSame(CheapestFirstPolicy, resolveProviderRoutingPolicy("cost-aware"))
    }

    @Test fun caseInsensitiveAndTrimmed() {
        assertSame(CheapestFirstPolicy, resolveProviderRoutingPolicy("CHEAPEST-FIRST"))
        assertSame(CheapestFirstPolicy, resolveProviderRoutingPolicy("  Cheapest  "))
        assertSame(RegistryOrderPolicy, resolveProviderRoutingPolicy("REGISTRY-ORDER"))
    }

    @Test fun explicitRegistryAliasesResolve() {
        assertSame(RegistryOrderPolicy, resolveProviderRoutingPolicy("default"))
        assertSame(RegistryOrderPolicy, resolveProviderRoutingPolicy("registry-order"))
        assertSame(RegistryOrderPolicy, resolveProviderRoutingPolicy("registry"))
    }

    @Test fun unknownValueFailsLoud() {
        // Misspelled flags must fail at boot rather than degrade
        // silently to the default — a typo on a routing knob would
        // otherwise hide until the first fallback fires in production.
        val ex = assertFailsWith<IllegalStateException> {
            resolveProviderRoutingPolicy("most-expensive-first")
        }
        assertTrue("most-expensive-first" in ex.message.orEmpty(), ex.message)
        assertTrue("accepted:" in ex.message.orEmpty(), "error must list legal values; got: ${ex.message}")
    }

    @Test fun resolvedPolicyIsActuallyUsableByAgent() {
        // Smoke-check that the returned object exposes the
        // ProviderRoutingPolicy contract, not just the right type at
        // compile time.
        val policy = resolveProviderRoutingPolicy("cheapest-first")
        // empty fallback → empty result (pure function shape check).
        assertEquals(emptyList(), policy.orderFallbacks(stubProvider("primary"), emptyList()))
    }

    private fun stubProvider(idValue: String) = object : io.talevia.core.provider.LlmProvider {
        override val id: String = idValue
        override suspend fun listModels(): List<io.talevia.core.provider.ModelInfo> = emptyList()
        override fun stream(request: io.talevia.core.provider.LlmRequest): kotlinx.coroutines.flow.Flow<io.talevia.core.provider.LlmEvent> = kotlinx.coroutines.flow.emptyFlow()
    }
}
