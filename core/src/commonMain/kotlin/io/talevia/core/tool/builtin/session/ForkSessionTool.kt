package io.talevia.core.tool.builtin.session

import io.talevia.core.MessageId
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
 * Branch a session — the write-verb counterpart of the session-lane read
 * tools. `SessionStore.fork(parentId, newTitle, anchorMessageId)` has
 * existed at the domain layer for a while, exposed only via the CLI
 * `/fork` slash command; this tool lets the agent itself branch a session
 * in response to "let's try a different approach from here" / "fork from
 * message X and continue" flows.
 *
 * Semantics mirror `SessionStore.fork`:
 *  - A new `SessionId` is minted and the branch is written with
 *    `parentId = <source>`. That backlink is what lets
 *    `describe_session` / `session_query(select=sessions)` render the fork graph.
 *  - When `anchorMessageId` is set, only messages at-or-before the
 *    anchor (in the parent's `(createdAt, id)` order) are copied —
 *    everything after is dropped from the branch. Lets the agent
 *    prune a tangent before continuing on the branch.
 *  - When `anchorMessageId` is null, the entire parent history is
 *    copied.
 *  - `newTitle` optional; default is `"<parent title> (fork)"` to
 *    match the store's default phrasing.
 *
 * Failure cases:
 *  - Parent session doesn't exist → loud error with a `session_query(select=sessions)`
 *    hint.
 *  - Anchor doesn't belong to the parent session → loud error (the
 *    store's `require` surfaces it verbatim).
 *
 * Permission: new `session.write` keyword. Forking is a write: it
 * creates a new session row and copies potentially many messages. But
 * it's purely local state (no external cost, no network, no filesystem
 * leak), so the default rule is ALLOW — matching `source.write` /
 * `project.write`. A deny-by-default server deployment can flip it to
 * ASK in config.
 */
class ForkSessionTool(
    private val sessions: SessionStore,
) : Tool<ForkSessionTool.Input, ForkSessionTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
        /** Optional: only copy messages at-or-before this id. Omit to copy everything. */
        val anchorMessageId: String? = null,
        /** Optional new title. Defaults to "<parent title> (fork)". */
        val newTitle: String? = null,
    )

    @Serializable data class Output(
        val newSessionId: String,
        val parentSessionId: String,
        val anchorMessageId: String?,
        val newTitle: String,
        val copiedMessageCount: Int,
    )

    override val id: String = "fork_session"
    override val helpText: String =
        "Branch a session. Creates a new session whose parentId points at the source; copies " +
            "messages up to optional anchorMessageId (omit to copy everything). Use for \"try a " +
            "different approach from here\" flows. The branch is a separate session — the agent's " +
            "running session is unaffected. Returns the new session id the caller can use with " +
            "session_query(select=sessions) / describe_session / session-revert etc."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Id of the session to branch from.")
            }
            putJsonObject("anchorMessageId") {
                put("type", "string")
                put(
                    "description",
                    "Optional anchor — only messages at-or-before this id (in parent's " +
                        "(createdAt, id) order) are copied. Everything after is dropped from " +
                        "the branch. Omit to copy the whole history.",
                )
            }
            putJsonObject("newTitle") {
                put("type", "string")
                put("description", "Optional title for the branch. Default \"<parent title> (fork)\".")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("sessionId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val parentId = SessionId(input.sessionId)
        val parent = sessions.getSession(parentId)
            ?: error(
                "Session ${input.sessionId} not found. Call session_query(select=sessions) to discover valid session ids.",
            )

        val anchorId = input.anchorMessageId?.takeIf { it.isNotBlank() }?.let { MessageId(it) }
        val newSessionId = sessions.fork(
            parentId = parentId,
            newTitle = input.newTitle?.takeIf { it.isNotBlank() },
            anchorMessageId = anchorId,
        )
        val newSession = sessions.getSession(newSessionId)
            ?: error("Fork created new session $newSessionId but store lookup returned null")
        val copied = sessions.listMessages(newSessionId).size

        val anchorNote = if (anchorId != null) " at anchor ${anchorId.value}" else ""
        val summary = "Forked session ${parent.id.value} '${parent.title}' into " +
            "${newSessionId.value} '${newSession.title}'$anchorNote (copied $copied message(s))."
        return ToolResult(
            title = "fork session ${parent.id.value}",
            outputForLlm = summary,
            data = Output(
                newSessionId = newSessionId.value,
                parentSessionId = parent.id.value,
                anchorMessageId = anchorId?.value,
                newTitle = newSession.title,
                copiedMessageCount = copied,
            ),
        )
    }
}
