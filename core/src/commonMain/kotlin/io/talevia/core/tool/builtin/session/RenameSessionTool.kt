package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
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
 * Retitle a session — the simplest session write-verb, paired with
 * [ForkSessionTool] on the session-lane write side. `SessionTitler` runs
 * automatically on first user message to give a session a placeholder
 * title ("Untitled" / "New session") or an LLM-derived one, but the user
 * often wants to rename *later* once the session's real focus is clear
 * ("call this 'Mei's story arc' now that we've settled that").
 *
 * The CLI has a /rename slash command; this tool lets the agent itself
 * respond to "rename this session to X" without the user leaving the
 * chat.
 *
 * Mutation path: read session, copy with new `title` + bump `updatedAt`,
 * `updateSession`. Publishes `BusEvent.SessionUpdated` via the store's
 * existing contract. Empty / blank new title is rejected — `SessionTitler`
 * defaults would win on a round-trip and the rename wouldn't stick.
 *
 * Permission: reuses `session.write` from the prior cycle's fork work.
 */
class RenameSessionTool(
    private val sessions: SessionStore,
    private val clock: Clock = Clock.System,
) : Tool<RenameSessionTool.Input, RenameSessionTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
        val newTitle: String,
    )

    @Serializable data class Output(
        val sessionId: String,
        val previousTitle: String,
        val newTitle: String,
    )

    override val id: String = "rename_session"
    override val helpText: String =
        "Rename a session. Empty title rejected. Publishes BusEvent.SessionUpdated so UI consumers " +
            "(session sidebar, picker) refresh. Use when the user asks to retitle after the session's " +
            "focus has become clear."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Id of the session to rename.")
            }
            putJsonObject("newTitle") {
                put("type", "string")
                put("description", "The new title. Must be non-blank.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("sessionId"), JsonPrimitive("newTitle"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.newTitle.isNotBlank()) { "newTitle must not be blank" }

        val sid = SessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${input.sessionId} not found. Call list_sessions to discover valid session ids.",
            )

        val previousTitle = session.title
        if (previousTitle == input.newTitle) {
            return ToolResult(
                title = "rename session (no-op)",
                outputForLlm = "Session ${sid.value} already titled '$previousTitle' — nothing to do.",
                data = Output(
                    sessionId = sid.value,
                    previousTitle = previousTitle,
                    newTitle = input.newTitle,
                ),
            )
        }

        sessions.updateSession(
            session.copy(
                title = input.newTitle,
                updatedAt = clock.now(),
            ),
        )

        return ToolResult(
            title = "rename session ${sid.value}",
            outputForLlm = "Renamed session ${sid.value}: '$previousTitle' → '${input.newTitle}'.",
            data = Output(
                sessionId = sid.value,
                previousTitle = previousTitle,
                newTitle = input.newTitle,
            ),
        )
    }
}
