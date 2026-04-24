package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=active_run_summary` — single-row composite of the running stats
 * for the most recent `Agent.run(...)` invocation on a session. Replaces
 * the 3-call cross-reference (`status` + latest `message` + `run_state_history`)
 * operators had to do before when asking "what is the agent doing right
 * now, and how much has it done so far this turn?".
 *
 * Scope cuts (filed as follow-up bullets):
 *  - `retriesScheduled` count is not populated. The tracker's
 *    [io.talevia.core.agent.StateTransition] doesn't carry the
 *    `retryAttempt` field yet — see `agent-retry-attempt-tracker-capture`
 *    in BACKLOG. When that lands the field opens up here with a default.
 *  - `providerFallbacks` likewise deferred — needs
 *    `AgentProviderFallbackTracker` (bullet `agent-provider-fallback-chain-tracking`).
 */
@Serializable data class ActiveRunSummaryRow(
    val sessionId: String,
    /** Current phase — same vocabulary as `select=status`. */
    val state: String,
    /** Non-null only when `state="failed"`. */
    val cause: String? = null,
    /**
     * True when the tracker has never seen any [AgentRunState]
     * transition for this session AND the session has no assistant
     * messages. All numeric fields collapse to zero / null.
     */
    val neverRan: Boolean = false,
    /**
     * Millis since Unix epoch when the most recent `Agent.run(...)`
     * invocation started writing the assistant turn. Derived from the
     * latest [Message.Assistant.createdAt]; null when [neverRan].
     */
    val runStartedAtEpochMs: Long? = null,
    /**
     * Wall-clock ms elapsed between [runStartedAtEpochMs] and the query
     * time. Null when [neverRan]. A terminal run (state=idle after a
     * completed turn) still reports an elapsed delta — callers check
     * [state] to decide whether the run is live.
     */
    val elapsedMs: Long? = null,
    /**
     * Count of [Part.Tool] parts on the latest assistant message. Grows
     * as the agent dispatches tools mid-turn. Zero when [neverRan].
     */
    val toolCallCount: Int = 0,
    /** Input tokens recorded on the latest assistant message. */
    val tokensIn: Long = 0L,
    /** Output tokens recorded on the latest assistant message. */
    val tokensOut: Long = 0L,
    /** Reasoning tokens recorded on the latest assistant message. */
    val reasoningTokens: Long = 0L,
    /** Cache-read tokens recorded on the latest assistant message. */
    val cacheReadTokens: Long = 0L,
    /** Cache-write tokens recorded on the latest assistant message. */
    val cacheWriteTokens: Long = 0L,
    /**
     * Count of [AgentRunState.Compacting] transitions observed since
     * [runStartedAtEpochMs] in the tracker's ring buffer. A zero here
     * doesn't prove no compaction ran (a ring-buffer wrap could drop
     * old transitions, though this is extraordinarily unlikely within a
     * single run); it's a best-effort indicator.
     */
    val compactionsInRun: Int = 0,
    /** Latest assistant message id. Null when [neverRan]. */
    val latestAssistantMessageId: String? = null,
)

/**
 * `select=active_run_summary` — composes running stats from existing
 * sources without adding new state:
 *  - `tracker.currentState(sid)` → [ActiveRunSummaryRow.state] + `cause`.
 *  - Latest [Message.Assistant] → `runStartedAtEpochMs`, `tokensIn/Out`,
 *    `toolCallCount` (via its [Part.Tool] parts), `latestAssistantMessageId`.
 *  - `tracker.history(sid, since=runStartedAtEpochMs)` filtered to
 *    Compacting → `compactionsInRun`.
 *  - [Clock] (injected, defaulted to [Clock.System]) → `elapsedMs`.
 *
 * Requires `sessionId`. Read-only; no state added. Rubric §5.4.
 */
internal suspend fun runActiveRunSummaryQuery(
    sessions: SessionStore,
    tracker: AgentRunStateTracker?,
    input: SessionQueryTool.Input,
    clock: Clock = Clock.System,
): ToolResult<SessionQueryTool.Output> {
    val sessionId = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_ACTIVE_RUN_SUMMARY}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    require(tracker != null) {
        "select='${SessionQueryTool.SELECT_ACTIVE_RUN_SUMMARY}' requires an AgentRunStateTracker — " +
            "not wired in this container."
    }
    val sid = SessionId(sessionId)
    val observed = tracker.currentState(sid)

    val history = sessions.listMessagesWithParts(sid, includeCompacted = false)
    val latestAssistant = history
        .asReversed()
        .firstOrNull { it.message is Message.Assistant }
    val assistantMsg = latestAssistant?.message as? Message.Assistant
    val neverRan = observed == null && assistantMsg == null

    val runStartedAtEpochMs = assistantMsg?.createdAt?.toEpochMilliseconds()
    val nowMs = clock.now().toEpochMilliseconds()
    val elapsedMs = runStartedAtEpochMs?.let { (nowMs - it).coerceAtLeast(0L) }

    val toolCallCount = latestAssistant?.parts?.count { it is Part.Tool } ?: 0
    val tokens = assistantMsg?.tokens
    val compactionsInRun = runStartedAtEpochMs?.let { since ->
        tracker.history(sid, since = since).count { it.state is AgentRunState.Compacting }
    } ?: 0

    val row = ActiveRunSummaryRow(
        sessionId = sid.value,
        state = stateTagActive(observed),
        cause = (observed as? AgentRunState.Failed)?.cause,
        neverRan = neverRan,
        runStartedAtEpochMs = runStartedAtEpochMs,
        elapsedMs = elapsedMs,
        toolCallCount = toolCallCount,
        tokensIn = tokens?.input ?: 0L,
        tokensOut = tokens?.output ?: 0L,
        reasoningTokens = tokens?.reasoning ?: 0L,
        cacheReadTokens = tokens?.cacheRead ?: 0L,
        cacheWriteTokens = tokens?.cacheWrite ?: 0L,
        compactionsInRun = compactionsInRun,
        latestAssistantMessageId = assistantMsg?.id?.value,
    )
    val rows = encodeRows(ListSerializer(ActiveRunSummaryRow.serializer()), listOf(row))
    val summary = if (neverRan) {
        "No active run on ${sid.value} (state=idle, neverRan=true)."
    } else {
        val elapsedSec = (elapsedMs ?: 0L) / 1000
        "${sid.value} state=${row.state}, elapsed=${elapsedSec}s, " +
            "tokens in=${row.tokensIn}/out=${row.tokensOut}, " +
            "toolCalls=${row.toolCallCount}" +
            (row.cause?.let { ", cause='$it'" } ?: "") +
            if (row.compactionsInRun > 0) ", compactions=${row.compactionsInRun}" else ""
    }
    return ToolResult(
        title = "session_query active_run_summary ${sid.value}",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_ACTIVE_RUN_SUMMARY,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}

private fun stateTagActive(state: AgentRunState?): String = when (state) {
    null, is AgentRunState.Idle -> "idle"
    is AgentRunState.Generating -> "generating"
    is AgentRunState.AwaitingTool -> "awaiting_tool"
    is AgentRunState.Compacting -> "compacting"
    is AgentRunState.Cancelled -> "cancelled"
    is AgentRunState.Failed -> "failed"
}
