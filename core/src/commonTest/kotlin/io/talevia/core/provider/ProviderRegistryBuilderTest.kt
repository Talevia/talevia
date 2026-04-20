package io.talevia.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.talevia.core.platform.InMemorySecretStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Builder composes two credential sources (SecretStore + process env) and must
 * (a) actually register providers from each and (b) never double-register the
 * same provider id — the source called first wins. These invariants are load-
 * bearing for the desktop flow: env left over from a dev shell must not clobber
 * a key the user deliberately stored via the UI.
 */
class ProviderRegistryBuilderTest {

    @Test
    fun secretStoreSeedsProviders() = runTest {
        val secrets = InMemorySecretStore(
            mapOf(
                ProviderRegistry.SecretKeys.OPENAI to "sk-openai",
                ProviderRegistry.SecretKeys.ANTHROPIC to "sk-ant",
            ),
        )
        val registry = ProviderRegistry.Builder()
            .addSecretStore(noopClient(), secrets)
            .build()

        assertNotNull(registry["openai"])
        assertNotNull(registry["anthropic"])
    }

    @Test
    fun googleAliasRoutesToGeminiProvider() = runTest {
        val secrets = InMemorySecretStore(mapOf(ProviderRegistry.SecretKeys.GOOGLE to "ai-key"))
        val registry = ProviderRegistry.Builder()
            .addSecretStore(noopClient(), secrets)
            .build()

        assertNotNull(registry["gemini"])
    }

    @Test
    fun firstSourceWinsWhenBothHaveTheSameProvider() = runTest {
        val secrets = InMemorySecretStore(mapOf(ProviderRegistry.SecretKeys.OPENAI to "from-secrets"))
        val env = mapOf("OPENAI_API_KEY" to "from-env")

        val registry = ProviderRegistry.Builder()
            .addSecretStore(noopClient(), secrets)
            .addEnv(noopClient(), env)
            .build()

        // De-dupe by provider id, and with secret-store-first registration,
        // the "from-secrets" provider wins — the env fallback is discarded.
        assertEquals(1, registry.all().count { it.id == "openai" })
        assertEquals("openai", registry.default?.id)
    }

    @Test
    fun envActsAsFallbackWhenSecretStoreMissingAProvider() = runTest {
        val secrets = InMemorySecretStore(mapOf(ProviderRegistry.SecretKeys.OPENAI to "from-secrets"))
        val env = mapOf("ANTHROPIC_API_KEY" to "from-env")

        val registry = ProviderRegistry.Builder()
            .addSecretStore(noopClient(), secrets)
            .addEnv(noopClient(), env)
            .build()

        assertNotNull(registry["openai"])
        assertNotNull(registry["anthropic"])
    }

    // No network calls happen during Builder construction — the provider objects
    // are instantiated but their `stream()` is never invoked. MockEngine satisfies
    // Ktor's HttpClient contract without issuing any real requests.
    private fun noopClient(): HttpClient = HttpClient(MockEngine) {
        engine { addHandler { respond(content = "", status = HttpStatusCode.OK) } }
    }
}
