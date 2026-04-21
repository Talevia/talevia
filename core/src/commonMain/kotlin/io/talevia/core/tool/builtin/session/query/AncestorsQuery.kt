package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=ancestors` — parent chain from the given `sessionId` up to the
 * root (a session with `parentId=null`). Child-first order: first row is
 * the immediate parent, last is the root. Cycle-safe via visited set.
 */
internal suspend fun runAncestorsQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val start = requireSession(sessions, input.sessionId, SessionQueryTool.SELECT_ANCESTORS)

    val chain = mutableListOf<SessionQueryTool.AncestorRow>()
    val visited = mutableSetOf<SessionId>()
    visited += start.id

    var cursor = start.parentId
    while (cursor != null) {
        if (!visited.add(cursor)) break
        val ancestor = sessions.getSession(cursor) ?: break
        chain += SessionQueryTool.AncestorRow(
            id = ancestor.id.value,
            projectId = ancestor.projectId.value,
            title = ancestor.title,
            parentId = ancestor.parentId?.value,
            createdAtEpochMs = ancestor.createdAt.toEpochMilliseconds(),
            archived = ancestor.archived,
        )
        cursor = ancestor.parentId
    }

    val page = chain.drop(offset).take(limit)
    val jsonRows = encodeRows(ListSerializer(SessionQueryTool.AncestorRow.serializer()), page)
    val body = if (chain.isEmpty()) {
        "Session ${start.id.value} '${start.title}' is a root (no parent)."
    } else {
        "${page.size} of ${chain.size} ancestor(s) of ${start.id.value} '${start.title}', " +
            "parent-first → root: " +
            page.take(5).joinToString("; ") { "${it.id} '${it.title}'" } +
            if (page.size > 5) "; …" else ""
    }
    return ToolResult(
        title = "session_query ancestors of ${start.id.value} (${page.size}/${chain.size})",
        outputForLlm = body,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_ANCESTORS,
            total = chain.size,
            returned = page.size,
            rows = jsonRows,
        ),
    )
}
