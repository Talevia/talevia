package io.talevia.core.provider

import io.ktor.client.HttpClient
import io.talevia.core.provider.anthropic.AnthropicProvider
import io.talevia.core.provider.openai.OpenAiProvider
import io.talevia.core.session.ModelRef

/**
 * Holds the set of providers known to the runtime. Built once at app start;
 * the Agent loop resolves provider per [ModelRef.providerId] for every turn so
 * different turns / tools can pick different models freely.
 *
 * `defaultProvider` is whichever provider was registered first that has a key —
 * the composition root decides priority order via [Builder.add].
 */
class ProviderRegistry(
    private val byId: Map<String, LlmProvider>,
    val default: LlmProvider?,
) {
    operator fun get(providerId: String): LlmProvider? = byId[providerId]
    fun all(): List<LlmProvider> = byId.values.toList()

    class Builder {
        private val list = mutableListOf<LlmProvider>()

        fun add(provider: LlmProvider): Builder = apply { list += provider }

        /**
         * Convenience: try every supported provider against env-style key map.
         * Skips any provider whose API key is null/blank.
         */
        fun addEnv(httpClient: HttpClient, env: Map<String, String>): Builder = apply {
            env["ANTHROPIC_API_KEY"]?.takeIf { it.isNotBlank() }?.let {
                add(AnthropicProvider(httpClient, apiKey = it))
            }
            env["OPENAI_API_KEY"]?.takeIf { it.isNotBlank() }?.let {
                add(OpenAiProvider(httpClient, apiKey = it))
            }
        }

        fun build(): ProviderRegistry {
            val byId = list.associateBy { it.id }
            return ProviderRegistry(byId, default = list.firstOrNull())
        }
    }
}
