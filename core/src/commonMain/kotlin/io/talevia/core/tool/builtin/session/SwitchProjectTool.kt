package io.talevia.core.tool.builtin.session

import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.ProjectStore
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
 * Bind the session to a new `currentProjectId` (VISION §5.4 — the cwd-analogue
 * for multi-project workflows). The binding is injected into every subsequent
 * turn's system prompt and exposed on [ToolContext.currentProjectId], so the
 * agent doesn't need to re-derive "which project am I editing?" from the
 * transcript after every user message.
 *
 * Verifies the target project exists via [ProjectStore.get] **before**
 * committing — an unknown id would otherwise leave the session pointing at a
 * ghost and every downstream tool call would fail loudly with the same error
 * spread across N turns.
 *
 * Same-id is a no-op: the session's `currentProjectId` is already what was
 * requested, so we don't bump `updatedAt` or emit a SessionUpdated event.
 *
 * Companion: `list_projects` / `create_project` to pick or build a project;
 * `describe_session` to read the current binding back.
 */
class SwitchProjectTool(
    private val sessions: SessionStore,
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
) : Tool<SwitchProjectTool.Input, SwitchProjectTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
        val projectId: String,
    )

    @Serializable data class Output(
        val sessionId: String,
        val previousProjectId: String?,
        val currentProjectId: String,
        val changed: Boolean,
    )

    override val id: String = "switch_project"
    override val helpText: String =
        "Set the session's currentProjectId — the cwd-analogue for multi-project work. " +
            "Verifies the project exists before committing; same-id is a no-op. The binding " +
            "is injected into each subsequent turn's system prompt and exposed on " +
            "ToolContext so tools can default projectId in the future."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Id of the session to rebind.")
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Id of the project to bind the session to. Must exist.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("sessionId"), JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(input.projectId.isNotBlank()) { "projectId must not be blank" }

        val sid = SessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${input.sessionId} not found. Call list_sessions to discover valid session ids.",
            )

        val pid = ProjectId(input.projectId)
        val previous = session.currentProjectId?.value

        if (previous == pid.value) {
            return ToolResult(
                title = "switch project (no-op)",
                outputForLlm =
                    "Session ${sid.value} is already bound to project ${pid.value} — nothing to do.",
                data = Output(
                    sessionId = sid.value,
                    previousProjectId = previous,
                    currentProjectId = pid.value,
                    changed = false,
                ),
            )
        }

        // Fail loud on unknown project id — otherwise downstream tool calls would
        // spray the same not-found error across every subsequent turn.
        projects.get(pid)
            ?: error(
                "Project ${pid.value} does not exist. Call list_projects to discover valid " +
                    "project ids, or create_project to make a new one.",
            )

        sessions.updateSession(
            session.copy(
                currentProjectId = pid,
                updatedAt = clock.now(),
            ),
        )

        return ToolResult(
            title = "switch project → ${pid.value}",
            outputForLlm =
                "Session ${sid.value} now bound to project ${pid.value}" +
                    if (previous != null) " (was: $previous)." else ".",
            data = Output(
                sessionId = sid.value,
                previousProjectId = previous,
                currentProjectId = pid.value,
                changed = true,
            ),
        )
    }
}
