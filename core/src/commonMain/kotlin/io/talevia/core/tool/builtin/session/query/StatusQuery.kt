package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=status` — snapshot of the agent's most recent [AgentRunState]
 * for the given session. Complements the streaming
 * [io.talevia.core.bus.BusEvent.AgentRunStateChanged]: late subscribers
 * (UI cold-boot, tools mid-run asking "am I in a compaction pass?") use
 * this to read the current state without having tailed the event stream.
 *
 * Requires `sessionId` (same contract as messages/parts/forks/…). Returns
 * a single-row list `[StatusRow(sessionId, state, cause?)]`. State strings
 * match the server SSE DTO's `runState` tag (`idle` / `generating` /
 * `awaiting_tool` / `compacting` / `cancelled` / `failed`). `cause` is
 * non-null only for `state=failed`.
 *
 * No prior state (no run ever dispatched on this session) returns
 * `state=idle` with `neverRan=true` — distinguishing "tracked as Idle
 * post-run" from "never seen". Rubric §5.4.
 */
internal fun runStatusQuery(
    tracker: AgentRunStateTracker?,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val sessionId = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_STATUS}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    require(tracker != null) {
        "select='${SessionQueryTool.SELECT_STATUS}' requires an AgentRunStateTracker — " +
            "not wired in this container."
    }
    val sid = SessionId(sessionId)
    val observed = tracker.currentState(sid)
    val row = SessionQueryTool.StatusRow(
        sessionId = sid.value,
        state = stateTag(observed),
        cause = (observed as? AgentRunState.Failed)?.cause,
        neverRan = observed == null,
    )
    val rows = encodeRows(ListSerializer(SessionQueryTool.StatusRow.serializer()), listOf(row))
    val summary = if (observed == null) {
        "No agent run tracked for ${sid.value} yet (state=idle, neverRan=true)."
    } else {
        "${sid.value} state=${row.state}" + (row.cause?.let { ", cause='$it'" } ?: "") + "."
    }
    return ToolResult(
        title = "session_query status ${sid.value}",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_STATUS,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}

private fun stateTag(state: AgentRunState?): String = when (state) {
    null, is AgentRunState.Idle -> "idle"
    is AgentRunState.Generating -> "generating"
    is AgentRunState.AwaitingTool -> "awaiting_tool"
    is AgentRunState.Compacting -> "compacting"
    is AgentRunState.Cancelled -> "cancelled"
    is AgentRunState.Failed -> "failed"
}
