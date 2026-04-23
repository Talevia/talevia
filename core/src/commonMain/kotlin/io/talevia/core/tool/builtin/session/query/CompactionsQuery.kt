package io.talevia.core.tool.builtin.session.query

import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

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
 * `select=compactions` — one row per [Part.Compaction] in a session,
 * most-recent first. Each row carries the full summary text plus the
 * from/to message ids the compaction replaced, so consumers can answer
 * "how many times was this session compacted, and what did each pass
 * cover?" in one call instead of pulling `select=parts&kind=compaction`
 * raw (whose preview caps at 80 chars and loses the message-range
 * metadata).
 *
 * Compaction parts are `includeCompacted=true` only by definition —
 * they're meta about compaction itself, not content to be compacted —
 * so this query always walks the full history regardless of the
 * generic `includeCompacted` toggle.
 */
internal suspend fun runCompactionsQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val session = requireSession(sessions, input.sessionId, SessionQueryTool.SELECT_COMPACTIONS)
    val parts = sessions.listSessionParts(session.id, includeCompacted = true)
    val compactions = parts.filterIsInstance<Part.Compaction>()
    val sorted = compactions.sortedByDescending { it.createdAt.toEpochMilliseconds() }
    val page = sorted.drop(offset).take(limit)

    val rows = page.map { p ->
        CompactionRow(
            partId = p.id.value,
            messageId = p.messageId.value,
            fromMessageId = p.replacedFromMessageId.value,
            toMessageId = p.replacedToMessageId.value,
            summaryText = p.summary,
            compactedAtEpochMs = p.createdAt.toEpochMilliseconds(),
        )
    }
    val jsonRows = encodeRows(ListSerializer(CompactionRow.serializer()), rows)
    val body = if (rows.isEmpty()) {
        "Session ${session.id.value} '${session.title}' has not been compacted yet."
    } else {
        "${rows.size} of ${compactions.size} compaction pass(es) on ${session.id.value}, most recent first: " +
            rows.take(3).joinToString("; ") {
                "${it.fromMessageId}→${it.toMessageId} (${it.summaryText.take(40)}${if (it.summaryText.length > 40) "…" else ""})"
            } +
            if (rows.size > 3) "; …" else ""
    }
    return ToolResult(
        title = "session_query compactions (${rows.size}/${compactions.size})",
        outputForLlm = body,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_COMPACTIONS,
            total = compactions.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
