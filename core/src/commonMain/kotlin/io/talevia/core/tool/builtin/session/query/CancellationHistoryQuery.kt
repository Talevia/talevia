package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * One assistant turn that was cancelled mid-flight (message-level
 * `finish == CANCELLED`). Carries the stamp from [Message.Assistant.error]
 * as `reason`, the model at the time of the turn, and a count of the
 * in-flight Tool parts that `finalizeCancelled` had to stamp with the
 * `"cancelled: <reason>"` prefix (cycle-50).
 *
 * `inFlightToolIds` exposes the distinct `toolId`s of those parts so the
 * operator / agent can answer "which tools were running when I hit
 * Ctrl-C?" without a second `parts` dispatch. The count and the id list
 * distinguish between "LLM had just started dispatching three
 * generate_image calls when I cancelled" and "LLM was idle (no parts in
 * flight) when I cancelled" — different cost / recoverability
 * signatures.
 */
@Serializable
data class CancellationHistoryRow(
    val messageId: String,
    val createdAtEpochMs: Long,
    val model: String,
    /** The `Message.Assistant.error` string that `finalizeCancelled` stamped; null on races where the error field stayed empty. */
    val reason: String?,
    /** Number of Part.Tool parts under this message whose state is Failed with a "cancelled:" message prefix. */
    val inFlightToolCallCount: Int,
    /** Distinct toolIds of those parts, sorted. */
    val inFlightToolIds: List<String>,
)

/**
 * `select=cancellation_history` — cancelled-turn post-mortem ledger
 * (complements `select=run_failure` which only surfaces on
 * `FinishReason.ERROR` turns, and `select=fallback_history` which
 * surfaces provider chains). The agent / operator can answer "how
 * many times was this session cancelled and which tools were
 * running each time?" without tailing the bus.
 *
 * Requires sessionId. Optional `messageId` narrows to one specific
 * cancelled turn. Oldest-first ordering mirrors `run_failure` and
 * `fallback_history`.
 *
 * Data flow: enumerate `Message.Assistant` with `finish =
 * CANCELLED`, then for each count the session's `Part.Tool` parts
 * whose `state is ToolState.Failed && state.message.startsWith
 * ("cancelled:")` AND whose `messageId` matches. That's the exact
 * stamp `finalizeCancelled` writes (cycle-50), so the count aligns
 * 1:1 with what the Agent cancellation path produced.
 *
 * Closes `session-query-cancellation-history` from cycle-51
 * repopulate. Rubric §5.4.
 */
internal suspend fun runCancellationHistoryQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_CANCELLATION_HISTORY}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionIdStr)

    val messagesWithParts = sessions.listMessagesWithParts(sid, includeCompacted = true)
    val cancelledAssistants = messagesWithParts
        .mapNotNull { mwp ->
            val msg = mwp.message as? Message.Assistant ?: return@mapNotNull null
            if (msg.finish != FinishReason.CANCELLED) return@mapNotNull null
            mwp to msg
        }

    val targetMessageId = input.messageId
    val scoped = if (targetMessageId != null) {
        cancelledAssistants.filter { it.second.id.value == targetMessageId }
    } else {
        cancelledAssistants
    }

    val rowsAll = scoped.map { (mwp, msg) ->
        // `finalizeCancelled` rewrites in-flight tool parts from
        // Pending/Running to the dedicated [ToolState.Cancelled] variant
        // (cycle-62 upgrade from `Failed("cancelled: <reason>")`).
        // Counting by variant avoids any prefix-parsing ambiguity with
        // ordinary tool failures in the same turn.
        val cancelledTools = mwp.parts
            .filterIsInstance<Part.Tool>()
            .filter { it.state is ToolState.Cancelled }
        val toolIds = cancelledTools.map { it.toolId }.distinct().sorted()
        CancellationHistoryRow(
            messageId = msg.id.value,
            createdAtEpochMs = msg.createdAt.toEpochMilliseconds(),
            model = "${msg.model.providerId}/${msg.model.modelId}",
            reason = msg.error,
            inFlightToolCallCount = cancelledTools.size,
            inFlightToolIds = toolIds,
        )
    }

    val total = rowsAll.size
    val rows = rowsAll.drop(offset).take(limit)

    val jsonRows = encodeRows(ListSerializer(CancellationHistoryRow.serializer()), rows)

    val narrative = when {
        rows.isEmpty() && targetMessageId != null ->
            "No cancelled assistant turn with messageId='$targetMessageId' in session '${sid.value}' " +
                "(either the id is unknown, or that message finished normally / with an error)."
        rows.isEmpty() ->
            "Session ${sid.value} has no cancelled assistant turns recorded."
        rows.size == 1 -> {
            val r = rows.single()
            val toolsPart = if (r.inFlightToolCallCount == 0) {
                "no tool calls in flight"
            } else {
                "${r.inFlightToolCallCount} in-flight tool call${if (r.inFlightToolCallCount == 1) "" else "s"} " +
                    "(${r.inFlightToolIds.joinToString(", ")})"
            }
            "Cancelled turn ${r.messageId} (${r.model}): reason='${r.reason ?: "unknown"}', $toolsPart."
        }
        else -> "${rows.size} cancelled turn(s) in session ${sid.value} (oldest first). " +
            "Most recent: ${rows.last().messageId} with ${rows.last().inFlightToolCallCount} in-flight tool call(s)."
    }

    return ToolResult(
        title = "session_query cancellation_history ${sid.value} (${rows.size}/$total)",
        outputForLlm = narrative,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_CANCELLATION_HISTORY,
            total = total,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
