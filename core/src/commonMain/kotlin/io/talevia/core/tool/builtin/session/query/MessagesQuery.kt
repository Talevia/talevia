package io.talevia.core.tool.builtin.session.query

import io.talevia.core.session.Message
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable data class MessageRow(
    val id: String,
    val role: String,
    val createdAtEpochMs: Long,
    val modelProviderId: String,
    val modelId: String,
    val agent: String? = null,
    val parentId: String? = null,
    val tokensInput: Long? = null,
    val tokensOutput: Long? = null,
    val finish: String? = null,
    val error: String? = null,
)

/**
 * `select=messages` — one row per message in a session, most-recent first.
 * Filter: [SessionQueryTool.Input.role] (`"user"` | `"assistant"`).
 */
internal suspend fun runMessagesQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val session = requireSession(sessions, input.sessionId, SessionQueryTool.SELECT_MESSAGES)
    val normalisedRole = input.role?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (normalisedRole != null && normalisedRole !in VALID_ROLES) {
        error("role must be one of ${VALID_ROLES.joinToString(", ")} (got '${input.role}')")
    }

    val all = sessions.listMessages(session.id)
    val filtered = when (normalisedRole) {
        "user" -> all.filterIsInstance<Message.User>()
        "assistant" -> all.filterIsInstance<Message.Assistant>()
        else -> all
    }
    val sorted = filtered.sortedByDescending { it.createdAt.toEpochMilliseconds() }
    val page = sorted.drop(offset).take(limit)

    val rows = page.map { m ->
        when (m) {
            is Message.User -> MessageRow(
                id = m.id.value,
                role = "user",
                createdAtEpochMs = m.createdAt.toEpochMilliseconds(),
                modelProviderId = m.model.providerId,
                modelId = m.model.modelId,
                agent = m.agent,
            )
            is Message.Assistant -> MessageRow(
                id = m.id.value,
                role = "assistant",
                createdAtEpochMs = m.createdAt.toEpochMilliseconds(),
                modelProviderId = m.model.providerId,
                modelId = m.model.modelId,
                parentId = m.parentId.value,
                tokensInput = m.tokens.input,
                tokensOutput = m.tokens.output,
                finish = m.finish?.name?.lowercase(),
                error = m.error,
            )
        }
    }
    val jsonRows = encodeRows(ListSerializer(MessageRow.serializer()), rows)
    val scope = normalisedRole?.let { " role=$it" } ?: ""
    val body = if (rows.isEmpty()) {
        "Session ${session.id.value} '${session.title}' has no messages$scope."
    } else {
        "${rows.size} of ${filtered.size} message(s)$scope on ${session.id.value} '${session.title}', " +
            "most recent first: " +
            rows.take(5).joinToString("; ") { "${it.role}/${it.id}" } +
            if (rows.size > 5) "; …" else ""
    }
    return ToolResult(
        title = "session_query messages (${rows.size}/${filtered.size})",
        outputForLlm = body,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_MESSAGES,
            total = filtered.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
