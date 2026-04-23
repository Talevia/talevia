package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.JsonConfig
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

@Serializable
data class ModelRow(
    val providerId: String,
    val modelId: String,
    val name: String,
    val contextWindow: Int,
    val supportsTools: Boolean,
    val supportsThinking: Boolean,
    val supportsImages: Boolean,
)

/**
 * `select=models` — fetch one provider's model catalog. Hits the provider's
 * HTTP `/models` endpoint; network / auth / rate-limit failures surface via
 * `Output.error` with empty rows, not exceptions.
 */
internal suspend fun runModelsQuery(
    providers: ProviderRegistry,
    providerId: String,
): ToolResult<ProviderQueryTool.Output> {
    val provider = providers.get(providerId)
        ?: error(
            "Provider '$providerId' is not registered. Call provider_query(select=providers) " +
                "to see valid ids.",
        )

    val attempt = runCatching { provider.listModels() }
    val errorMsg = attempt.exceptionOrNull()?.let { it.message ?: it::class.simpleName ?: "unknown error" }
    val raw = attempt.getOrNull().orEmpty()

    val rows = raw.map {
        ModelRow(
            providerId = provider.id,
            modelId = it.id,
            name = it.name,
            contextWindow = it.contextWindow,
            supportsTools = it.supportsTools,
            supportsThinking = it.supportsThinking,
            supportsImages = it.supportsImages,
        )
    }
    val encoded = JsonConfig.default.encodeToJsonElement(
        ListSerializer(ModelRow.serializer()),
        rows,
    ) as JsonArray

    val summary = when {
        errorMsg != null -> "Failed to list models for ${provider.id}: $errorMsg"
        rows.isEmpty() ->
            "Provider ${provider.id} returned no models (listModels may not be implemented yet)."
        else ->
            "${rows.size} model(s) on ${provider.id}: " +
                rows.take(5).joinToString(", ") { it.modelId } +
                if (rows.size > 5) ", …" else ""
    }

    return ToolResult(
        title = "provider_query models on ${provider.id} (${rows.size})",
        outputForLlm = summary,
        data = ProviderQueryTool.Output(
            select = ProviderQueryTool.SELECT_MODELS,
            total = rows.size,
            returned = rows.size,
            rows = encoded,
            error = errorMsg,
        ),
    )
}
