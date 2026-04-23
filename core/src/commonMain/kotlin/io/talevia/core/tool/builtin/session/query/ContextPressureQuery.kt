package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.compaction.TokenEstimator
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=context_pressure` — single-row snapshot of how close this session's
 * surviving history is to the Agent's auto-compaction threshold. Unlike
 * `select=status` (which requires the run-state tracker), this works off the
 * session store alone and adds an explicit `marginTokens` field so the LLM
 * can decide whether to pre-emptively summarise. `ratio` is un-clamped so the
 * over-threshold case surfaces as `> 1.0`.
 */
@Serializable data class ContextPressureRow(
    val sessionId: String,
    /** `TokenEstimator.forHistory` on `listMessagesWithParts(includeCompacted=false)` — same slice Compactor evaluates. */
    val currentEstimate: Int,
    /** Auto-compaction threshold ([DEFAULT_COMPACTION_TOKEN_THRESHOLD]). */
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
 * `select=context_pressure` — single-row snapshot of how close this session's
 * context is to the Agent's auto-compaction threshold. Exists so the LLM can
 * proactively decide "I should summarise / branch into a subtask" **before**
 * the Compactor fires automatically (VISION §5.4 "专家路径 + agent 路径共享
 * 同一份底层状态" — the agent sees the same token pressure number the
 * Compactor is deciding on).
 *
 * Why a separate select when `select=status` already carries
 * (estimatedTokens, compactionThreshold, percent)?
 *  1. **No tracker dependency.** `status` requires an [AgentRunStateTracker]
 *     (the tracker owns the run-state machine). Pure context-pressure
 *     readings don't — they only need the session store. A rig without an
 *     agent wired up (pure-tool CLI harness, server endpoint publishing
 *     session health without instantiating an Agent) can still ask this
 *     question.
 *  2. **Explicit `marginTokens`.** How many tokens before auto-compact
 *     fires. LLM decision math is simpler with "margin" than with ratio
 *     alone, and a negative margin is the most useful "act now" signal
 *     (status's `percent` clamps to [0, 1] so the over-threshold case
 *     looks identical to "exactly at threshold").
 *  3. **Responsibility separation.** `status` answers "what is the agent
 *     doing right now?". `context_pressure` answers "how heavy is this
 *     conversation?". Coupling the two forced `status` to double as a
 *     pressure gauge; splitting them keeps each row's contract focused.
 *
 * Input: requires `sessionId`. Unknown session → errors (matches every
 * other sessionId-scoped select). Token estimate uses
 * `sessions.listMessagesWithParts(sid, includeCompacted=false)` — same
 * slice `Compactor` actually evaluates (compacted parts fold into a
 * `Part.Compaction` summary and don't double-count).
 *
 * Output: one `ContextPressureRow` carrying (currentEstimate, threshold,
 * ratio, marginTokens, overThreshold, messageCount). `ratio` is
 * `currentEstimate / threshold` un-clamped so the over-threshold case
 * surfaces as >1.0.
 */
internal suspend fun runContextPressureQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val sessionId = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_CONTEXT_PRESSURE}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
        )

    val history = sessions.listMessagesWithParts(session.id, includeCompacted = false)
    val currentEstimate = TokenEstimator.forHistory(history)
    val threshold = DEFAULT_COMPACTION_TOKEN_THRESHOLD
    val ratio = if (threshold > 0) currentEstimate.toDouble() / threshold.toDouble() else 0.0
    val marginTokens = threshold - currentEstimate
    val overThreshold = currentEstimate >= threshold

    val row = ContextPressureRow(
        sessionId = sid.value,
        currentEstimate = currentEstimate,
        threshold = threshold,
        ratio = ratio,
        marginTokens = marginTokens,
        overThreshold = overThreshold,
        messageCount = history.size,
    )
    val rows = encodeRows(
        ListSerializer(ContextPressureRow.serializer()),
        listOf(row),
    )
    val pct = (ratio * 100.0).toString().take(5)
    val marginNote = if (overThreshold) {
        " (OVER threshold by ${-marginTokens} tokens — Compactor will fire next turn)"
    } else {
        " (${marginTokens} tokens remaining before auto-compact)"
    }
    val summary = "Session ${sid.value} context_pressure: $currentEstimate/$threshold tokens " +
        "(${pct}%), ${history.size} message(s)$marginNote."
    return ToolResult(
        title = "session_query context_pressure ${sid.value} (${pct}%)",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_CONTEXT_PRESSURE,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}
