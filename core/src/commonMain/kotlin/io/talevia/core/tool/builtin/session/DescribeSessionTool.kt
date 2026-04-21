package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Per-session deep inspection — counterpart of [ListSessionsTool] the way
 * `describe_project` is to `list_projects`. Returns Session metadata plus
 * derived aggregates the list view trims:
 *
 *  - `messageCount` / `userMessageCount` / `assistantMessageCount` — quick
 *    answer to "how long is this session?"
 *  - `totalTokensInput` / `totalTokensOutput` / `cacheRead` / `cacheWrite` —
 *    summed across every assistant turn. Useful for the compaction-eager
 *    user asking "how much context have I spent here?"
 *  - `hasCompactionPart` — whether the Compactor has run on this session at
 *    least once. A true value + low recent token usage often signals the
 *    session is about to run into the next compaction trigger.
 *  - `permissionRuleCount` — tells the user how much persistent "Always"
 *    state has accumulated on this session.
 *  - `latestMessageAt` — distinct from `updatedAt`, which the store also
 *    bumps when you touch metadata (title rename, archive flip). For "when
 *    did we last *talk* on this session?" the last-message timestamp is
 *    what the user means.
 *
 * Missing sessionId fails loudly with a `list_sessions` hint. Read-only;
 * permission `session.read`.
 */
class DescribeSessionTool(
    private val sessions: SessionStore,
) : Tool<DescribeSessionTool.Input, DescribeSessionTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
    )

    @Serializable data class Output(
        val id: String,
        val projectId: String,
        val title: String,
        val parentId: String?,
        val archived: Boolean,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        /** `updatedAt` of the most recent message, or `createdAtEpochMs` when the session
         *  has no messages yet. Null-safe: defaults to session.createdAt on an empty session. */
        val latestMessageAtEpochMs: Long,
        val messageCount: Int,
        val userMessageCount: Int,
        val assistantMessageCount: Int,
        val totalTokensInput: Long,
        val totalTokensOutput: Long,
        val totalTokensCacheRead: Long,
        val totalTokensCacheWrite: Long,
        val hasCompactionPart: Boolean,
        val permissionRuleCount: Int,
        val compactingFromMessageId: String?,
    )

    override val id: String = "describe_session"
    override val helpText: String =
        "Per-session deep inspection: session metadata plus message counts, summed token usage, " +
            "compaction state, permission-rule count, and the latest message timestamp. Use after " +
            "list_sessions to pick which session to fork / revert / resume. Read-only."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Session id from list_sessions.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("sessionId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val sid = SessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${input.sessionId} not found. Call list_sessions to discover valid session ids.",
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

        val out = Output(
            id = session.id.value,
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

        val summary = "Session ${session.id.value} '${session.title}' on ${session.projectId.value}: " +
            "$userCount user / ${assistantMessages.size} assistant message(s), " +
            "${totals.input} input + ${totals.output} output token(s)" +
            (if (hasCompaction) ", compacted at least once" else "") +
            (if (session.archived) ", archived" else "") +
            "."
        return ToolResult(
            title = "describe session ${session.id.value}",
            outputForLlm = summary,
            data = out,
        )
    }
}
