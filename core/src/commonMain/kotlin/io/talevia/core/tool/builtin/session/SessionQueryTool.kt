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
import io.talevia.core.tool.builtin.session.query.CacheStatsRow
import io.talevia.core.tool.builtin.session.query.CompactionRow
import io.talevia.core.tool.builtin.session.query.ContextPressureRow
import io.talevia.core.tool.builtin.session.query.ForkRow
import io.talevia.core.tool.builtin.session.query.MessageDetailRow
import io.talevia.core.tool.builtin.session.query.MessageRow
import io.talevia.core.tool.builtin.session.query.PartRow
import io.talevia.core.tool.builtin.session.query.RunFailureRow
import io.talevia.core.tool.builtin.session.query.RunStateTransitionRow
import io.talevia.core.tool.builtin.session.query.SessionMetadataRow
import io.talevia.core.tool.builtin.session.query.SessionRow
import io.talevia.core.tool.builtin.session.query.SessionSpendSummaryRow
import io.talevia.core.tool.builtin.session.query.SpendSummaryRow
import io.talevia.core.tool.builtin.session.query.StatusRow
import io.talevia.core.tool.builtin.session.query.ToolCallRow
import io.talevia.core.tool.builtin.session.query.ToolSpecBudgetRow
import io.talevia.core.tool.builtin.session.query.runActiveRunSummaryQuery
import io.talevia.core.tool.builtin.session.query.runAncestorsQuery
import io.talevia.core.tool.builtin.session.query.runCacheStatsQuery
import io.talevia.core.tool.builtin.session.query.runCompactionsQuery
import io.talevia.core.tool.builtin.session.query.runContextPressureQuery
import io.talevia.core.tool.builtin.session.query.runForksQuery
import io.talevia.core.tool.builtin.session.query.runMessageDetailQuery
import io.talevia.core.tool.builtin.session.query.runMessagesQuery
import io.talevia.core.tool.builtin.session.query.runPartsQuery
import io.talevia.core.tool.builtin.session.query.runRunFailureQuery
import io.talevia.core.tool.builtin.session.query.runRunStateHistoryQuery
import io.talevia.core.tool.builtin.session.query.runSessionMetadataQuery
import io.talevia.core.tool.builtin.session.query.runSessionsQuery
import io.talevia.core.tool.builtin.session.query.runSpendQuery
import io.talevia.core.tool.builtin.session.query.runSpendSummaryQuery
import io.talevia.core.tool.builtin.session.query.runStatusQuery
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
        "Unified read-only query over sessions + messages + parts + forks. Pick one `select`:\n" +
            "  • sessions — filter: projectId, includeArchived. Most-recent by updatedAt.\n" +
            "  • messages — filter: role (user|assistant). requires sessionId.\n" +
            "  • parts — filter: kind (text|reasoning|tool|media|timeline-snapshot|" +
            "render-progress|step-start|step-finish|compaction|todos), includeCompacted. " +
            "requires sessionId.\n" +
            "  • forks — immediate child sessions (one hop). requires sessionId. Oldest first.\n" +
            "  • ancestors — parent chain to root. requires sessionId. Depth-bounded.\n" +
            "  • tool_calls — Part.Tool only. filter: toolId, includeCompacted. " +
            "requires sessionId.\n" +
            "  • compactions — Part.Compaction aggregate (from/to messageId + full summary + " +
            "compactedAtEpochMs). requires sessionId.\n" +
            "  • status — agent run state + compaction progress: (state, cause?, neverRan, " +
            "estimatedTokens, compactionThreshold, percent). state: " +
            "idle|generating|awaiting_tool|compacting|cancelled|failed. requires sessionId.\n" +
            "  • session_metadata — single-row drill-down (message counts, token usage, " +
            "compaction presence, permission-rule count). requires sessionId.\n" +
            "  • message — single-row drill-down + per-part summaries. requires messageId.\n" +
            "  • spend — single-row AIGC cost aggregate for this session's current project. " +
            "requires sessionId.\n" +
            "  • spend_summary — per-provider roll-up of this session's AIGC spend: " +
            "(totalCalls, totalTokens?, estimatedUsdCents?, perProviderBreakdown[providerId, " +
            "calls, tokens?, usdCents?, unknownCalls]). Complements `spend` (grouped by tool) " +
            "with a provider lens. requires sessionId.\n" +
            "  • context_pressure — (currentEstimate, threshold, ratio, marginTokens, " +
            "overThreshold, messageCount); ratio un-clamped > 1.0 when over. requires sessionId.\n" +
            "  • tool_spec_budget — registry-wide (toolCount, estimatedTokens, specBytes, " +
            "registryResolved, topByTokens[5]). Session-independent — sessionId rejected.\n" +
            "  • run_failure — post-mortem for failed turns (terminalCause + stepFinishErrors); " +
            "requires sessionId; optional messageId drills to one turn.\n" +
            "  • active_run_summary — running stats for the latest turn (state, elapsedMs, " +
            "tokensIn/Out, toolCallCount, compactionsInRun). requires sessionId.\n" +
            "Common: limit (default 100, clamped 1..1000), offset (default 0). Filter-on-" +
            "wrong-select fails loud."
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
        SELECT_ACTIVE_RUN_SUMMARY -> ActiveRunSummaryRow.serializer()
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
            SELECT_ACTIVE_RUN_SUMMARY -> runActiveRunSummaryQuery(sessions, agentStates, input)
            else -> error("unreachable — select validated above: '$select'")
        }
    }

    private fun rejectIncompatibleFilters(select: String, input: Input) {
        // Each filter field belongs to a specific select. Setting it on the wrong
        // select usually means the LLM typed the wrong field name — fail loud.
        val misapplied = buildList {
            if (select != SELECT_SESSIONS) {
                // sessions-only filters
                if (input.projectId != null) add("projectId (select=sessions only)")
                if (input.includeArchived != null) add("includeArchived (select=sessions only)")
            }
            if (select != SELECT_MESSAGES && input.role != null) {
                add("role (select=messages only)")
            }
            if (select != SELECT_PARTS && input.kind != null) {
                add("kind (select=parts only)")
            }
            if (select != SELECT_PARTS && select != SELECT_TOOL_CALLS && input.includeCompacted != null) {
                add("includeCompacted (select=parts or tool_calls only)")
            }
            if (select != SELECT_TOOL_CALLS && input.toolId != null) {
                add("toolId (select=tool_calls only)")
            }
            if (select != SELECT_MESSAGE && select != SELECT_RUN_FAILURE && input.messageId != null) {
                add("messageId (select=message or run_failure only)")
            }
            // sessionId is required for everything except sessions (and `message`, which
            // uses messageId for the drill-down); rejected for sessions.
            if (select == SELECT_SESSIONS && input.sessionId != null) {
                add("sessionId (rejected for select=sessions — use projectId)")
            }
            if (select != SELECT_RUN_STATE_HISTORY && input.sinceEpochMs != null) {
                add("sinceEpochMs (select=run_state_history only)")
            }
            // tool_spec_budget is a registry-wide snapshot — passing sessionId is a typo.
            if (select == SELECT_TOOL_SPEC_BUDGET && input.sessionId != null) {
                add("sessionId (rejected for select=tool_spec_budget — registry-wide snapshot)")
            }
        }
        if (misapplied.isNotEmpty()) {
            error(
                "The following filter fields do not apply to select='$select': " +
                    misapplied.joinToString(", "),
            )
        }
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
        const val SELECT_ACTIVE_RUN_SUMMARY = "active_run_summary"
        internal val ALL_SELECTS = setOf(
            SELECT_SESSIONS, SELECT_MESSAGES, SELECT_PARTS,
            SELECT_FORKS, SELECT_ANCESTORS, SELECT_TOOL_CALLS, SELECT_COMPACTIONS, SELECT_STATUS,
            SELECT_SESSION_METADATA, SELECT_MESSAGE, SELECT_SPEND, SELECT_SPEND_SUMMARY,
            SELECT_CACHE_STATS,
            SELECT_CONTEXT_PRESSURE, SELECT_RUN_STATE_HISTORY, SELECT_TOOL_SPEC_BUDGET,
            SELECT_RUN_FAILURE, SELECT_ACTIVE_RUN_SUMMARY,
        )

        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 1000
    }
}
