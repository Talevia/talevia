package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=session_metadata` — single-row drill-down replacing the
 * deleted `describe_session` tool. Returns session metadata plus the
 * same aggregate counts the old tool exposed (message counts, summed
 * token usage, compaction presence, permission-rule count, latest
 * message timestamp).
 *
 * Consolidated under `session_query` per the
 * `debt-consolidate-session-describe-queries` backlog bullet: one
 * `session_query` spec in the LLM's context covers both list-style
 * selects (sessions, messages, parts, …) and per-entity drill-downs
 * (status, session_metadata, message).
 */
internal suspend fun runSessionMetadataQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val sessionId = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_SESSION_METADATA}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
        )

    val messages = sessions.listMessages(sid)
    val userCount = messages.count { it is Message.User }
    val assistantMessages = messages.filterIsInstance<Message.Assistant>()
    val totals = assistantMessages.fold(TokenUsage.ZERO) { acc, m ->
        TokenUsage(
            input = acc.input + m.tokens.input,
            output = acc.output + m.tokens.output,
            reasoning = acc.reasoning + m.tokens.reasoning,
            cacheRead = acc.cacheRead + m.tokens.cacheRead,
            cacheWrite = acc.cacheWrite + m.tokens.cacheWrite,
        )
    }

    val parts = sessions.listSessionParts(sid, includeCompacted = true)
    val hasCompaction = parts.any { it is Part.Compaction }

    val latestMessageAt = messages.maxByOrNull { it.createdAt.toEpochMilliseconds() }
        ?.createdAt?.toEpochMilliseconds()
        ?: session.createdAt.toEpochMilliseconds()

    val row = SessionQueryTool.SessionMetadataRow(
        sessionId = session.id.value,
        projectId = session.projectId.value,
        title = session.title,
        parentId = session.parentId?.value,
        archived = session.archived,
        createdAtEpochMs = session.createdAt.toEpochMilliseconds(),
        updatedAtEpochMs = session.updatedAt.toEpochMilliseconds(),
        latestMessageAtEpochMs = latestMessageAt,
        messageCount = messages.size,
        userMessageCount = userCount,
        assistantMessageCount = assistantMessages.size,
        totalTokensInput = totals.input,
        totalTokensOutput = totals.output,
        totalTokensCacheRead = totals.cacheRead,
        totalTokensCacheWrite = totals.cacheWrite,
        hasCompactionPart = hasCompaction,
        permissionRuleCount = session.permissionRules.size,
        compactingFromMessageId = session.compactingFrom?.value,
    )
    val rows = encodeRows(
        ListSerializer(SessionQueryTool.SessionMetadataRow.serializer()),
        listOf(row),
    )
    val summary = "Session ${session.id.value} '${session.title}' on ${session.projectId.value}: " +
        "$userCount user / ${assistantMessages.size} assistant message(s), " +
        "${totals.input} input + ${totals.output} output token(s)" +
        (if (hasCompaction) ", compacted at least once" else "") +
        (if (session.archived) ", archived" else "") +
        "."
    return ToolResult(
        title = "session_query session_metadata ${session.id.value}",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_SESSION_METADATA,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}
