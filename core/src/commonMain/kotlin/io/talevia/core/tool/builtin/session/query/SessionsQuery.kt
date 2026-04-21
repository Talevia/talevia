package io.talevia.core.tool.builtin.session.query

import io.talevia.core.ProjectId
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=sessions` — one row per session, most-recent first by updatedAt.
 * Filters: [SessionQueryTool.Input.projectId],
 * [SessionQueryTool.Input.includeArchived].
 */
internal suspend fun runSessionsQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val pid = input.projectId?.takeIf { it.isNotBlank() }?.let { ProjectId(it) }
    val includeArchived = input.includeArchived ?: false
    val all = if (includeArchived) {
        sessions.listSessionsIncludingArchived(pid)
    } else {
        sessions.listSessions(pid)
    }
    val sorted = all.sortedByDescending { it.updatedAt.toEpochMilliseconds() }
    val page = sorted.drop(offset).take(limit)

    val rows = page.map { s ->
        SessionQueryTool.SessionRow(
            id = s.id.value,
            projectId = s.projectId.value,
            title = s.title,
            parentId = s.parentId?.value,
            createdAtEpochMs = s.createdAt.toEpochMilliseconds(),
            updatedAtEpochMs = s.updatedAt.toEpochMilliseconds(),
            archived = s.archived,
        )
    }
    val jsonRows = encodeRows(ListSerializer(SessionQueryTool.SessionRow.serializer()), rows)
    val scopeLabel = pid?.let { "project ${it.value}" } ?: "all projects"
    val body = if (rows.isEmpty()) {
        "No sessions on $scopeLabel."
    } else {
        "${rows.size} session(s) on $scopeLabel: " +
            rows.take(5).joinToString("; ") { "${it.id} '${it.title}'" } +
            if (rows.size > 5) "; …" else ""
    }
    return ToolResult(
        title = "session_query sessions (${rows.size}/${all.size})",
        outputForLlm = body,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_SESSIONS,
            total = all.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
