package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * One `Part.StepFinish(finish=ERROR)` entry inside a failed assistant
 * message's turn. Pinpoints which step of the multi-step LLM loop hit the
 * error + the token spend that still landed (errors near the end of a
 * long tool-call chain are expensive even when the final answer fails).
 */
@Serializable
data class StepFinishErrorEntry(
    val partId: String,
    val tokensInput: Long,
    val tokensOutput: Long,
    val tokensCacheRead: Long,
    val tokensCacheWrite: Long,
    val createdAtEpochMs: Long,
)

/**
 * One failed assistant turn's post-mortem. Aggregates persisted state
 * (message + parts) with live-tracker signals (terminal run-state via
 * [AgentRunStateTracker]).
 *
 * `fallbackChain` is deliberately NOT surfaced in this iteration —
 * `BusEvent.AgentProviderFallback` is bus-only today and isn't persisted;
 * wiring a parallel tracker to capture it is filed as a follow-up
 * (`agent-provider-fallback-chain-tracking`) in BACKLOG. Same for
 * per-retry reason strings — `BusEvent.AgentRetryScheduled.reason` is
 * bus-only; `Message.Assistant.error` holds the aggregated terminal
 * cause which is sufficient for the "why did this turn fail?" question.
 */
@Serializable
data class RunFailureRow(
    /** MessageId of the failed assistant turn. */
    val messageId: String,
    /** `"providerId/modelId"` of the attempted model. */
    val model: String,
    /**
     * The `error` field from the failed assistant message. Typically
     * the raw provider error surfaced through `FinishReason.ERROR`.
     * Null when the message's error field is empty (rare — usually
     * means cancellation, not a provider error).
     */
    val terminalCause: String?,
    /**
     * Every `Part.StepFinish(finish=ERROR)` attached to this message,
     * oldest first. Multi-step LLM loops that fail mid-tool-dispatch
     * can have multiple error-step-finishes before the turn terminates.
     */
    val stepFinishErrors: List<StepFinishErrorEntry>,
    /**
     * Coarse terminal run-state kind (`"failed"` / `"cancelled"` / other)
     * derived from the `AgentRunStateTracker` transition history whose
     * `epochMs` falls in `[message.createdAt, next message.createdAt)`.
     * Null when the tracker wasn't wired or its history doesn't cover
     * this message's time window.
     */
    val runStateTerminalKind: String?,
    val createdAtEpochMs: Long,
)

/**
 * `select=run_failure` — post-mortem aggregation for failed assistant
 * turns.
 *
 * For each `Message.Assistant` in the session whose `finish ==
 * FinishReason.ERROR`, return one [RunFailureRow] with:
 *  - terminal cause (`Message.Assistant.error`);
 *  - every `Part.StepFinish(finish=ERROR)` attached to that message;
 *  - terminal run-state kind from [AgentRunStateTracker] when wired.
 *
 * Rows ordered oldest-first so multi-failure sessions read in timeline
 * order. Optional `messageId` filter scopes to one specific failed
 * turn (single row or empty when the id doesn't match a failed
 * assistant message).
 *
 * Closes `agent-failure-diagnostics-query` from cycle-48's repopulate.
 * Rubric §5.4 — post-mortem observability, no state added.
 *
 * `includeCompacted=true` when reading parts: compaction doesn't hide a
 * post-mortem row (the whole point of the query is to surface failures;
 * losing them to summarisation would defeat the use case).
 */
internal suspend fun runRunFailureQuery(
    sessions: SessionStore,
    tracker: AgentRunStateTracker?,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_RUN_FAILURE}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionIdStr)

    val messagesWithParts = sessions.listMessagesWithParts(sid, includeCompacted = true)
    val indexed = messagesWithParts.withIndex().toList()

    val failedAssistantTurns = indexed.filter { (_, mwp) ->
        val m = mwp.message
        m is Message.Assistant && m.finish == FinishReason.ERROR
    }

    val targetMessageId = input.messageId
    val scoped = if (targetMessageId != null) {
        failedAssistantTurns.filter { it.value.message.id.value == targetMessageId }
    } else {
        failedAssistantTurns
    }

    val trackerHistory = tracker?.history(sid).orEmpty()

    val rows = scoped.map { (i, mwp) ->
        val msg = mwp.message as Message.Assistant

        val stepErrors = mwp.parts
            .filterIsInstance<Part.StepFinish>()
            .filter { it.finish == FinishReason.ERROR }
            .sortedBy { it.createdAt }
            .map {
                StepFinishErrorEntry(
                    partId = it.id.value,
                    tokensInput = it.tokens.input,
                    tokensOutput = it.tokens.output,
                    tokensCacheRead = it.tokens.cacheRead,
                    tokensCacheWrite = it.tokens.cacheWrite,
                    createdAtEpochMs = it.createdAt.toEpochMilliseconds(),
                )
            }

        // Window this message "owns": [this.createdAt, nextMessage.createdAt).
        // Last message owns until the end of tracker history.
        val windowStart = msg.createdAt.toEpochMilliseconds()
        val windowEnd = indexed
            .firstOrNull { it.index > i }
            ?.value?.message?.createdAt?.toEpochMilliseconds()
            ?: Long.MAX_VALUE
        val runStateTerminalKind = trackerHistory
            .lastOrNull { it.epochMs in windowStart until windowEnd }
            ?.let { terminalKindTag(it.state) }

        RunFailureRow(
            messageId = msg.id.value,
            model = "${msg.model.providerId}/${msg.model.modelId}",
            terminalCause = msg.error,
            stepFinishErrors = stepErrors,
            runStateTerminalKind = runStateTerminalKind,
            createdAtEpochMs = msg.createdAt.toEpochMilliseconds(),
        )
    }

    val jsonRows = encodeRows(ListSerializer(RunFailureRow.serializer()), rows)

    val narrative = when {
        targetMessageId != null && rows.isEmpty() ->
            "No failed assistant turn found with messageId='$targetMessageId' in session '${sid.value}' " +
                "(either the id is unknown, or that message finished normally)."
        rows.isEmpty() ->
            "Session ${sid.value} has no failed assistant turns recorded."
        rows.size == 1 -> {
            val r = rows.first()
            "Failed turn ${r.messageId} (${r.model}): cause='${r.terminalCause ?: "unknown"}', " +
                "${r.stepFinishErrors.size} step-finish error(s)."
        }
        else -> "${rows.size} failed turn(s) in session ${sid.value} (oldest first). " +
            "Most recent: ${rows.last().messageId}, cause='${rows.last().terminalCause ?: "unknown"}'."
    }

    return ToolResult(
        title = "session_query run_failure ${sid.value} (${rows.size})",
        outputForLlm = narrative,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_RUN_FAILURE,
            total = rows.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}

private fun terminalKindTag(state: AgentRunState): String = when (state) {
    is AgentRunState.Idle -> "idle"
    is AgentRunState.Generating -> "generating"
    is AgentRunState.AwaitingTool -> "awaiting_tool"
    is AgentRunState.Compacting -> "compacting"
    is AgentRunState.Cancelled -> "cancelled"
    is AgentRunState.Failed -> "failed"
}
