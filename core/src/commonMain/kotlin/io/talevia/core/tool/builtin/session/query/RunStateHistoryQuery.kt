package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=run_state_history` — the timeline complement to
 * `select=status`. Pulls the [AgentRunStateTracker]'s per-session
 * ring-buffer of [io.talevia.core.agent.StateTransition] entries and
 * renders each as a `RunStateTransitionRow`, oldest first.
 *
 * Scope limits:
 *  - **Process-lifetime only.** The tracker is in-memory, no SQLite
 *    persistence. Querying after an app restart returns an empty
 *    history even if the session ran before.
 *  - **Capped** at `AgentRunStateTracker.DEFAULT_HISTORY_CAP`
 *    (256 transitions per session). Sessions that churn through more
 *    state flips drop the oldest.
 *  - **`sinceEpochMs` filter** trims to `epochMs >= sinceEpochMs`;
 *    null returns the full buffer.
 *
 * Standard `limit` + `offset` apply post-filter, mirroring the other
 * time-series-shaped selects (messages, parts, compactions). The
 * ring-buffer is already time-ordered, so `offset` walks from the
 * oldest surviving transition forward.
 */
internal suspend fun runRunStateHistoryQuery(
    sessions: SessionStore,
    tracker: AgentRunStateTracker?,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val agentStates = tracker
        ?: error(
            "select='${SessionQueryTool.SELECT_RUN_STATE_HISTORY}' requires an AgentRunStateTracker — " +
                "this container was constructed without one (pure-session-store test harnesses).",
        )
    val sessionId = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_RUN_STATE_HISTORY}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionId)
    // Session existence check — keeps the "session deleted" error arm
    // consistent with other sessionId-scoped selects. History reads
    // would return an empty list anyway, but we want the error to say
    // "session not found" rather than "empty history".
    sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
        )

    val fullHistory = agentStates.history(sid, since = input.sinceEpochMs)
    val total = fullHistory.size
    val page = fullHistory.drop(offset).take(limit)
    val rowList = page.map { transition ->
        val (state, cause) = stateTag(transition.state)
        SessionQueryTool.RunStateTransitionRow(
            sessionId = sid.value,
            epochMs = transition.epochMs,
            state = state,
            cause = cause,
        )
    }
    val rows = encodeRows(
        ListSerializer(SessionQueryTool.RunStateTransitionRow.serializer()),
        rowList,
    )
    val summary = buildString {
        append("Session ${sid.value} run-state history: ")
        append("${rowList.size} row(s)")
        if (offset > 0) append(" (offset=$offset)")
        if (rowList.size < total) append(" of $total total")
        input.sinceEpochMs?.let { append(" since=$it") }
        if (rowList.isEmpty()) append(" — no transitions observed in this process.")
    }
    return ToolResult(
        title = "session_query run_state_history ${sid.value} (${rowList.size})",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_RUN_STATE_HISTORY,
            total = total,
            returned = rowList.size,
            rows = rows,
        ),
    )
}

/**
 * Mirror of the `stateTag` helper inside
 * `io.talevia.core.metrics.EventBusMetricsSink` — kept local here to
 * avoid pulling in the metrics module for a 6-line enum-to-string
 * conversion. If a third use site appears, lift to `core.agent`.
 */
private fun stateTag(state: AgentRunState): Pair<String, String?> = when (state) {
    is AgentRunState.Idle -> "idle" to null
    is AgentRunState.Generating -> "generating" to null
    is AgentRunState.AwaitingTool -> "awaiting_tool" to null
    is AgentRunState.Compacting -> "compacting" to null
    is AgentRunState.Cancelled -> "cancelled" to null
    is AgentRunState.Failed -> "failed" to state.cause
}
