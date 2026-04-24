package io.talevia.core.tool.builtin.session.query

import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable data class ToolCallRow(
    val partId: String,
    val messageId: String,
    val toolId: String,
    val callId: String,
    /** `"pending"` | `"running"` | `"completed"` | `"error"`. */
    val state: String,
    val title: String? = null,
    val createdAtEpochMs: Long,
    val compactedAtEpochMs: Long? = null,
)

/**
 * `select=tool_calls` — one row per [Part.Tool] in a session, most-recent
 * first. Filters: [SessionQueryTool.Input.toolId],
 * [SessionQueryTool.Input.includeCompacted].
 */
internal suspend fun runToolCallsQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val session = requireSession(sessions, input.sessionId, SessionQueryTool.SELECT_TOOL_CALLS)
    val includeCompacted = input.includeCompacted ?: true

    val parts = sessions.listSessionParts(session.id, includeCompacted = includeCompacted)
    val toolParts = parts.filterIsInstance<Part.Tool>()
    val filtered = if (input.toolId.isNullOrBlank()) toolParts else toolParts.filter { it.toolId == input.toolId }
    val sorted = filtered.sortedByDescending { it.createdAt.toEpochMilliseconds() }
    val page = sorted.drop(offset).take(limit)

    val rows = page.map { p ->
        ToolCallRow(
            partId = p.id.value,
            messageId = p.messageId.value,
            toolId = p.toolId,
            callId = p.callId.value,
            state = when (p.state) {
                is ToolState.Pending -> "pending"
                is ToolState.Running -> "running"
                is ToolState.Completed -> "completed"
                is ToolState.Failed -> "error"
                is ToolState.Cancelled -> "cancelled"
            },
            title = p.title,
            createdAtEpochMs = p.createdAt.toEpochMilliseconds(),
            compactedAtEpochMs = p.compactedAt?.toEpochMilliseconds(),
        )
    }
    val jsonRows = encodeRows(ListSerializer(ToolCallRow.serializer()), rows)
    val scope = input.toolId?.let { " toolId=$it" } ?: ""
    val body = if (rows.isEmpty()) {
        "Session ${session.id.value} '${session.title}' has no tool calls$scope."
    } else {
        "${rows.size} of ${filtered.size} tool call(s)$scope on ${session.id.value}, " +
            "most recent first: " +
            rows.take(5).joinToString("; ") { "${it.toolId}[${it.state}]" } +
            if (rows.size > 5) "; …" else ""
    }
    return ToolResult(
        title = "session_query tool_calls (${rows.size}/${filtered.size})",
        outputForLlm = body,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_TOOL_CALLS,
            total = filtered.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
