package io.talevia.core.tool.builtin.session.query

import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable data class PartRow(
    val partId: String,
    val kind: String,
    val messageId: String,
    val createdAtEpochMs: Long,
    val compactedAtEpochMs: Long? = null,
    val preview: String,
)

/**
 * `select=parts` — one row per Part in a session, most-recent first.
 * Filters: [SessionQueryTool.Input.kind],
 * [SessionQueryTool.Input.includeCompacted].
 */
internal suspend fun runPartsQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val session = requireSession(sessions, input.sessionId, SessionQueryTool.SELECT_PARTS)
    val requestedKind = input.kind?.takeIf { it.isNotBlank() }
    if (requestedKind != null && requestedKind !in VALID_PART_KINDS) {
        error("kind must be one of ${VALID_PART_KINDS.joinToString(", ")} (got '$requestedKind')")
    }
    val includeCompacted = input.includeCompacted ?: true

    val all = sessions.listSessionParts(session.id, includeCompacted = includeCompacted)
    val filtered = if (requestedKind == null) all else all.filter { it.kindDiscriminator() == requestedKind }
    val sorted = filtered.sortedByDescending { it.createdAt.toEpochMilliseconds() }
    val page = sorted.drop(offset).take(limit)

    val rows = page.map { p ->
        PartRow(
            partId = p.id.value,
            kind = p.kindDiscriminator(),
            messageId = p.messageId.value,
            createdAtEpochMs = p.createdAt.toEpochMilliseconds(),
            compactedAtEpochMs = p.compactedAt?.toEpochMilliseconds(),
            preview = p.preview(),
        )
    }
    val jsonRows = encodeRows(ListSerializer(PartRow.serializer()), rows)
    val scope = requestedKind?.let { " kind=$it" } ?: ""
    val body = if (rows.isEmpty()) {
        "No parts on session ${session.id.value}$scope."
    } else {
        "${rows.size} of ${filtered.size} part(s)$scope on ${session.id.value}, " +
            "most recent first: " +
            rows.take(5).joinToString("; ") { "${it.kind}:${it.preview.take(24)}" } +
            if (rows.size > 5) "; …" else ""
    }
    return ToolResult(
        title = "session_query parts (${rows.size}/${filtered.size})",
        outputForLlm = body,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_PARTS,
            total = filtered.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
