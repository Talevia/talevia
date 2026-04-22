package io.talevia.core.tool.builtin.provider

import io.talevia.core.JsonConfig
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Unified read-only query primitive over the [ProviderRegistry] — the provider-
 * lane counterpart of [io.talevia.core.tool.builtin.session.SessionQueryTool]
 * and [io.talevia.core.tool.builtin.project.ProjectQueryTool]. One tool spec
 * in the LLM's context instead of two list-* tools for what is structurally
 * the same "query the registry" verb.
 *
 * Replaces the deleted `list_providers` + `list_provider_models` pair
 * (debt-consolidate-provider-queries cycle). The two original tools were a
 * very thin slice over the same registry — the only substantive difference
 * was that one needed a `providerId` filter and hit the provider's HTTP
 * `/models` endpoint. Consolidating is cheap and mirrors the `project_query`
 * / `session_query` shape the LLM already knows.
 *
 * [Input.select] discriminates what to return:
 *  - `providers` — enumerate all configured LLM providers and which is default.
 *    No HTTP call. Lightweight local introspection.
 *  - `models` — fetch the model catalog of one provider. Requires
 *    `providerId`. Hits the provider's HTTP `/models` endpoint; failures
 *    surface via `errorByProvider[providerId]` instead of exceptions
 *    (matching `web_fetch` / `web_search` convention).
 *
 * Output is uniform: `{select, total, returned, rows}` where `rows` is a
 * [JsonArray] whose shape depends on `select`. Consumers decode with the
 * matching row serializer — `ProviderQueryTool.ProviderRow.serializer()` or
 * `ProviderQueryTool.ModelRow.serializer()`.
 */
class ProviderQueryTool(
    private val providers: ProviderRegistry,
) : Tool<ProviderQueryTool.Input, ProviderQueryTool.Output> {

    @Serializable data class Input(
        /** One of [SELECT_PROVIDERS] / [SELECT_MODELS] (case-insensitive). */
        val select: String,
        /** Required for [SELECT_MODELS]; rejected for [SELECT_PROVIDERS]. */
        val providerId: String? = null,
    )

    @Serializable data class Output(
        /** Echo of the (normalised) select used to produce [rows]. */
        val select: String,
        /** Count of matches (providers enumerated or models returned by the API). */
        val total: Int,
        /** Count of rows in [rows]; equals [total] — no offset / limit on this
         *  select yet (both selects are small enough that paging isn't needed). */
        val returned: Int,
        /** Select-specific row objects, serialised via [JsonConfig.default]. */
        val rows: JsonArray,
        /**
         * Set when [SELECT_MODELS] triggered a provider-side failure (network,
         * auth, rate-limit). `rows` is empty in that case. Null for
         * [SELECT_PROVIDERS] or on success.
         */
        val error: String? = null,
    )

    @Serializable data class ProviderRow(
        val providerId: String,
        val isDefault: Boolean,
    )

    @Serializable data class ModelRow(
        val providerId: String,
        val modelId: String,
        val name: String,
        val contextWindow: Int,
        val supportsTools: Boolean,
        val supportsThinking: Boolean,
        val supportsImages: Boolean,
    )

    override val id: String = "provider_query"
    override val helpText: String =
        "Unified read-only query over the LLM provider registry (replaces list_providers / " +
            "list_provider_models). Pick one `select`:\n" +
            "  • providers — enumerate all configured providers and mark the default. " +
            "Pure local state, no HTTP. providerId filter rejected — providers is the " +
            "catalog-of-all.\n" +
            "  • models — fetch one provider's model catalog (contextWindow, supportsTools, " +
            "supportsThinking, supportsImages). Requires providerId (error if unknown). " +
            "Hits the provider's /models endpoint; network / auth / rate-limit failures " +
            "surface via Output.error with empty rows, not exceptions.\n" +
            "Start with select=providers to discover valid providerIds before asking for models."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("provider.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("select") {
                put("type", "string")
                put(
                    "description",
                    "What to query: providers | models (case-insensitive).",
                )
                put(
                    "enum",
                    buildJsonArray {
                        add(JsonPrimitive(SELECT_PROVIDERS))
                        add(JsonPrimitive(SELECT_MODELS))
                    },
                )
            }
            putJsonObject("providerId") {
                put("type", "string")
                put(
                    "description",
                    "Provider id (anthropic / openai / gemini). Required for select=models; " +
                        "rejected for select=providers.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("select"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = input.select.trim().lowercase()
        if (select !in ALL_SELECTS) {
            error("select must be one of ${ALL_SELECTS.joinToString(", ")} (got '${input.select}')")
        }
        rejectIncompatibleFilters(select, input)

        return when (select) {
            SELECT_PROVIDERS -> runProvidersQuery()
            SELECT_MODELS -> runModelsQuery(input.providerId!!)
            else -> error("unreachable — select validated above: '$select'")
        }
    }

    private fun runProvidersQuery(): ToolResult<Output> {
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
            data = Output(
                select = SELECT_PROVIDERS,
                total = rows.size,
                returned = rows.size,
                rows = encoded,
            ),
        )
    }

    private suspend fun runModelsQuery(providerId: String): ToolResult<Output> {
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
            data = Output(
                select = SELECT_MODELS,
                total = rows.size,
                returned = rows.size,
                rows = encoded,
                error = errorMsg,
            ),
        )
    }

    private fun rejectIncompatibleFilters(select: String, input: Input) {
        if (select == SELECT_PROVIDERS && input.providerId != null) {
            error(
                "The following filter fields do not apply to select='$select': " +
                    "providerId (select=models only — select=providers is the " +
                    "enumerate-all verb).",
            )
        }
        if (select == SELECT_MODELS && input.providerId.isNullOrBlank()) {
            error(
                "select='$select' requires providerId. Call provider_query(select=providers) " +
                    "to discover valid ids.",
            )
        }
    }

    companion object {
        const val SELECT_PROVIDERS = "providers"
        const val SELECT_MODELS = "models"
        private val ALL_SELECTS = setOf(SELECT_PROVIDERS, SELECT_MODELS)
    }
}
