package io.talevia.core.tool.builtin.provider

import io.talevia.core.JsonConfig
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.provider.query.ModelRow
import io.talevia.core.tool.builtin.provider.query.ProviderRow
import io.talevia.core.tool.builtin.provider.query.runModelsQuery
import io.talevia.core.tool.builtin.provider.query.runProvidersQuery
import io.talevia.core.tool.query.QueryDispatcher
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
 * matching row serializer — [ProviderRow.serializer] or [ModelRow.serializer].
 * Row types live in the `provider/query/` sibling package per the
 * [QueryDispatcher] top-level-row convention.
 */
class ProviderQueryTool(
    private val providers: ProviderRegistry,
) : QueryDispatcher<ProviderQueryTool.Input, ProviderQueryTool.Output>() {

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

    override val selects: Set<String> = ALL_SELECTS

    override fun rowSerializerFor(select: String): KSerializer<*> = when (select) {
        SELECT_PROVIDERS -> ProviderRow.serializer()
        SELECT_MODELS -> ModelRow.serializer()
        else -> error("No row serializer registered for select='$select'")
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = canonicalSelect(input.select)
        rejectIncompatibleFilters(select, input)

        return when (select) {
            SELECT_PROVIDERS -> runProvidersQuery(providers)
            SELECT_MODELS -> runModelsQuery(providers, input.providerId!!)
            else -> error("unreachable — select validated above: '$select'")
        }
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
        internal val ALL_SELECTS = setOf(SELECT_PROVIDERS, SELECT_MODELS)
    }
}
