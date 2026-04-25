package io.talevia.core.tool.builtin.session

import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionRevert
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
 * Rewind a session to an anchor message and roll the timeline back to the
 * matching snapshot — wraps the long-existing [SessionRevert] primitive,
 * which the CLI already exposes via a `/revert` slash command but the
 * agent itself couldn't call.
 *
 * Closes the session-lane write verb set:
 *  - `session_action(action="fork")` — branch from a point, parent unchanged.
 *  - `rename_session` — cosmetic metadata edit.
 *  - `revert_session` — **this** — destructive rewind of a single session.
 *
 * Semantics, all inherited from [SessionRevert]:
 *  - Every message strictly **after** `anchorMessageId` (in the session's
 *    `(createdAt, id)` order) is deleted, parts included.
 *  - The project's `timeline` is rolled back to the most recent
 *    `Part.TimelineSnapshot` at-or-before the anchor. If there's no such
 *    snapshot (fresh session that never mutated), the timeline is left
 *    untouched and `appliedSnapshotPartId` is null.
 *  - Publishes `BusEvent.SessionReverted` so UIs refresh atomically.
 *
 * **Destructive.** Hard revert — no way to un-revert via tools. The
 * `project_snapshot_action(action=save)` family covers project-level undo; this tool
 * is explicitly for the session half. The help text flags the
 * irreversibility so an agent running against a human user can warn.
 *
 * **Not cancel-safe.** [SessionRevert] does NOT assert the session is
 * idle, mirroring its domain-layer kdoc. Callers that care about
 * mid-flight interleaving should cancel the agent run for the target
 * session first via `Agent.cancel(sessionId)` before firing this tool.
 * The agent will typically only call this tool on the *current*
 * session after the user explicitly asks for it — the cancel is
 * implicit because the agent is the only one running in that session.
 *
 * Permission: `session.write` (reuses the keyword introduced for
 * `session_action(action="fork")`).
 */
class RevertSessionTool(
    private val sessions: SessionStore,
    private val projects: ProjectStore,
    private val bus: EventBus,
) : Tool<RevertSessionTool.Input, RevertSessionTool.Output> {

    private val revert: SessionRevert = SessionRevert(sessions, projects, bus)

    @Serializable data class Input(
        val sessionId: String,
        /** Rewind target. Every message strictly after this id is deleted. */
        val anchorMessageId: String,
        /** Project whose timeline to roll back. */
        val projectId: String,
    )

    @Serializable data class Output(
        val sessionId: String,
        val projectId: String,
        val anchorMessageId: String,
        val deletedMessages: Int,
        /** Null when no timeline snapshot existed at-or-before the anchor. */
        val appliedSnapshotPartId: String?,
        val restoredClipCount: Int,
        val restoredTrackCount: Int,
    )

    override val id: String = "revert_session"
    override val helpText: String =
        "DESTRUCTIVE. Rewind a session to an anchor message: deletes every message strictly after " +
            "the anchor and rolls the project timeline back to the most recent snapshot at-or-before " +
            "the anchor. No un-revert — use project_snapshot_action(action=save) for project-level safety nets. " +
            "Cancel any in-flight Agent.run on the target session before calling. Use when the user " +
            "says \"undo back to where we defined Mei\"."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Session to rewind.")
            }
            putJsonObject("anchorMessageId") {
                put("type", "string")
                put("description", "Target anchor — every later message is deleted.")
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Project whose timeline to roll back.")
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("sessionId"),
                    JsonPrimitive("anchorMessageId"),
                    JsonPrimitive("projectId"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val sessionId = SessionId(input.sessionId)
        val anchorId = MessageId(input.anchorMessageId)
        val projectId = ProjectId(input.projectId)

        val result = revert.revertToMessage(
            sessionId = sessionId,
            anchorMessageId = anchorId,
            projectId = projectId,
        )

        val snapshotNote = when (result.appliedSnapshotPartId) {
            null -> " No timeline snapshot at-or-before anchor; timeline untouched."
            else -> " Timeline restored to snapshot ${result.appliedSnapshotPartId.value} " +
                "(${result.restoredClipCount} clip(s) across ${result.restoredTrackCount} track(s))."
        }
        val summary = "Reverted session ${sessionId.value} to ${anchorId.value}: deleted " +
            "${result.deletedMessages} message(s) after the anchor.$snapshotNote"
        return ToolResult(
            title = "revert session ${sessionId.value}",
            outputForLlm = summary,
            data = Output(
                sessionId = sessionId.value,
                projectId = projectId.value,
                anchorMessageId = anchorId.value,
                deletedMessages = result.deletedMessages,
                appliedSnapshotPartId = result.appliedSnapshotPartId?.value,
                restoredClipCount = result.restoredClipCount,
                restoredTrackCount = result.restoredTrackCount,
            ),
        )
    }
}
