package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.compaction.TokenEstimator
import io.talevia.core.session.SessionStore
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
 * a single-row list `[StatusRow(sessionId, state, cause?, neverRan,
 * estimatedTokens, compactionThreshold, percent)]`. State strings match
 * the server SSE DTO's `runState` tag (`idle` / `generating` /
 * `awaiting_tool` / `compacting` / `cancelled` / `failed`). `cause` is
 * non-null only for `state=failed`.
 *
 * The three compaction-progress fields let the UI render a "session is
 * X% toward the compaction threshold" bar without a second round-trip:
 *   - `estimatedTokens` — [TokenEstimator.forHistory] over
 *     `sessions.listMessagesWithParts(includeCompacted=false)`, matching
 *     the exact slice `Compactor` evaluates before deciding to auto-trigger
 *     (compacted parts are already folded into the summary and don't
 *     count).
 *   - `compactionThreshold` — the default auto-trigger the `Agent` ships
 *     with (`compactionTokenThreshold = 120_000`). Projects that wire a
 *     bespoke Agent with a different threshold still see the default
 *     reported here; the field reflects the Core default, not any
 *     per-run override. See the follow-up note in the decision doc for
 *     the "threshold-propagation" refinement.
 *   - `percent` — `estimatedTokens / compactionThreshold`, clamped to
 *     `[0.0, 1.0]`. Integer division would round UI indicators to zero
 *     on small sessions; float keeps the ratio monotonic.
 *
 * No prior state (no run ever dispatched on this session) returns
 * `state=idle` with `neverRan=true` — distinguishing "tracked as Idle
 * post-run" from "never seen". Rubric §5.4.
 */
internal suspend fun runStatusQuery(
    sessions: SessionStore,
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

    // Compaction-progress snapshot. Mirrors what `Agent.autoCompactIfNeeded`
    // evaluates: history with already-compacted parts excluded (they live in
    // a Part.Compaction summary now, not as independent context cost).
    // Unknown session id returns empty list → estimatedTokens = 0, percent = 0.
    // We don't fail-loud on unknown session — `neverRan=true` is a legitimate
    // answer for a never-seen session (matches the tracker's behavior).
    val history = sessions.listMessagesWithParts(sid, includeCompacted = false)
    val estimatedTokens = TokenEstimator.forHistory(history)
    val threshold = DEFAULT_COMPACTION_TOKEN_THRESHOLD
    val percent = (estimatedTokens.toFloat() / threshold.toFloat()).coerceIn(0f, 1f)

    val row = SessionQueryTool.StatusRow(
        sessionId = sid.value,
        state = stateTag(observed),
        cause = (observed as? AgentRunState.Failed)?.cause,
        neverRan = observed == null,
        estimatedTokens = estimatedTokens,
        compactionThreshold = threshold,
        percent = percent,
    )
    val rows = encodeRows(ListSerializer(SessionQueryTool.StatusRow.serializer()), listOf(row))
    val percentNote = if (estimatedTokens > 0) {
        ", ${(percent * 100).toInt()}% of compaction threshold ($estimatedTokens/$threshold tokens)"
    } else {
        ""
    }
    val summary = if (observed == null) {
        "No agent run tracked for ${sid.value} yet (state=idle, neverRan=true$percentNote)."
    } else {
        "${sid.value} state=${row.state}" +
            (row.cause?.let { ", cause='$it'" } ?: "") +
            "$percentNote."
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

/**
 * Mirrors `Agent.compactionTokenThreshold` default (120_000). Kept in sync
 * by convention — if the Agent default changes, bump here too. A future
 * cycle can replace this with a per-session wire-through once Agent exposes
 * its threshold on an accessible surface.
 */
internal const val DEFAULT_COMPACTION_TOKEN_THRESHOLD: Int = 120_000

private fun stateTag(state: AgentRunState?): String = when (state) {
    null, is AgentRunState.Idle -> "idle"
    is AgentRunState.Generating -> "generating"
    is AgentRunState.AwaitingTool -> "awaiting_tool"
    is AgentRunState.Compacting -> "compacting"
    is AgentRunState.Cancelled -> "cancelled"
    is AgentRunState.Failed -> "failed"
}
