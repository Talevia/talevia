package io.talevia.core.provider

import io.ktor.client.HttpClient
import io.talevia.core.platform.SecretStore
import io.talevia.core.provider.anthropic.AnthropicProvider
import io.talevia.core.provider.gemini.GeminiProvider
import io.talevia.core.provider.openai.OpenAiProvider
import io.talevia.core.session.ModelRef

/**
 * Holds the set of providers known to the runtime. Built once at app start;
 * the Agent loop resolves provider per [ModelRef.providerId] for every turn so
 * different turns / tools can pick different models freely.
 *
 * `defaultProvider` is whichever provider was registered first that has a key —
 * the composition root decides priority order via [Builder.add]. Sources
 * ([addSecretStore], [addEnv]) are additive and de-duplicate by provider id;
 * the first source to supply a given provider wins, so later calls act as
 * fallbacks.
 */
class ProviderRegistry(
    private val byId: Map<String, LlmProvider>,
    val default: LlmProvider?,
) {
    operator fun get(providerId: String): LlmProvider? = byId[providerId]
    fun all(): List<LlmProvider> = byId.values.toList()

    class Builder {
        private val list = mutableListOf<LlmProvider>()

        /** Register a provider. No-op if one with the same [LlmProvider.id] is already present. */
        fun add(provider: LlmProvider): Builder = apply {
            if (list.none { it.id == provider.id }) list += provider
        }

        /**
         * Convenience: try every supported provider against env-style key map.
         * Skips any provider whose API key is null/blank, and (per [add])
         * any provider already registered.
         */
        fun addEnv(httpClient: HttpClient, env: Map<String, String>): Builder = apply {
            env["ANTHROPIC_API_KEY"]?.takeIf { it.isNotBlank() }?.let {
                add(AnthropicProvider(httpClient, apiKey = it))
            }
            env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }?.let {
                add(OpenAiProvider(httpClient, apiKey = it))
            }
            (env["GEMINI_API_KEY"] ?: env["GOOGLE_API_KEY"])?.takeIf { it.isNotBlank() }?.let {
                add(GeminiProvider(httpClient, apiKey = it))
            }
        }

        /**
         * Pull provider keys from a [SecretStore], using the convention
         * documented on [SecretStore] — keyed by `providerId`. Each entry is
         * routed through [add], so already-registered providers are not
         * overwritten (whichever source was called first wins).
         *
         * Call this before [addEnv] if you want persisted / UI-entered keys to
         * take precedence over the process environment; call it after to let
         * env act as the authoritative source and secrets act as fallback.
         */
        suspend fun addSecretStore(httpClient: HttpClient, secrets: SecretStore): Builder = apply {
            secrets.get(SecretKeys.ANTHROPIC)?.takeIf { it.isNotBlank() }?.let {
                add(AnthropicProvider(httpClient, apiKey = it))
            }
            secrets.get(SecretKeys.OPENAI)?.takeIf { it.isNotBlank() }?.let {
                add(OpenAiProvider(httpClient, apiKey = it))
            }
            (secrets.get(SecretKeys.GEMINI) ?: secrets.get(SecretKeys.GOOGLE))?.takeIf { it.isNotBlank() }?.let {
                add(GeminiProvider(httpClient, apiKey = it))
            }
        }

        fun build(): ProviderRegistry {
            val byId = list.associateBy { it.id }
            return ProviderRegistry(byId, default = list.firstOrNull())
        }
    }

    /**
     * Canonical [SecretStore] keys for LLM provider credentials. Composition
     * roots that want to seed secrets (e.g. from a "paste your key" UI) should
     * use these names so [Builder.addSecretStore] picks them up.
     */
    object SecretKeys {
        const val ANTHROPIC = "anthropic"
        const val OPENAI = "openai"
        const val GEMINI = "gemini"
        /** Alias some users prefer; falls back to [GEMINI] if both present. */
        const val GOOGLE = "google"
    }
}
