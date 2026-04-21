package io.talevia.core.tool.builtin.provider

import io.talevia.core.permission.PermissionSpec
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Fetch the model catalog of one registered LLM provider — HTTP-hitting
 * complement to [ListProvidersTool]. `list_providers` enumerates which
 * providers are wired; this tool answers "what models can I pick from
 * on <providerId>?" by calling `LlmProvider.listModels()`, which hits
 * the provider's public `/models` endpoint (or equivalent).
 *
 * Output returns structural capabilities per model — `contextWindow`,
 * `supportsTools`, `supportsThinking`, `supportsImages` — so the agent
 * can make cost / feature tradeoffs without a second provider call.
 *
 * Error handling: provider HTTP calls fail (network hiccup, revoked
 * API key, rate limit). Rather than bubble up a raw exception, the
 * tool catches and surfaces an `error` field; `models` is empty in
 * that case. This matches the `web_fetch` / `web_search` convention
 * of "external-call tools return structured error surface, not
 * exception stacks."
 *
 * Unknown providerId fails loudly (not an external-call failure —
 * the caller typed the wrong id).
 *
 * Permission: reuses `provider.read`. Provider model listings are
 * typically unmetered metadata endpoints, not inference calls, so
 * the silent-default is safe. Operators who want a prompt can flip
 * the rule locally.
 */
class ListProviderModelsTool(
    private val providers: ProviderRegistry,
) : Tool<ListProviderModelsTool.Input, ListProviderModelsTool.Output> {

    @Serializable data class Input(
        val providerId: String,
    )

    @Serializable data class ModelSummary(
        val id: String,
        val name: String,
        val contextWindow: Int,
        val supportsTools: Boolean,
        val supportsThinking: Boolean,
        val supportsImages: Boolean,
    )

    @Serializable data class Output(
        val providerId: String,
        val isDefault: Boolean,
        val modelCount: Int,
        val models: List<ModelSummary>,
        /** Non-null when the HTTP call to the provider failed. Models is empty. */
        val error: String? = null,
    )

    override val id: String = "list_provider_models"
    override val helpText: String =
        "Fetch the model catalog of one LLM provider (HTTP call to the provider's /models endpoint). " +
            "Returns model id, display name, context window, tool / thinking / image support per model. " +
            "Unknown providerId fails loudly; provider-side failures (network / auth / rate-limit) are " +
            "reported via the `error` field with an empty models list. For the list of configured " +
            "providers, call list_providers first."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("provider.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("providerId") {
                put("type", "string")
                put("description", "Provider id from list_providers (e.g. anthropic / openai / gemini).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("providerId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val provider = providers.get(input.providerId)
            ?: error(
                "Provider '${input.providerId}' is not registered. Call list_providers to see " +
                    "valid ids.",
            )
        val isDefault = providers.default?.id == provider.id

        val attempt = runCatching { provider.listModels() }
        val error = attempt.exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "unknown error" }
        val rawModels = attempt.getOrNull().orEmpty()

        val models = rawModels.map { info ->
            ModelSummary(
                id = info.id,
                name = info.name,
                contextWindow = info.contextWindow,
                supportsTools = info.supportsTools,
                supportsThinking = info.supportsThinking,
                supportsImages = info.supportsImages,
            )
        }

        val summary = when {
            error != null ->
                "Failed to list models for ${provider.id}: $error"
            models.isEmpty() ->
                "Provider ${provider.id} returned no models (provider's listModels may not be implemented yet)."
            else ->
                "${models.size} model(s) on ${provider.id}: " +
                    models.take(5).joinToString(", ") { it.id } +
                    if (models.size > 5) ", …" else ""
        }
        return ToolResult(
            title = "list models on ${provider.id} (${models.size})",
            outputForLlm = summary,
            data = Output(
                providerId = provider.id,
                isDefault = isDefault,
                modelCount = models.size,
                models = models,
                error = error,
            ),
        )
    }
}
