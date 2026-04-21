package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Message
import io.talevia.core.session.SessionStore
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
 * Per-session message listing — completes the `list_sessions` /
 * `describe_session` / `list_messages` triad on the session lane. The agent
 * already has its own in-flight history via `ToolContext.messages`, but the
 * *other* sessions in the store are invisible to it today. Two concrete flows
 * need this:
 *
 *  - **Continuation:** "resume from the edit we did on session X" — before
 *    `session_fork`, the agent needs to scan X's history to find the right
 *    anchor message id.
 *  - **Audit / debug:** the user asks "what did the previous session do with
 *    Mei?" and the agent has to introspect a session other than its own.
 *
 * Returns most-recent first (by `createdAt`) so "last five messages" is the
 * default reasonable read. Each row exposes the common interesting fields per
 * role: assistant rows surface `tokens`, `finish`, `error`, `parentId`; user
 * rows just surface the agent name + model used at the time. The *content*
 * of each message (text parts, tool calls, tool results) is NOT returned —
 * that's a much larger payload and the follow-up tool for it is
 * `describe_message` (a future add) or directly reading parts via a part-level
 * tool. Keeping the list terse matches `list_lockfile_entries` /
 * `list_timeline_clips` / `list_source_nodes` house style.
 *
 * Read-only; permission `session.read` (reuses the keyword the earlier
 * session-lane work introduced).
 */
class ListMessagesTool(
    private val sessions: SessionStore,
) : Tool<ListMessagesTool.Input, ListMessagesTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
        /** Cap on returned messages (default 50, max 500). */
        val limit: Int? = null,
    )

    @Serializable data class Summary(
        val id: String,
        /** `"user"` | `"assistant"`. */
        val role: String,
        val createdAtEpochMs: Long,
        val modelProviderId: String,
        val modelId: String,
        // User-only:
        val agent: String? = null,
        // Assistant-only:
        val parentId: String? = null,
        val tokensInput: Long? = null,
        val tokensOutput: Long? = null,
        val finish: String? = null,
        val error: String? = null,
    )

    @Serializable data class Output(
        val sessionId: String,
        val totalMessages: Int,
        val returnedMessages: Int,
        val messages: List<Summary>,
    )

    override val id: String = "list_messages"
    override val helpText: String =
        "List messages on a session, most recent first. Assistant rows surface tokens / finish " +
            "reason / error; user rows surface agent + model. The message *content* (text, tool " +
            "calls, tool results) is not returned — use a part-level tool to drill in. Default " +
            "limit 50, max 500. Use before session_fork to find an anchor messageId, or for " +
            "cross-session audit when the user asks \"what did we do in session X?\"."
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
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Cap on returned messages (default 50, max 500).")
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
        val all = sessions.listMessages(sid)
        val cap = (input.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        // Most-recent first so "last N" queries land naturally on the tail.
        val ordered = all.sortedByDescending { it.createdAt.toEpochMilliseconds() }.take(cap)

        val summaries = ordered.map { m ->
            when (m) {
                is Message.User -> Summary(
                    id = m.id.value,
                    role = "user",
                    createdAtEpochMs = m.createdAt.toEpochMilliseconds(),
                    modelProviderId = m.model.providerId,
                    modelId = m.model.modelId,
                    agent = m.agent,
                )
                is Message.Assistant -> Summary(
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

        val out = Output(
            sessionId = session.id.value,
            totalMessages = all.size,
            returnedMessages = summaries.size,
            messages = summaries,
        )
        val summary = if (summaries.isEmpty()) {
            "Session ${session.id.value} '${session.title}' has no messages."
        } else {
            "${summaries.size} of ${all.size} message(s) on ${session.id.value} '${session.title}', " +
                "most recent first: " +
                summaries.take(5).joinToString("; ") { "${it.role}/${it.id}" } +
                if (summaries.size > 5) "; …" else ""
        }
        return ToolResult(
            title = "list messages (${summaries.size})",
            outputForLlm = summary,
            data = out,
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }
}
