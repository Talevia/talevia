package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.JsonConfig
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

@Serializable
data class ProviderRow(
    val providerId: String,
    val isDefault: Boolean,
)

/**
 * `select=providers` — enumerate every LLM provider configured in this
 * runtime and mark the default. Pure local state, no HTTP.
 */
internal fun runProvidersQuery(providers: ProviderRegistry): ToolResult<ProviderQueryTool.Output> {
    val defaultId = providers.default?.id
    val rows = providers.all().map { ProviderRow(providerId = it.id, isDefault = it.id == defaultId) }
    val summary = when {
        rows.isEmpty() ->
            "No LLM providers configured in this runtime — set ANTHROPIC_API_KEY / " +
                "OPENAI_API_KEY / GEMINI_API_KEY in the environment or via SecretStore."
        rows.size == 1 -> "1 provider: ${rows.single().providerId} (default)."
        else -> {
            val names = rows.joinToString(", ") { s -> s.providerId + if (s.isDefault) "*" else "" }
            "${rows.size} providers: $names (default marked *)."
        }
    }
    val encoded = JsonConfig.default.encodeToJsonElement(
        ListSerializer(ProviderRow.serializer()),
        rows,
    ) as JsonArray
    return ToolResult(
        title = "provider_query providers (${rows.size})",
        outputForLlm = summary,
        data = ProviderQueryTool.Output(
            select = ProviderQueryTool.SELECT_PROVIDERS,
            total = rows.size,
            returned = rows.size,
            rows = encoded,
        ),
    )
}
