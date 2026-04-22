package io.talevia.core.tool.builtin.session

import io.talevia.core.JsonConfig
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.query.runAncestorsQuery
import io.talevia.core.tool.builtin.session.query.runCacheStatsQuery
import io.talevia.core.tool.builtin.session.query.runCompactionsQuery
import io.talevia.core.tool.builtin.session.query.runContextPressureQuery
import io.talevia.core.tool.builtin.session.query.runForksQuery
import io.talevia.core.tool.builtin.session.query.runMessageDetailQuery
import io.talevia.core.tool.builtin.session.query.runMessagesQuery
import io.talevia.core.tool.builtin.session.query.runPartsQuery
import io.talevia.core.tool.builtin.session.query.runRunStateHistoryQuery
import io.talevia.core.tool.builtin.session.query.runSessionMetadataQuery
import io.talevia.core.tool.builtin.session.query.runSessionsQuery
import io.talevia.core.tool.builtin.session.query.runSpendQuery
import io.talevia.core.tool.builtin.session.query.runStatusQuery
import io.talevia.core.tool.builtin.session.query.runToolCallsQuery
import io.talevia.core.tool.builtin.session.query.runToolSpecBudgetQuery
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
 * Replaces six list tools landed in loop-2 / loop-3:
 *  - `list_sessions` → `select=sessions` (filter: projectId, includeArchived)
 *  - `list_messages` → `select=messages` (filter: role)
 *  - `list_parts` → `select=parts` (filter: kind, includeCompacted)
 *  - `list_session_forks` → `select=forks`
 *  - `list_session_ancestors` → `select=ancestors`
 *  - `list_tool_calls` → `select=tool_calls` (filter: toolId, includeCompacted)
 *
 * `describe_session` and `describe_message` stay as separate tools — they
 * are single-entity deep inspections, not projections.
 *
 * Output is uniform: `{select, total, returned, rows}` where `rows` is a
 * [JsonArray] whose shape depends on `select`. Consumers inspect the
 * echoed `select` and decode with the matching row serializer
 * (`SessionQueryTool.SessionRow.serializer()` etc.); wire encoding is
 * the canonical [JsonConfig.default].
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
) : Tool<SessionQueryTool.Input, SessionQueryTool.Output> {

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

    // -----------------------------------------------------------------
    // Row data classes — public because tests + UI decode via the paired
    // serializers (`SessionQueryTool.SessionRow.serializer()` etc.).

    @Serializable data class SessionRow(
        val id: String,
        val projectId: String,
        val title: String,
        val parentId: String? = null,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val archived: Boolean,
    )

    @Serializable data class MessageRow(
        val id: String,
        val role: String,
        val createdAtEpochMs: Long,
        val modelProviderId: String,
        val modelId: String,
        val agent: String? = null,
        val parentId: String? = null,
        val tokensInput: Long? = null,
        val tokensOutput: Long? = null,
        val finish: String? = null,
        val error: String? = null,
    )

    @Serializable data class PartRow(
        val partId: String,
        val kind: String,
        val messageId: String,
        val createdAtEpochMs: Long,
        val compactedAtEpochMs: Long? = null,
        val preview: String,
    )

    @Serializable data class ForkRow(
        val id: String,
        val projectId: String,
        val title: String,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val archived: Boolean,
    )

    @Serializable data class AncestorRow(
        val id: String,
        val projectId: String,
        val title: String,
        val parentId: String? = null,
        val createdAtEpochMs: Long,
        val archived: Boolean,
    )

    @Serializable data class ToolCallRow(
        val partId: String,
        val messageId: String,
        val toolId: String,
        val callId: String,
        /** `"pending"` | `"running"` | `"completed"` | `"error"`. */
        val state: String,
        val title: String? = null,
        val createdAtEpochMs: Long,
        val compactedAtEpochMs: Long? = null,
    )

    @Serializable data class CompactionRow(
        val partId: String,
        val messageId: String,
        /** First message-id in the range the compaction replaced. */
        val fromMessageId: String,
        /** Last message-id in the range the compaction replaced (inclusive). */
        val toMessageId: String,
        /** Full summary produced by the compactor — not truncated, unlike `select=parts` preview. */
        val summaryText: String,
        val compactedAtEpochMs: Long,
    )

    /**
     * `select=message` — one row per drilled message with metadata + part
     * previews. Replaces the deleted `describe_message` tool. Preview
     * strategy matches the old tool's rendering (text first 80 chars,
     * tool toolId+state, media assetId, timeline-snapshot clip count,
     * etc.).
     */
    @Serializable data class MessageDetailRow(
        val messageId: String,
        val sessionId: String,
        /** `"user"` | `"assistant"`. */
        val role: String,
        val createdAtEpochMs: Long,
        val modelProviderId: String,
        val modelId: String,
        /** User-only; null on assistant rows. */
        val agent: String? = null,
        /** Assistant-only; null on user rows. */
        val parentId: String? = null,
        val tokensInput: Long? = null,
        val tokensOutput: Long? = null,
        val finish: String? = null,
        val error: String? = null,
        val partCount: Int,
        val parts: List<MessagePartSummary>,
    )

    @Serializable data class MessagePartSummary(
        val id: String,
        /** Discriminator matching the `@SerialName` of the `Part` subtype. */
        val kind: String,
        val createdAtEpochMs: Long,
        /** When non-null, this part has been compacted out of the LLM context. */
        val compactedAtEpochMs: Long? = null,
        /** Terse human summary, per-kind. */
        val preview: String,
    )

    /**
     * `select=session_metadata` — one row per session with the aggregate
     * counts the old `describe_session` tool returned (message counts,
     * summed token usage, compaction / permission-rule presence, latest
     * message timestamp).
     */
    @Serializable data class SessionMetadataRow(
        val sessionId: String,
        val projectId: String,
        val title: String,
        val parentId: String? = null,
        val archived: Boolean,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val latestMessageAtEpochMs: Long,
        val messageCount: Int,
        val userMessageCount: Int,
        val assistantMessageCount: Int,
        val totalTokensInput: Long,
        val totalTokensOutput: Long,
        val totalTokensCacheRead: Long,
        val totalTokensCacheWrite: Long,
        val hasCompactionPart: Boolean,
        val permissionRuleCount: Int,
        val compactingFromMessageId: String? = null,
    )

    /**
     * `select=spend` — single-row AIGC cost aggregate attributed to one
     * session. Walks the session's bound project's lockfile and sums
     * `costCents` for entries whose stamped sessionId matches. Unknown-cost
     * entries (no pricing rule) are counted in [unknownCostEntries] and do
     * not contribute to [totalCostCents]. [projectResolved] surfaces "the
     * session's project no longer exists" — the session record is still
     * valid but spend is un-computable.
     */
    @Serializable data class SpendSummaryRow(
        val sessionId: String,
        val projectId: String,
        val totalCostCents: Long,
        val entryCount: Int,
        val knownCostEntries: Int,
        val unknownCostEntries: Int,
        val byTool: Map<String, Long> = emptyMap(),
        val unknownByTool: Map<String, Int> = emptyMap(),
        val projectResolved: Boolean,
    )

    /**
     * `select=cache_stats` — single-row prompt-cache utilisation for one
     * session. Walks the session's assistant messages and sums their
     * [TokenUsage]. Providers normalise `input` to include both cached and
     * uncached input tokens, so the hit ratio is
     * `cacheReadTokens / totalInputTokens` regardless of provider. Helpful
     * for the "is my prompt cache actually firing?" debug flow — a hit
     * ratio stuck at 0 across many turns usually means a cache key is
     * rotating per turn (session-scoped key disabled, different model,
     * etc.).
     */
    @Serializable data class CacheStatsRow(
        val sessionId: String,
        /** Number of assistant messages contributing to the aggregate. Zero → the session has had no assistant turns yet. */
        val assistantMessageCount: Int,
        /** Sum of `TokenUsage.input` across assistant messages (cached + uncached). */
        val totalInputTokens: Long,
        /** Sum of `TokenUsage.cacheRead` — input tokens served from the provider's cache. */
        val cacheReadTokens: Long,
        /** Sum of `TokenUsage.cacheWrite` — input tokens newly written to the cache. */
        val cacheWriteTokens: Long,
        /** `cacheReadTokens / totalInputTokens`, clamped to [0.0, 1.0]. Zero when totalInputTokens is zero. */
        val hitRatio: Double,
    )

    /**
     * `select=run_state_history` — one row per observed
     * [AgentRunState] transition for this session, oldest first. Reads
     * from the [AgentRunStateTracker]'s in-memory ring buffer (capped
     * per session), so history is bounded to the current process's
     * lifetime — no SQLite persistence. Useful for "how many times did
     * the agent enter Compacting in the last 5 minutes?" debug
     * questions that `select=status` (latest-only snapshot) can't
     * answer.
     */
    @Serializable data class RunStateTransitionRow(
        val sessionId: String,
        /** Millis since Unix epoch when the tracker observed the transition. */
        val epochMs: Long,
        /** `"idle"` | `"generating"` | `"awaiting_tool"` | `"compacting"` | `"cancelled"` | `"failed"`. */
        val state: String,
        /** Non-null only for `state="failed"` transitions. */
        val cause: String? = null,
    )

    /**
     * `select=context_pressure` — single-row snapshot of how close this
     * session's surviving history is to the Agent's auto-compaction
     * threshold. Unlike `select=status` (which requires the run-state
     * tracker), this works off the session store alone and adds an
     * explicit `marginTokens` field so the LLM can decide whether to
     * pre-emptively summarise. `ratio` is un-clamped so the
     * over-threshold case surfaces as `> 1.0`.
     */
    @Serializable data class ContextPressureRow(
        val sessionId: String,
        /** `TokenEstimator.forHistory` on `listMessagesWithParts(includeCompacted=false)` — same slice Compactor evaluates. */
        val currentEstimate: Int,
        /** Auto-compaction threshold ([io.talevia.core.tool.builtin.session.query.DEFAULT_COMPACTION_TOKEN_THRESHOLD]). */
        val threshold: Int,
        /** `currentEstimate / threshold`, **un-clamped**. Over-threshold reads > 1.0. */
        val ratio: Double,
        /** `threshold - currentEstimate`. Negative when over threshold. */
        val marginTokens: Int,
        /** True when `currentEstimate >= threshold`. Compactor would fire next turn. */
        val overThreshold: Boolean,
        /** How many non-compacted messages contributed to the estimate. */
        val messageCount: Int,
    )

    /**
     * `select=tool_spec_budget` — single-row snapshot of how many tokens
     * the registered tool specs cost the LLM on every turn (VISION §5.4
     * + §3a-10). Registry-wide, session-independent — passing sessionId
     * is rejected so typos surface. [registryResolved] is false only
     * when the session-query is wired without a registry (non-Agent
     * rigs), in which case every count reports zero.
     */
    @Serializable data class ToolSpecBudgetRow(
        val toolCount: Int,
        /** Sum across all tools of `(id + helpText + JsonSchema).length / 4`. Order-of-magnitude estimate. */
        val estimatedTokens: Int,
        /** Sum of raw spec byte lengths — useful when the caller wants to apply its own tokenizer ratio. */
        val specBytes: Int,
        /** True when a registry was injected; false on test rigs that skip registry wiring. */
        val registryResolved: Boolean,
        /** Top [ToolSpecBudgetEntry]s by [ToolSpecBudgetEntry.estimatedTokens] descending. Capped so the response stays cheap. */
        val topByTokens: List<ToolSpecBudgetEntry> = emptyList(),
    )

    @Serializable data class ToolSpecBudgetEntry(
        val toolId: String,
        /** `(id + helpText + schema).length / 4`, rounded half-up. */
        val estimatedTokens: Int,
        val specBytes: Int,
    )

    @Serializable data class StatusRow(
        val sessionId: String,
        /** `"idle"` | `"generating"` | `"awaiting_tool"` | `"compacting"` | `"cancelled"` | `"failed"`. */
        val state: String,
        /** Non-null only when `state="failed"`. */
        val cause: String? = null,
        /**
         * True when the tracker has never seen any [io.talevia.core.agent.AgentRunState]
         * transition for this session. `state` still reports `"idle"` in that
         * case (distinct from "ran and terminated back to idle" only via this flag).
         */
        val neverRan: Boolean = false,
        /**
         * Estimated token footprint of the session's surviving history
         * (`includeCompacted=false`) via [io.talevia.core.compaction.TokenEstimator].
         * Null on rigs that don't have the store wired (not expected in production).
         * Default null keeps legacy Output JSON forward-compatible.
         */
        val estimatedTokens: Int? = null,
        /** Default auto-compaction threshold the Agent ships with (120_000 tokens). */
        val compactionThreshold: Int? = null,
        /** `estimatedTokens / compactionThreshold`, clamped to [0.0, 1.0]. UI progress bar. */
        val percent: Float? = null,
    )

    override val id: String = "session_query"
    override val helpText: String =
        "Unified read-only query over sessions + messages + parts + forks (replaces list_sessions / " +
            "list_messages / list_parts / list_session_forks / list_session_ancestors / " +
            "list_tool_calls). Pick one `select`:\n" +
            "  • sessions — filter: projectId, includeArchived. Most-recent first by updatedAt. " +
            "Defaults limit 100.\n" +
            "  • messages — filter: role (user|assistant). requires sessionId. Most-recent first.\n" +
            "  • parts — filter: kind (text, reasoning, tool, media, timeline-snapshot, " +
            "render-progress, step-start, step-finish, compaction, todos), includeCompacted. " +
            "requires sessionId. Most-recent first.\n" +
            "  • forks — immediate child sessions (one hop). requires sessionId. Oldest first.\n" +
            "  • ancestors — parent chain up to root (child→root). requires sessionId. Depth-bounded.\n" +
            "  • tool_calls — Part.Tool only. filter: toolId, includeCompacted. requires sessionId. " +
            "Most-recent first.\n" +
            "  • compactions — Part.Compaction aggregate view, most-recent first. Each row " +
            "carries from/to messageId + full summaryText + compactedAtEpochMs. requires " +
            "sessionId. Use this instead of parts(kind=compaction) when you need the full summary " +
            "plus message-range metadata.\n" +
            "  • status — snapshot of the agent's most recent run state + compaction progress. " +
            "Each row carries (state, cause?, neverRan, estimatedTokens, compactionThreshold, " +
            "percent) so UI can render both a state badge and a threshold progress bar. " +
            "State is one of (idle | generating | " +
            "awaiting_tool | compacting | cancelled | failed). requires sessionId. neverRan=true " +
            "means the tracker has not seen any run on this session yet.\n" +
            "  • session_metadata — single-row drill-down replacing the old describe_session " +
            "tool. Returns session metadata + aggregate counts (message counts, summed token " +
            "usage, compaction presence, permission-rule count, latest-message timestamp). " +
            "requires sessionId.\n" +
            "  • message — single-row drill-down replacing the old describe_message tool. " +
            "Returns message metadata + per-part summaries. requires messageId (rejected " +
            "elsewhere).\n" +
            "  • spend — single-row AIGC cost aggregate for this session (walks the " +
            "session's bound project lockfile, sums `costCents` for entries stamped with " +
            "sessionId). Unknown-cost entries (no pricing rule) are counted separately. " +
            "requires sessionId. Scoped to the session's CURRENT project only — sessions " +
            "that switched projects mid-run report partial totals.\n" +
            "  • context_pressure — single-row snapshot of token footprint vs the " +
            "auto-compaction threshold. Returns (currentEstimate, threshold, ratio, " +
            "marginTokens, overThreshold, messageCount). ratio is un-clamped so the " +
            "over-threshold case reads > 1.0; marginTokens is negative when over. Use " +
            "before issuing a big-context operation to decide whether to compact first. " +
            "requires sessionId.\n" +
            "  • tool_spec_budget — single-row registry-wide snapshot of how many tokens " +
            "the registered tool specs cost the LLM per turn. Returns (toolCount, " +
            "estimatedTokens, specBytes, registryResolved, topByTokens[5]). Session-" +
            "independent — sessionId is rejected. Use to decide whether to consolidate " +
            "near-duplicate tools before they inflate the per-turn context cost.\n" +
            "Common: limit (default 100, clamped 1..1000), offset (default 0). Setting a filter " +
            "that doesn't apply to the chosen select fails loud so typos surface instead of silently " +
            "returning an empty list."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = SESSION_QUERY_INPUT_SCHEMA

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = input.select.trim().lowercase()
        if (select !in ALL_SELECTS) {
            error("select must be one of ${ALL_SELECTS.joinToString(", ")} (got '${input.select}')")
        }
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
            SELECT_CACHE_STATS -> runCacheStatsQuery(sessions, input)
            SELECT_CONTEXT_PRESSURE -> runContextPressureQuery(sessions, input)
            SELECT_RUN_STATE_HISTORY -> runRunStateHistoryQuery(sessions, agentStates, input, limit, offset)
            SELECT_TOOL_SPEC_BUDGET -> runToolSpecBudgetQuery(toolRegistry, input)
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
            if (select != SELECT_MESSAGE && input.messageId != null) {
                add("messageId (select=message only)")
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
        const val SELECT_CACHE_STATS = "cache_stats"
        const val SELECT_CONTEXT_PRESSURE = "context_pressure"
        const val SELECT_RUN_STATE_HISTORY = "run_state_history"
        const val SELECT_TOOL_SPEC_BUDGET = "tool_spec_budget"
        private val ALL_SELECTS = setOf(
            SELECT_SESSIONS, SELECT_MESSAGES, SELECT_PARTS,
            SELECT_FORKS, SELECT_ANCESTORS, SELECT_TOOL_CALLS, SELECT_COMPACTIONS, SELECT_STATUS,
            SELECT_SESSION_METADATA, SELECT_MESSAGE, SELECT_SPEND, SELECT_CACHE_STATS,
            SELECT_CONTEXT_PRESSURE, SELECT_RUN_STATE_HISTORY, SELECT_TOOL_SPEC_BUDGET,
        )

        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 1000
    }
}
