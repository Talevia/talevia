package io.talevia.core.tool.builtin.provider

import io.talevia.core.JsonConfig
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.provider.ProviderWarmupStats
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.provider.query.CostCompareRow
import io.talevia.core.tool.builtin.provider.query.CostHistoryRow
import io.talevia.core.tool.builtin.provider.query.ModelRow
import io.talevia.core.tool.builtin.provider.query.ProviderRow
import io.talevia.core.tool.builtin.provider.query.WarmupStatsRow
import io.talevia.core.tool.builtin.provider.query.runCostCompareQuery
import io.talevia.core.tool.builtin.provider.query.runCostHistoryQuery
import io.talevia.core.tool.builtin.provider.query.runModelsQuery
import io.talevia.core.tool.builtin.provider.query.runProvidersQuery
import io.talevia.core.tool.builtin.provider.query.runWarmupStatsQuery
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
    private val warmupStats: ProviderWarmupStats,
    private val projects: ProjectStore,
) : QueryDispatcher<ProviderQueryTool.Input, ProviderQueryTool.Output>() {

    @Serializable data class Input(
        /** One of [SELECT_PROVIDERS] / [SELECT_MODELS] / [SELECT_COST_COMPARE] /
         *  [SELECT_WARMUP_STATS] / [SELECT_COST_HISTORY] (case-insensitive). */
        val select: String,
        /** Required for [SELECT_MODELS]; rejected for [SELECT_PROVIDERS] / [SELECT_COST_COMPARE]. */
        val providerId: String? = null,
        /**
         * Required for [SELECT_COST_COMPARE] — prompt-token budget the
         * comparison scales to. Rejected for other selects.
         */
        val requestedInputTokens: Int? = null,
        /**
         * Required for [SELECT_COST_COMPARE] — generated-token budget the
         * comparison scales to. Rejected for other selects.
         */
        val requestedOutputTokens: Int? = null,
        /**
         * [SELECT_COST_HISTORY] only — cap returned rows. Default 50, clamped
         * to 1..500. Rejected for other selects.
         */
        val limit: Int? = null,
        /**
         * [SELECT_COST_HISTORY] only — drop entries with
         * `provenance.createdAtEpochMs < sinceEpochMs`. Rejected for other
         * selects. Null/omitted = no lower bound.
         */
        val sinceEpochMs: Long? = null,
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
        "Unified read-only query over the LLM provider registry. Pick one `select`:\n" +
            "  • providers — enumerate configured providers + mark default. No HTTP.\n" +
            "  • models — fetch one provider's model catalog (contextWindow, supportsTools, " +
            "supportsThinking, supportsImages). requires providerId. Hits /models endpoint; " +
            "failures surface via Output.error with empty rows.\n" +
            "  • cost_compare — priced (provider, model) pairs + cents-per-1k rates + rolled-up " +
            "estimatedCostCents for (requestedInputTokens, requestedOutputTokens). " +
            "Sorted ascending; rows.first() is cheapest. Local snapshot table; no HTTP.\n" +
            "  • warmup_stats — per-provider cold-start latency over the rolling window. Rows: " +
            "{providerId, count, p50Ms, p95Ms, p99Ms, minMs, maxMs, latestMs}. Sourced from " +
            "BusEvent.ProviderWarmup(Starting→Ready) pairings since process start. Providers " +
            "without a warmup/streaming split (e.g. synchronous OpenAI image endpoints) are " +
            "absent from the result. No filters; no HTTP.\n" +
            "  • cost_history — most-recent N priced AIGC dispatches across every project. " +
            "Rows: {toolId, providerId, modelId, costCents, projectId, sessionId, " +
            "originatingMessageId, assetId, createdAtEpochMs}. Sourced from each project's " +
            "lockfile.entries; entries without costCents are filtered out. Filters: limit " +
            "(default 50, max 500), sinceEpochMs. Sorted by createdAtEpochMs desc."
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
                    "What to query: providers | models | cost_compare | warmup_stats | " +
                        "cost_history (case-insensitive).",
                )
                put(
                    "enum",
                    buildJsonArray {
                        add(JsonPrimitive(SELECT_PROVIDERS))
                        add(JsonPrimitive(SELECT_MODELS))
                        add(JsonPrimitive(SELECT_COST_COMPARE))
                        add(JsonPrimitive(SELECT_WARMUP_STATS))
                        add(JsonPrimitive(SELECT_COST_HISTORY))
                    },
                )
            }
            putJsonObject("providerId") {
                put("type", "string")
                put("description", "Provider id. Required for select=models; rejected otherwise.")
            }
            putJsonObject("requestedInputTokens") {
                put("type", "integer")
                put("minimum", 0)
                put("description", "cost_compare only. Prompt-token budget.")
            }
            putJsonObject("requestedOutputTokens") {
                put("type", "integer")
                put("minimum", 0)
                put("description", "cost_compare only. Output-token budget.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("minimum", 1)
                put("maximum", 500)
                put("description", "cost_history only. Cap returned rows. Default 50.")
            }
            putJsonObject("sinceEpochMs") {
                put("type", "integer")
                put("minimum", 0)
                put(
                    "description",
                    "cost_history only. Drop entries older than this epoch-ms. Null = no lower bound.",
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
        SELECT_COST_COMPARE -> CostCompareRow.serializer()
        SELECT_WARMUP_STATS -> WarmupStatsRow.serializer()
        SELECT_COST_HISTORY -> CostHistoryRow.serializer()
        else -> error("No row serializer registered for select='$select'")
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = canonicalSelect(input.select)
        rejectIncompatibleFilters(select, input)

        return when (select) {
            SELECT_PROVIDERS -> runProvidersQuery(providers)
            SELECT_MODELS -> runModelsQuery(providers, input.providerId!!)
            SELECT_COST_COMPARE -> runCostCompareQuery(
                requestedInputTokens = input.requestedInputTokens!!,
                requestedOutputTokens = input.requestedOutputTokens!!,
            )
            SELECT_WARMUP_STATS -> runWarmupStatsQuery(warmupStats)
            SELECT_COST_HISTORY -> runCostHistoryQuery(
                store = projects,
                limit = (input.limit ?: 50).coerceIn(1, 500),
                sinceEpochMs = input.sinceEpochMs,
            )
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
        if (select == SELECT_WARMUP_STATS && input.providerId != null) {
            error(
                "The following filter fields do not apply to select='$select': " +
                    "providerId (warmup_stats returns one row per observed provider; " +
                    "filter client-side if you only want one).",
            )
        }
        if (select == SELECT_COST_HISTORY && input.providerId != null) {
            error(
                "The following filter fields do not apply to select='$select': " +
                    "providerId (cost_history aggregates every project's lockfile; " +
                    "filter client-side if you only want one provider).",
            )
        }
        if (select == SELECT_MODELS && input.providerId.isNullOrBlank()) {
            error(
                "select='$select' requires providerId. Call provider_query(select=providers) " +
                    "to discover valid ids.",
            )
        }
        if (select != SELECT_COST_COMPARE &&
            (input.requestedInputTokens != null || input.requestedOutputTokens != null)
        ) {
            error(
                "The following filter fields do not apply to select='$select': " +
                    "requestedInputTokens / requestedOutputTokens (cost_compare only).",
            )
        }
        if (select == SELECT_MODELS &&
            (input.requestedInputTokens != null || input.requestedOutputTokens != null)
        ) {
            // Subsumed by the previous check but kept explicit — models + token
            // params is a common accidental combination.
            error(
                "select='$select' does not accept requestedInputTokens / requestedOutputTokens " +
                    "(cost_compare only).",
            )
        }
        if (select != SELECT_COST_HISTORY &&
            (input.limit != null || input.sinceEpochMs != null)
        ) {
            error(
                "The following filter fields do not apply to select='$select': " +
                    "limit / sinceEpochMs (cost_history only).",
            )
        }
        if (select == SELECT_COST_COMPARE) {
            if (input.providerId != null) {
                error(
                    "select='$select' does not accept providerId — cost_compare returns " +
                        "every priced (provider, model) pair in the static table.",
                )
            }
            if (input.requestedInputTokens == null || input.requestedOutputTokens == null) {
                error(
                    "select='$select' requires both requestedInputTokens and " +
                        "requestedOutputTokens (non-negative integers) to scale the comparison.",
                )
            }
        }
    }

    companion object {
        const val SELECT_PROVIDERS = "providers"
        const val SELECT_MODELS = "models"
        const val SELECT_COST_COMPARE = "cost_compare"
        const val SELECT_WARMUP_STATS = "warmup_stats"
        const val SELECT_COST_HISTORY = "cost_history"
        internal val ALL_SELECTS = setOf(
            SELECT_PROVIDERS, SELECT_MODELS, SELECT_COST_COMPARE, SELECT_WARMUP_STATS,
            SELECT_COST_HISTORY,
        )
    }
}
