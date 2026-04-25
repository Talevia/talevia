package io.talevia.core.tool.builtin.session

import io.talevia.core.JsonConfig
import io.talevia.core.agent.AgentProviderFallbackTracker
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.query.ActiveRunSummaryRow
import io.talevia.core.tool.builtin.session.query.AncestorRow
import io.talevia.core.tool.builtin.session.query.BusTraceRow
import io.talevia.core.tool.builtin.session.query.CacheStatsRow
import io.talevia.core.tool.builtin.session.query.CancellationHistoryRow
import io.talevia.core.tool.builtin.session.query.CompactionRow
import io.talevia.core.tool.builtin.session.query.ContextPressureRow
import io.talevia.core.tool.builtin.session.query.FallbackHistoryRow
import io.talevia.core.tool.builtin.session.query.ForkRow
import io.talevia.core.tool.builtin.session.query.MessageDetailRow
import io.talevia.core.tool.builtin.session.query.MessageRow
import io.talevia.core.tool.builtin.session.query.PartRow
import io.talevia.core.tool.builtin.session.query.PermissionHistoryRow
import io.talevia.core.tool.builtin.session.query.PermissionRuleRow
import io.talevia.core.tool.builtin.session.query.PreflightSummaryRow
import io.talevia.core.tool.builtin.session.query.RunFailureRow
import io.talevia.core.tool.builtin.session.query.RunStateTransitionRow
import io.talevia.core.tool.builtin.session.query.SessionMetadataRow
import io.talevia.core.tool.builtin.session.query.SessionRecapRow
import io.talevia.core.tool.builtin.session.query.SessionRow
import io.talevia.core.tool.builtin.session.query.SessionSpendSummaryRow
import io.talevia.core.tool.builtin.session.query.SpendSummaryRow
import io.talevia.core.tool.builtin.session.query.StatusRow
import io.talevia.core.tool.builtin.session.query.StepHistoryRow
import io.talevia.core.tool.builtin.session.query.TextSearchMatchRow
import io.talevia.core.tool.builtin.session.query.TokenEstimateRow
import io.talevia.core.tool.builtin.session.query.ToolCallRow
import io.talevia.core.tool.builtin.session.query.ToolSpecBudgetRow
import io.talevia.core.tool.builtin.session.query.runActiveRunSummaryQuery
import io.talevia.core.tool.builtin.session.query.runAncestorsQuery
import io.talevia.core.tool.builtin.session.query.runBusTraceQuery
import io.talevia.core.tool.builtin.session.query.runCacheStatsQuery
import io.talevia.core.tool.builtin.session.query.runCancellationHistoryQuery
import io.talevia.core.tool.builtin.session.query.runCompactionsQuery
import io.talevia.core.tool.builtin.session.query.runContextPressureQuery
import io.talevia.core.tool.builtin.session.query.runFallbackHistoryQuery
import io.talevia.core.tool.builtin.session.query.runForksQuery
import io.talevia.core.tool.builtin.session.query.runMessageDetailQuery
import io.talevia.core.tool.builtin.session.query.runMessagesQuery
import io.talevia.core.tool.builtin.session.query.runPartsQuery
import io.talevia.core.tool.builtin.session.query.runPermissionHistoryQuery
import io.talevia.core.tool.builtin.session.query.runPermissionRulesQuery
import io.talevia.core.tool.builtin.session.query.runPreflightSummaryQuery
import io.talevia.core.tool.builtin.session.query.runRunFailureQuery
import io.talevia.core.tool.builtin.session.query.runRunStateHistoryQuery
import io.talevia.core.tool.builtin.session.query.runSessionMetadataQuery
import io.talevia.core.tool.builtin.session.query.runSessionRecapQuery
import io.talevia.core.tool.builtin.session.query.runSessionsQuery
import io.talevia.core.tool.builtin.session.query.runSpendQuery
import io.talevia.core.tool.builtin.session.query.runSpendSummaryQuery
import io.talevia.core.tool.builtin.session.query.runStatusQuery
import io.talevia.core.tool.builtin.session.query.runStepHistoryQuery
import io.talevia.core.tool.builtin.session.query.runTextSearchQuery
import io.talevia.core.tool.builtin.session.query.runTokenEstimateQuery
import io.talevia.core.tool.builtin.session.query.runToolCallsQuery
import io.talevia.core.tool.builtin.session.query.runToolSpecBudgetQuery
import io.talevia.core.tool.query.QueryDispatcher
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Unified read-only query primitive over the session store — session-lane
 * counterpart of [io.talevia.core.tool.builtin.project.ProjectQueryTool].
 * One tool spec in the LLM's context instead of one tool per dimension
 * (sessions / messages / parts / forks / ancestors / tool_calls) —
 * modelled on codebase-grep's `(select, filter, sort, limit)` shape.
 *
 * [Input.select] discriminates what to return; each select advertises its
 * own filter fields (see [helpText]). Filter fields that don't apply to
 * the chosen select fail loud — silent empty lists would hide typos.
 *
 * Output is uniform: `{select, total, returned, rows}` where `rows` is a
 * [JsonArray] whose shape depends on `select`. The per-select row data
 * classes are top-level types in `io.talevia.core.tool.builtin.session
 * .query` (one file per select); consumers decode with the matching row
 * serializer using the canonical [JsonConfig.default].
 *
 * This file stays a thin dispatcher: schema + validation + select routing.
 * Each select's implementation — including its row data class — lives in
 * its own sibling file so the dispatcher doesn't grow with every new
 * select.
 */
class SessionQueryTool(
    private val sessions: SessionStore,
    /**
     * Optional agent-state snapshot source. Required when `select=status` is
     * requested — callers pass null to keep the rest of the selects
     * functional in non-Agent rigs (pure session store tests). Wired by each
     * production AppContainer from the same bus every Agent publishes on.
     */
    private val agentStates: AgentRunStateTracker? = null,
    /**
     * Optional project store. Required when `select=spend` is requested so we
     * can resolve the session's bound project and walk its lockfile for
     * cost-stamped entries. Callers pass null to keep other selects
     * functional in non-Project rigs (pure session store tests); a
     * `select=spend` call on a rig with no project store reports zero cost
     * and flags `projectResolved=false`.
     */
    private val projects: ProjectStore? = null,
    /**
     * Optional tool-registry reference. Required when
     * `select=tool_spec_budget` is requested so we can enumerate the
     * registered tools and estimate their LLM-spec token weight.
     * Callers pass null in rigs that don't have a registry (pure
     * session-store tests); a `select=tool_spec_budget` call on such a
     * rig reports zero tools and flags `registryResolved=false`.
     * Holding the registry here is safe even though this tool is itself
     * registered in it — `execute()` reads via `registry.all()` at
     * dispatch time, after the composition root has finished wiring.
     */
    private val toolRegistry: ToolRegistry? = null,
    /**
     * Optional provider-fallback tracker. Populates
     * `RunFailureRow.fallbackChain` on `select=run_failure` results. Null
     * when the container doesn't wire it; `fallbackChain` then defaults
     * to the empty list (no regression from pre-cycle-57 callers).
     */
    private val fallbackTracker: AgentProviderFallbackTracker? = null,
    /**
     * Optional permission-history recorder. Required for
     * `select=permission_history`; null in test rigs that don't have
     * permission UX. When null, the select reports zero rows with a
     * descriptive note rather than failing — same convention as the
     * other optional aggregator wires.
     */
    private val permissionHistory: io.talevia.core.permission.PermissionHistoryRecorder? = null,
    /**
     * Optional bus event trace recorder. Required for
     * `select=bus_trace`; null in test rigs that don't wire it. When
     * null the select reports zero rows with a descriptive note rather
     * than failing — same convention as the other optional aggregator
     * wires.
     */
    private val busTrace: io.talevia.core.bus.BusEventTraceRecorder? = null,
) : QueryDispatcher<SessionQueryTool.Input, SessionQueryTool.Output>() {

    @Serializable data class Input(
        /** One of [SELECT_SESSIONS] / [SELECT_MESSAGES] / [SELECT_PARTS] /
         *  [SELECT_FORKS] / [SELECT_ANCESTORS] / [SELECT_TOOL_CALLS] (case-insensitive). */
        val select: String,
        /** Required for messages/parts/forks/ancestors/tool_calls. Rejected for sessions. */
        val sessionId: String? = null,
        /** Optional project filter. `select=sessions` only. */
        val projectId: String? = null,
        /** Include archived sessions? `select=sessions` only. Default false. */
        val includeArchived: Boolean? = null,
        /** `"user"` | `"assistant"`. `select=messages` only. */
        val role: String? = null,
        /** Part kind discriminator (see [helpText]). `select=parts` only. */
        val kind: String? = null,
        /** Include compacted rows? `select=parts` and `select=tool_calls` only. Default true. */
        val includeCompacted: Boolean? = null,
        /** Filter tool parts by toolId (e.g. `"generate_image"`). `select=tool_calls` only. */
        val toolId: String? = null,
        /**
         * Message id for drill-down. Required for `select=message` (the
         * singular describe-verb replacing the old `describe_message` tool),
         * rejected elsewhere.
         */
        val messageId: String? = null,
        /** Post-filter cap. Default 100, clamped to `[1, 1000]`. */
        val limit: Int? = null,
        /** Skip the first N rows after filter+sort. Default 0. */
        val offset: Int? = null,
        /**
         * Epoch-millis lower bound for `select=run_state_history`. Entries
         * with `epochMs < sinceEpochMs` are dropped; null returns the full
         * ring buffer (within its cap). Rejected for other selects.
         */
        val sinceEpochMs: Long? = null,
        /**
         * Substring (case-insensitive) for `select=text_search`. Required
         * for that select; rejected for others. When `sessionId` is null
         * the search is cross-session, otherwise scoped to that session.
         */
        val query: String? = null,
        /**
         * `select=token_estimate` only. When true, include per-message
         * token rows (most-recent first) on the single output row. Default
         * false keeps the response terse so a 1000-message session doesn't
         * blow up the tool-result payload — agents only reach for the
         * breakdown when they're debugging *which* message is heavy.
         */
        val includeBreakdown: Boolean? = null,
    )

    @Serializable data class Output(
        /** Echo of the (normalised) select used to produce [rows]. */
        val select: String,
        /** Count of matches after filters, before offset/limit. */
        val total: Int,
        /** Count of rows in [rows]. Lower than [total] when offset/limit hide rows. */
        val returned: Int,
        /** Select-specific row objects, serialised via [JsonConfig.default]. */
        val rows: JsonArray,
    )

    override val id: String = "session_query"
    override val helpText: String =
        "Unified read-only query over sessions + messages + parts + forks. " +
            "All selects require sessionId except sessions / tool_spec_budget; " +
            "select=message uses messageId instead. Filter-on-wrong-select fails loud. " +
            "Common: limit (1..1000, default 100), offset. Picks one `select`:\n" +
            "  • sessions — filter: projectId, includeArchived.\n" +
            "  • messages — filter: role (user|assistant).\n" +
            "  • parts — filter: kind (text|reasoning|tool|media|timeline-snapshot|" +
            "render-progress|step-start|step-finish|compaction|todos), includeCompacted.\n" +
            "  • forks — child sessions.\n" +
            "  • ancestors — parent chain.\n" +
            "  • tool_calls — filter: toolId, includeCompacted.\n" +
            "  • compactions — Part.Compaction aggregate.\n" +
            "  • status — agent run state: idle|generating|awaiting_tool|compacting|cancelled|failed.\n" +
            "  • session_metadata — single-row drill-down.\n" +
            "  • message — single-row + parts summary.\n" +
            "  • spend — single-row AIGC cost.\n" +
            "  • spend_summary — per-provider roll-up.\n" +
            "  • context_pressure — current vs threshold token count.\n" +
            "  • tool_spec_budget — registry-wide spec token estimate + topByTokens.\n" +
            "  • run_failure — failed-turn post-mortem; optional messageId.\n" +
            "  • fallback_history — turns with ≥1 provider fallback hop; optional messageId.\n" +
            "  • cancellation_history — finish=CANCELLED turns; optional messageId.\n" +
            "  • permission_history — Asked↔Replied round-trips.\n" +
            "  • permission_rules — persisted Always-grant rules.\n" +
            "  • preflight_summary — plan-time snapshot collapsing context_pressure + " +
            "fallback + cancel + retry + pendingAsks.\n" +
            "  • recap — orientation: turnCount, tokens, totalCost, distinctToolsUsed, lastModel.\n" +
            "  • step_history — per-step timeline; optional messageId.\n" +
            "  • active_run_summary — latest turn stats (state, elapsedMs, tokensIn/Out, " +
            "toolCallCount, compactionsInRun).\n" +
            "  • bus_trace — per-session bus event ring buffer {kind, epochMs, summary}; " +
            "filters: kind (event class), sinceEpochMs.\n" +
            "  • text_search — substring grep over Part.Text content {messageId, partId, " +
            "snippet, matchOffset}; requires query, optional sessionId for scope.\n" +
            "  • token_estimate — pre-compaction session-weight probe {messageCount, " +
            "totalTokens, largestMessageTokens, breakdown?}. Heuristic via TokenEstimator " +
            "(matches the compactor's trigger). includeBreakdown=true adds per-message rows " +
            "(most-recent first); default terse."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = SESSION_QUERY_INPUT_SCHEMA

    override val selects: Set<String> = ALL_SELECTS

    override fun rowSerializerFor(select: String): KSerializer<*> = when (select) {
        SELECT_SESSIONS -> SessionRow.serializer()
        SELECT_MESSAGES -> MessageRow.serializer()
        SELECT_PARTS -> PartRow.serializer()
        SELECT_FORKS -> ForkRow.serializer()
        SELECT_ANCESTORS -> AncestorRow.serializer()
        SELECT_TOOL_CALLS -> ToolCallRow.serializer()
        SELECT_COMPACTIONS -> CompactionRow.serializer()
        SELECT_STATUS -> StatusRow.serializer()
        SELECT_SESSION_METADATA -> SessionMetadataRow.serializer()
        SELECT_MESSAGE -> MessageDetailRow.serializer()
        SELECT_SPEND -> SpendSummaryRow.serializer()
        SELECT_SPEND_SUMMARY -> SessionSpendSummaryRow.serializer()
        SELECT_CACHE_STATS -> CacheStatsRow.serializer()
        SELECT_CONTEXT_PRESSURE -> ContextPressureRow.serializer()
        SELECT_RUN_STATE_HISTORY -> RunStateTransitionRow.serializer()
        SELECT_TOOL_SPEC_BUDGET -> ToolSpecBudgetRow.serializer()
        SELECT_RUN_FAILURE -> RunFailureRow.serializer()
        SELECT_FALLBACK_HISTORY -> FallbackHistoryRow.serializer()
        SELECT_CANCELLATION_HISTORY -> CancellationHistoryRow.serializer()
        SELECT_PERMISSION_HISTORY -> PermissionHistoryRow.serializer()
        SELECT_PERMISSION_RULES -> PermissionRuleRow.serializer()
        SELECT_PREFLIGHT_SUMMARY -> PreflightSummaryRow.serializer()
        SELECT_RECAP -> SessionRecapRow.serializer()
        SELECT_BUS_TRACE -> BusTraceRow.serializer()
        SELECT_TEXT_SEARCH -> TextSearchMatchRow.serializer()
        SELECT_STEP_HISTORY -> StepHistoryRow.serializer()
        SELECT_ACTIVE_RUN_SUMMARY -> ActiveRunSummaryRow.serializer()
        SELECT_TOKEN_ESTIMATE -> TokenEstimateRow.serializer()
        else -> error("No row serializer registered for select='$select'")
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = canonicalSelect(input.select)
        rejectIncompatibleFilters(select, input)

        val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)
        val offset = (input.offset ?: 0).coerceAtLeast(0)

        return when (select) {
            SELECT_SESSIONS -> runSessionsQuery(sessions, input, limit, offset)
            SELECT_MESSAGES -> runMessagesQuery(sessions, input, limit, offset)
            SELECT_PARTS -> runPartsQuery(sessions, input, limit, offset)
            SELECT_FORKS -> runForksQuery(sessions, input, limit, offset)
            SELECT_ANCESTORS -> runAncestorsQuery(sessions, input, limit, offset)
            SELECT_TOOL_CALLS -> runToolCallsQuery(sessions, input, limit, offset)
            SELECT_COMPACTIONS -> runCompactionsQuery(sessions, input, limit, offset)
            SELECT_STATUS -> runStatusQuery(sessions, agentStates, input)
            SELECT_SESSION_METADATA -> runSessionMetadataQuery(sessions, input)
            SELECT_MESSAGE -> runMessageDetailQuery(sessions, input)
            SELECT_SPEND -> runSpendQuery(sessions, projects, input)
            SELECT_SPEND_SUMMARY -> runSpendSummaryQuery(sessions, projects, input)
            SELECT_CACHE_STATS -> runCacheStatsQuery(sessions, input)
            SELECT_CONTEXT_PRESSURE -> runContextPressureQuery(sessions, input)
            SELECT_RUN_STATE_HISTORY -> runRunStateHistoryQuery(sessions, agentStates, input, limit, offset)
            SELECT_TOOL_SPEC_BUDGET -> runToolSpecBudgetQuery(toolRegistry, input)
            SELECT_RUN_FAILURE -> runRunFailureQuery(sessions, agentStates, fallbackTracker, input)
            SELECT_FALLBACK_HISTORY -> runFallbackHistoryQuery(sessions, fallbackTracker, input, limit, offset)
            SELECT_CANCELLATION_HISTORY -> runCancellationHistoryQuery(sessions, input, limit, offset)
            SELECT_PERMISSION_HISTORY -> runPermissionHistoryQuery(permissionHistory, input, limit, offset)
            SELECT_PERMISSION_RULES -> runPermissionRulesQuery(sessions, input, limit, offset)
            SELECT_PREFLIGHT_SUMMARY -> runPreflightSummaryQuery(
                sessions = sessions,
                agentStates = agentStates,
                fallbackTracker = fallbackTracker,
                permissionHistory = permissionHistory,
                input = input,
            )
            SELECT_STEP_HISTORY -> runStepHistoryQuery(sessions, input, limit, offset)
            SELECT_RECAP -> runSessionRecapQuery(sessions, projects, input)
            SELECT_BUS_TRACE -> runBusTraceQuery(busTrace, input, limit, offset)
            SELECT_TEXT_SEARCH -> runTextSearchQuery(sessions, input, limit, offset)
            SELECT_ACTIVE_RUN_SUMMARY -> runActiveRunSummaryQuery(sessions, agentStates, input)
            SELECT_TOKEN_ESTIMATE -> runTokenEstimateQuery(sessions, input)
            else -> error("unreachable — select validated above: '$select'")
        }
    }

    /**
     * Delegate to the matrix-driven walker in
     * [SessionQueryFilterMatrix.kt]. The previous if-else chain grew
     * with every new select (cycle-93 §3a #12 trigger fired at 24
     * selects). The matrix is one entry per select; the walker
     * computes "which selects DO accept this field" from the matrix
     * itself for the error message.
     */
    private fun rejectIncompatibleFilters(select: String, input: Input) {
        rejectIncompatibleSessionQueryFilters(select, input)
    }

    companion object {
        const val SELECT_SESSIONS = "sessions"
        const val SELECT_MESSAGES = "messages"
        const val SELECT_PARTS = "parts"
        const val SELECT_FORKS = "forks"
        const val SELECT_ANCESTORS = "ancestors"
        const val SELECT_TOOL_CALLS = "tool_calls"
        const val SELECT_COMPACTIONS = "compactions"
        const val SELECT_STATUS = "status"
        const val SELECT_SESSION_METADATA = "session_metadata"
        const val SELECT_MESSAGE = "message"
        const val SELECT_SPEND = "spend"
        const val SELECT_SPEND_SUMMARY = "spend_summary"
        const val SELECT_CACHE_STATS = "cache_stats"
        const val SELECT_CONTEXT_PRESSURE = "context_pressure"
        const val SELECT_RUN_STATE_HISTORY = "run_state_history"
        const val SELECT_TOOL_SPEC_BUDGET = "tool_spec_budget"
        const val SELECT_RUN_FAILURE = "run_failure"
        const val SELECT_FALLBACK_HISTORY = "fallback_history"
        const val SELECT_CANCELLATION_HISTORY = "cancellation_history"
        const val SELECT_PERMISSION_HISTORY = "permission_history"
        const val SELECT_PERMISSION_RULES = "permission_rules"
        const val SELECT_PREFLIGHT_SUMMARY = "preflight_summary"
        const val SELECT_RECAP = "recap"
        const val SELECT_STEP_HISTORY = "step_history"
        const val SELECT_ACTIVE_RUN_SUMMARY = "active_run_summary"
        const val SELECT_BUS_TRACE = "bus_trace"
        const val SELECT_TEXT_SEARCH = "text_search"
        const val SELECT_TOKEN_ESTIMATE = "token_estimate"
        internal val ALL_SELECTS = setOf(
            SELECT_SESSIONS, SELECT_MESSAGES, SELECT_PARTS,
            SELECT_FORKS, SELECT_ANCESTORS, SELECT_TOOL_CALLS, SELECT_COMPACTIONS, SELECT_STATUS,
            SELECT_SESSION_METADATA, SELECT_MESSAGE, SELECT_SPEND, SELECT_SPEND_SUMMARY,
            SELECT_CACHE_STATS,
            SELECT_CONTEXT_PRESSURE, SELECT_RUN_STATE_HISTORY, SELECT_TOOL_SPEC_BUDGET,
            SELECT_RUN_FAILURE, SELECT_FALLBACK_HISTORY, SELECT_CANCELLATION_HISTORY,
            SELECT_PERMISSION_HISTORY, SELECT_PERMISSION_RULES,
            SELECT_PREFLIGHT_SUMMARY, SELECT_RECAP, SELECT_STEP_HISTORY,
            SELECT_ACTIVE_RUN_SUMMARY, SELECT_BUS_TRACE, SELECT_TEXT_SEARCH,
            SELECT_TOKEN_ESTIMATE,
        )

        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 1000
    }
}
