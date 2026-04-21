package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState
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
 * Filter a session's part stream down to `Part.Tool` entries only — the
 * "show me every generate_image call I made" / "which tools did this
 * session actually use?" query. Reconstructible from
 * `list_messages` + `describe_message`, but that's O(1 + N) per session
 * whereas this tool is a single store read over parts.
 *
 * Optional `toolId` narrows to one tool kind (e.g. `"generate_image"`,
 * `"synthesize_speech"`). Optional `includeCompacted` decides whether to
 * include tool calls that have been compacted out of the LLM context
 * (default `true` so a post-hoc audit sees everything; set `false` to
 * mirror the agent's current working view).
 *
 * Read-only, `session.read`. Missing session id fails loudly with a
 * `list_sessions` hint.
 */
class ListToolCallsTool(
    private val sessions: SessionStore,
) : Tool<ListToolCallsTool.Input, ListToolCallsTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
        /** Optional filter — only parts whose toolId matches. */
        val toolId: String? = null,
        /** Include compacted tool parts? Default true (full audit view). */
        val includeCompacted: Boolean = true,
        /** Cap on returned rows. Default 100, max 1000. */
        val limit: Int? = null,
    )

    @Serializable data class Summary(
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

    @Serializable data class Output(
        val sessionId: String,
        val totalToolParts: Int,
        val returnedToolParts: Int,
        val toolCalls: List<Summary>,
    )

    override val id: String = "list_tool_calls"
    override val helpText: String =
        "List every Part.Tool entry in a session — optionally filtered by toolId. Most-recent-first " +
            "by createdAt. Use for \"which tools has this session used?\" / \"how many generate_image " +
            "calls did we make?\" audits without walking list_messages + describe_message per turn. " +
            "includeCompacted=true (default) surfaces compacted tool calls too."
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
            putJsonObject("toolId") {
                put("type", "string")
                put(
                    "description",
                    "Optional filter — only tool parts whose toolId matches (e.g. generate_image).",
                )
            }
            putJsonObject("includeCompacted") {
                put("type", "boolean")
                put("description", "Include compacted tool parts? Default true.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Cap on returned rows (default 100, max 1000).")
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

        val parts = sessions.listSessionParts(sid, includeCompacted = input.includeCompacted)
        val toolParts = parts.filterIsInstance<Part.Tool>()
        val filtered = if (input.toolId.isNullOrBlank()) toolParts else toolParts.filter { it.toolId == input.toolId }
        val cap = (input.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        // Most-recent-first matches list_messages convention.
        val capped = filtered.sortedByDescending { it.createdAt.toEpochMilliseconds() }.take(cap)

        val rows = capped.map { p ->
            Summary(
                partId = p.id.value,
                messageId = p.messageId.value,
                toolId = p.toolId,
                callId = p.callId.value,
                state = when (p.state) {
                    is ToolState.Pending -> "pending"
                    is ToolState.Running -> "running"
                    is ToolState.Completed -> "completed"
                    is ToolState.Failed -> "error"
                },
                title = p.title,
                createdAtEpochMs = p.createdAt.toEpochMilliseconds(),
                compactedAtEpochMs = p.compactedAt?.toEpochMilliseconds(),
            )
        }

        val out = Output(
            sessionId = session.id.value,
            totalToolParts = filtered.size,
            returnedToolParts = rows.size,
            toolCalls = rows,
        )
        val scope = input.toolId?.let { " toolId=$it" } ?: ""
        val summary = if (rows.isEmpty()) {
            "Session ${session.id.value} '${session.title}' has no tool calls$scope."
        } else {
            "${rows.size} of ${filtered.size} tool call(s)$scope on ${session.id.value}, " +
                "most recent first: " +
                rows.take(5).joinToString("; ") { "${it.toolId}[${it.state}]" } +
                if (rows.size > 5) "; …" else ""
        }
        return ToolResult(
            title = "list tool calls (${rows.size})",
            outputForLlm = summary,
            data = out,
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000
    }
}
