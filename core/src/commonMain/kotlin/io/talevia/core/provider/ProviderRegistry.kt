package io.talevia.core.provider

import io.ktor.client.HttpClient
import io.talevia.core.platform.SecretStore
import io.talevia.core.provider.anthropic.AnthropicProvider
import io.talevia.core.provider.gemini.GeminiProvider
import io.talevia.core.provider.openai.OpenAiProvider
import io.talevia.core.provider.openai.codex.OpenAiCodexCredentialProviderImpl
import io.talevia.core.provider.openai.codex.OpenAiCodexCredentialStore
import io.talevia.core.provider.openai.codex.OpenAiCodexProvider
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
                add(
                    OpenAiProvider(
                        httpClient,
                        apiKey = it,
                        tpmThrottle = openaiThrottleFrom(env),
                    ),
                )
            }
            (env["GEMINI_API_KEY"] ?: env["GOOGLE_API_KEY"])?.takeIf { it.isNotBlank() }?.let {
                add(GeminiProvider(httpClient, apiKey = it))
            }
        }

        /**
         * Construct a [TpmThrottle] from env vars when the org's OpenAI TPM limit is
         * declared — returns null otherwise, leaving the provider in its original
         * fire-and-backoff mode. Mostly we read the limit directly
         * (`OPENAI_TPM_LIMIT=200000`); the buffer ratio knob is available for
         * accounts with known accounting drift.
         */
        private fun openaiThrottleFrom(env: Map<String, String>): io.talevia.core.provider.TpmThrottle? {
            val limit = env["OPENAI_TPM_LIMIT"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val buffer = env["OPENAI_TPM_BUFFER_RATIO"]?.toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: 0.85
            return io.talevia.core.provider.TpmThrottle(tpmLimit = limit, bufferRatio = buffer)
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
        suspend fun addSecretStore(
            httpClient: HttpClient,
            secrets: SecretStore,
            env: Map<String, String> = emptyMap(),
        ): Builder = apply {
            secrets.get(SecretKeys.ANTHROPIC)?.takeIf { it.isNotBlank() }?.let {
                add(AnthropicProvider(httpClient, apiKey = it))
            }
            secrets.get(SecretKeys.OPENAI)?.takeIf { it.isNotBlank() }?.let {
                add(
                    OpenAiProvider(
                        httpClient,
                        apiKey = it,
                        tpmThrottle = openaiThrottleFrom(env),
                    ),
                )
            }
            (secrets.get(SecretKeys.GEMINI) ?: secrets.get(SecretKeys.GOOGLE))?.takeIf { it.isNotBlank() }?.let {
                add(GeminiProvider(httpClient, apiKey = it))
            }
        }

        /**
         * Register the `openai-codex` provider when the user has signed in via
         * the OAuth flow (credentials present in [credentialStore]). Skipped
         * silently when the user is not signed in.
         */
        suspend fun addOpenAiCodex(
            httpClient: HttpClient,
            credentialStore: OpenAiCodexCredentialStore,
        ): Builder = apply {
            if (credentialStore.load() == null) return@apply
            add(
                OpenAiCodexProvider(
                    httpClient = httpClient,
                    credentials = OpenAiCodexCredentialProviderImpl(httpClient, credentialStore),
                ),
            )
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
        /**
         * The `openai-codex` provider does not store its credentials in
         * [SecretStore] (they're a structured JSON blob managed by
         * `OpenAiCodexCredentialStore`); the constant is kept here for callers
         * that want a stable provider id reference.
         */
        const val OPENAI_CODEX = "openai-codex"
    }
}
