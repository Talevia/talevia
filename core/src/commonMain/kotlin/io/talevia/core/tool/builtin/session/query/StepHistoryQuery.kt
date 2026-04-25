package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * One step in an assistant turn — pairs [Part.StepStart] with its
 * matching [Part.StepFinish] and counts any [Part.Tool] parts that
 * fell between them. Today the data lives split across both part
 * types and the tools-during-step relation is implicit (createdAt
 * ordering); the agent that wants "per-step model / tokens /
 * toolCallCount / elapsed" timeline has to reconstruct it from the
 * `parts` select or grep `~/.talevia/cli.log`.
 *
 * Steps are 0-indexed within an assistant message. `model` is the
 * assistant message's `model` (the same model drives every step in
 * one message). Pending steps (StepStart without StepFinish — agent
 * killed or crashed) carry `finish=null` + `elapsedMs=null` so the
 * agent can spot a stuck turn.
 */
@Serializable
data class StepHistoryRow(
    val messageId: String,
    val stepIndex: Int,
    val model: String,
    /** Wall-clock elapsed (StepFinish - StepStart). Null for pending step. */
    val elapsedMs: Long? = null,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    /** [io.talevia.core.session.FinishReason] name, or null when StepFinish hasn't landed. */
    val finishReason: String? = null,
    /** Count of `Part.Tool` parts whose createdAt is between StepStart and StepFinish (or now, for pending). */
    val toolCallCount: Int,
    val createdAtEpochMs: Long,
)

/**
 * `select=step_history` — flatten Assistant.parts' interleaved
 * StepStart/StepFinish/Tool sequence into a per-step timeline.
 *
 * Rationale: a single assistant turn often runs multiple steps —
 * "text → tool_calls → tool_results → text" — and each step has its
 * own (model, finish, tokens, elapsed). The `messages` /
 * `tool_calls` selects flatten across steps; this select preserves
 * the per-step granularity that's load-bearing for "which step
 * burned all the tokens?" debugging.
 *
 * Walks `listMessagesWithParts(sessionId, includeCompacted=false)`
 * once. For each Assistant message, emits one row per StepStart
 * paired with its earliest-following StepFinish (or null when the
 * turn was killed mid-step). Tool parts are bucketed by createdAt
 * range. Optional `messageId` narrows to one turn.
 *
 * Oldest-first across the session.
 */
internal suspend fun runStepHistoryQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_STEP_HISTORY}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionIdStr)

    val messagesWithParts = sessions.listMessagesWithParts(sid, includeCompacted = false)
    val rowsAll = messagesWithParts.flatMap { mwp ->
        val asst = mwp.message as? Message.Assistant ?: return@flatMap emptyList()
        if (input.messageId != null && asst.id.value != input.messageId) return@flatMap emptyList()

        val partsByCreatedAt = mwp.parts.sortedBy { it.createdAt }
        val starts = partsByCreatedAt.filterIsInstance<Part.StepStart>()
        val finishes = partsByCreatedAt.filterIsInstance<Part.StepFinish>()
        val tools = partsByCreatedAt.filterIsInstance<Part.Tool>()

        val modelStr = "${asst.model.providerId}/${asst.model.modelId}"
        starts.mapIndexed { i, start ->
            // Pair: nearest StepFinish whose createdAt >= this start's
            // createdAt AND that hasn't been claimed by an earlier
            // start. Simple O(n²) but n is tiny (≤10 steps in any
            // realistic turn).
            val finish = finishes
                .filter { it.createdAt >= start.createdAt }
                .firstOrNull { f ->
                    starts.none { otherStart ->
                        otherStart.createdAt > start.createdAt && otherStart.createdAt <= f.createdAt
                    }
                }
            val rangeEnd = finish?.createdAt
            val toolCallCount = tools.count { tool ->
                tool.createdAt >= start.createdAt &&
                    (rangeEnd == null || tool.createdAt <= rangeEnd)
            }
            val elapsed = finish?.let {
                (it.createdAt - start.createdAt).inWholeMilliseconds
            }
            StepHistoryRow(
                messageId = asst.id.value,
                stepIndex = i,
                model = modelStr,
                elapsedMs = elapsed,
                tokensIn = finish?.tokens?.input ?: 0L,
                tokensOut = finish?.tokens?.output ?: 0L,
                finishReason = finish?.finish?.name,
                toolCallCount = toolCallCount,
                createdAtEpochMs = start.createdAt.toEpochMilliseconds(),
            )
        }
    }

    val total = rowsAll.size
    val rows = rowsAll.drop(offset).take(limit)
    val jsonRows = encodeRows(ListSerializer(StepHistoryRow.serializer()), rows)

    val narrative = when {
        rows.isEmpty() && input.messageId != null ->
            "No steps recorded for messageId='${input.messageId}' in session ${sid.value}."
        rows.isEmpty() ->
            "Session ${sid.value} has no recorded assistant steps."
        else -> {
            val pending = rowsAll.count { it.finishReason == null }
            val totalTokens = rowsAll.sumOf { it.tokensIn + it.tokensOut }
            "${rows.size} of $total step(s) for session ${sid.value} (oldest first). " +
                "${rowsAll.size - pending} finished, $pending pending, $totalTokens total tokens."
        }
    }

    return ToolResult(
        title = "session_query step_history ${sid.value} (${rows.size}/$total)",
        outputForLlm = narrative,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_STEP_HISTORY,
            total = total,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
