package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionSpec
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
 * Permanently delete a session and (via SQL ON DELETE CASCADE on
 * `Messages.session_id`) every message + part attached to it.
 *
 * Closes the session mutation surface that prior cycles built up —
 * fork / rename / revert / archive/unarchive were all either reversible
 * or preserving. Delete is the **hard** verb: no un-delete, no cascade-
 * restore from snapshots (there isn't one for sessions at the store
 * level). The `archive_session` tool is the soft alternative; this tool
 * is for when the user really means "nuke this."
 *
 * Permission: `session.destructive` (new keyword, defaults to ASK) —
 * matches `project.destructive` at the permission layer. A different
 * keyword from `session.write` because the blast radius differs and
 * because operators typically want to deny-by-default for the
 * destructive lane while granting the mundane writes. Same precedent
 * set by `project.write` / `project.destructive`.
 *
 * Not cancel-safe, same as [RevertSessionTool]: delete while another
 * `Agent.run` is writing to the target session will race with ongoing
 * message persistence. Callers should `Agent.cancel(sessionId)` first
 * when the target might be live.
 */
class DeleteSessionTool(
    private val sessions: SessionStore,
) : Tool<DeleteSessionTool.Input, DeleteSessionTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
    )

    @Serializable data class Output(
        val sessionId: String,
        val title: String,
        val archived: Boolean,
    )

    override val id: String = "delete_session"
    override val helpText: String =
        "IRREVERSIBLE. Permanently delete a session and every message + part attached " +
            "to it. Use archive_session for the reversible alternative. Cancel any in-flight " +
            "Agent.run on the target session first. Missing session id fails loudly."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.destructive")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Session id to permanently delete.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("sessionId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val sid = SessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${input.sessionId} not found. Call session_query(select=sessions) to discover valid session ids.",
            )

        // Capture metadata before delete so the result can echo it back.
        val snapshot = Output(
            sessionId = session.id.value,
            title = session.title,
            archived = session.archived,
        )

        sessions.deleteSession(sid)

        val archivedNote = if (session.archived) " (was archived)" else ""
        return ToolResult(
            title = "delete session ${session.id.value}",
            outputForLlm = "Deleted session ${session.id.value} '${session.title}'$archivedNote. " +
                "Every message + part on it is gone. This cannot be undone.",
            data = snapshot,
        )
    }
}
