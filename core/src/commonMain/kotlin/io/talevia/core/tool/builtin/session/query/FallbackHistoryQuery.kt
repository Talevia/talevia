package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.agent.AgentProviderFallbackTracker
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * One assistant turn whose wall-clock window saw ≥ 1 provider fallback hop.
 * Complements [RunFailureRow.fallbackChain] (which only surfaces on
 * finish=ERROR turns) by exposing chains on **successful** turns too —
 * "provider A hit 503, B recovered and finished the turn" is invisible
 * via run_failure but load-bearing for the operator / agent deciding
 * whether to switch the default routing.
 *
 * `finish` is the turn's terminal FinishReason normalised to a short
 * lowercase tag (`end_turn` / `tool_calls` / `error` / `unknown`). An
 * operator reading a long fallback history can filter by `finish` client-
 * side to answer "which fallbacks saved a turn that otherwise would have
 * failed" vs "which fallbacks happened but the turn still failed" without
 * a second roundtrip.
 *
 * `chain` reuses [FallbackHopEntry] from [RunFailureRow] so consumers
 * already decoding failure post-mortems don't need a second row type.
 */
@Serializable
data class FallbackHistoryRow(
    val messageId: String,
    val createdAtEpochMs: Long,
    val model: String,
    val finish: String,
    val chain: List<FallbackHopEntry>,
)

/**
 * `select=fallback_history` — every assistant turn that triggered at least
 * one provider fallback hop within its wall-clock window, oldest first.
 *
 * Data flow:
 *  1. Enumerate assistant messages in `[createdAt(msg_i),
 *     createdAt(msg_{i+1}))` windowing pattern (same window math as
 *     [runRunFailureQuery]).
 *  2. For each window, filter [AgentProviderFallbackTracker.hops] to the
 *     hops whose `epochMs` falls inside.
 *  3. Emit one row per window with a non-empty chain. Empty windows are
 *     dropped — listing turns with no fallback clutters the result
 *     without value (consumers wanting "all turns" query `select=messages`).
 *
 * `messageId` filter narrows to one specific turn (useful for "did this
 * failed run burn a fallback?" post-mortem); returns empty when the id
 * doesn't match an assistant message in the session.
 *
 * Untracked containers (no `fallbackTracker` wired) always return empty —
 * same degradation pattern as [RunFailureRow.fallbackChain].
 *
 * Closes `session-query-fallback-history` from M2 iteration-42 repopulate.
 * Rubric §5.4 / §5.7.
 */
internal suspend fun runFallbackHistoryQuery(
    sessions: SessionStore,
    fallbackTracker: AgentProviderFallbackTracker?,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_FALLBACK_HISTORY}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionIdStr)

    val messagesWithParts = sessions.listMessagesWithParts(sid, includeCompacted = true)
    val indexed = messagesWithParts.withIndex().toList()

    val assistantTurns = indexed.filter { (_, mwp) -> mwp.message is Message.Assistant }

    val targetMessageId = input.messageId
    val scoped = if (targetMessageId != null) {
        assistantTurns.filter { it.value.message.id.value == targetMessageId }
    } else {
        assistantTurns
    }

    val fallbackHops = fallbackTracker?.hops(sid).orEmpty()

    val rowsAll = scoped.mapNotNull { (i, mwp) ->
        val msg = mwp.message as Message.Assistant
        val windowStart = msg.createdAt.toEpochMilliseconds()
        val windowEnd = indexed
            .firstOrNull { it.index > i }
            ?.value?.message?.createdAt?.toEpochMilliseconds()
            ?: Long.MAX_VALUE
        val chain = fallbackHops
            .filter { it.epochMs in windowStart until windowEnd }
            .map {
                FallbackHopEntry(
                    fromProviderId = it.fromProviderId,
                    toProviderId = it.toProviderId,
                    reason = it.reason,
                    epochMs = it.epochMs,
                )
            }
        if (chain.isEmpty()) return@mapNotNull null
        FallbackHistoryRow(
            messageId = msg.id.value,
            createdAtEpochMs = windowStart,
            model = "${msg.model.providerId}/${msg.model.modelId}",
            finish = finishTag(msg.finish),
            chain = chain,
        )
    }

    val total = rowsAll.size
    val rows = rowsAll.drop(offset).take(limit)

    val jsonRows = encodeRows(ListSerializer(FallbackHistoryRow.serializer()), rows)

    val narrative = when {
        rows.isEmpty() && targetMessageId != null ->
            "No fallback hops recorded for messageId='$targetMessageId' in session '${sid.value}' " +
                "(either the message id is unknown, or its turn finished on the first provider)."
        rows.isEmpty() && fallbackTracker == null ->
            "Session ${sid.value}: fallback tracker not wired on this container — no chain data " +
                "available. Run on a container that wires `AgentProviderFallbackTracker` (CLI, " +
                "Desktop, Server, Android, iOS all do)."
        rows.isEmpty() ->
            "Session ${sid.value}: no provider fallback hops across ${assistantTurns.size} " +
                "assistant turn${if (assistantTurns.size == 1) "" else "s"}."
        else -> {
            val totalHops = rows.sumOf { it.chain.size }
            "Session ${sid.value}: ${rows.size} turn${if (rows.size == 1) "" else "s"} with " +
                "fallback hops ($totalHops total). Most recent: " +
                "turn ${rows.last().messageId} (${rows.last().finish}, " +
                "${rows.last().chain.size} hop${if (rows.last().chain.size == 1) "" else "s"})."
        }
    }

    return ToolResult(
        title = "session_query fallback_history ${sid.value} (${rows.size}/$total)",
        outputForLlm = narrative,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_FALLBACK_HISTORY,
            total = total,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}

private fun finishTag(finish: FinishReason?): String = when (finish) {
    FinishReason.STOP -> "stop"
    FinishReason.END_TURN -> "end_turn"
    FinishReason.MAX_TOKENS -> "max_tokens"
    FinishReason.CONTENT_FILTER -> "content_filter"
    FinishReason.TOOL_CALLS -> "tool_calls"
    FinishReason.ERROR -> "error"
    FinishReason.CANCELLED -> "cancelled"
    null -> "unknown"
}
