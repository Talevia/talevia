package io.talevia.core.tool.builtin.session.query

import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=forks` — immediate child sessions of the given `sessionId`,
 * oldest first. One hop only — deeper traversal is the caller's job via
 * repeated queries.
 */
internal suspend fun runForksQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val parent = requireSession(sessions, input.sessionId, SessionQueryTool.SELECT_FORKS)
    val children = sessions.listChildSessions(parent.id)
    val page = children.drop(offset).take(limit)

    val rows = page.map { s ->
        SessionQueryTool.ForkRow(
            id = s.id.value,
            projectId = s.projectId.value,
            title = s.title,
            createdAtEpochMs = s.createdAt.toEpochMilliseconds(),
            updatedAtEpochMs = s.updatedAt.toEpochMilliseconds(),
            archived = s.archived,
        )
    }
    val jsonRows = encodeRows(ListSerializer(SessionQueryTool.ForkRow.serializer()), rows)
    val body = if (rows.isEmpty()) {
        "Session ${parent.id.value} '${parent.title}' has no forks."
    } else {
        "${rows.size} of ${children.size} fork(s) of ${parent.id.value} '${parent.title}', oldest first: " +
            rows.take(5).joinToString("; ") { "${it.id} '${it.title}'" } +
            if (rows.size > 5) "; …" else ""
    }
    return ToolResult(
        title = "session_query forks of ${parent.id.value} (${rows.size}/${children.size})",
        outputForLlm = body,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_FORKS,
            total = children.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
