package io.talevia.core.tool.builtin.session.action

import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.ProjectStore
import io.talevia.core.session.SessionRevert
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionActionTool

/**
 * `session_action(action="revert", sessionId, anchorMessageId, projectId)`
 * dispatch handler.
 *
 * Cycle 146 absorbed the standalone `revert_session` tool into the
 * dispatcher, completing the session-cluster fold series. Wraps the
 * long-existing [SessionRevert] domain primitive — same semantics
 * the standalone tool documented:
 *  - Every message strictly after `anchorMessageId` (in the
 *    session's `(createdAt, id)` order) is deleted, parts included.
 *  - The project's `timeline` rolls back to the most recent
 *    `Part.TimelineSnapshot` at-or-before the anchor. If there's no
 *    such snapshot (fresh session that never mutated), the timeline
 *    is left untouched and `revertAppliedSnapshotPartId` is null.
 *  - Publishes `BusEvent.SessionReverted` so UIs refresh atomically.
 *
 * **Destructive.** Hard revert — no way to un-revert via tools. The
 * `project_action(kind="snapshot", args={action="save"})` family covers project-level
 * undo; this verb is explicitly for the session half.
 *
 * **Not cancel-safe.** [SessionRevert] does NOT assert the session
 * is idle; mid-flight interleaving is the caller's responsibility.
 * The agent typically only fires this on the *current* session
 * after a user explicit ask, so the cancel is implicit (the agent
 * is the only writer in that session).
 *
 * Permission: inherits the dispatcher's base `session.write` tier
 * (preserves the standalone tool's pre-fold permission level —
 * revert mutates session + project state but doesn't drop the
 * session entirely).
 *
 * Missing-deps fail-loud at dispatch time matches the
 * `import` / `export` / `set_x` patterns:
 *  - `projects=null` (the SessionActionTool was wired without a
 *    ProjectStore — every production AppContainer wires it now,
 *    so this is a test-rig signal).
 *  - `bus=null` (same shape — production wires it; tests opting
 *    into revert must provide one).
 */
internal suspend fun executeSessionRevert(
    sessions: SessionStore,
    projects: ProjectStore?,
    bus: EventBus?,
    input: SessionActionTool.Input,
): ToolResult<SessionActionTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error("action=revert requires `sessionId`")
    val anchorIdStr = input.anchorMessageId
        ?: error("action=revert requires `anchorMessageId`")
    val projectIdStr = input.projectId
        ?: error("action=revert requires `projectId` (the project whose timeline to roll back)")

    val resolvedProjects = projects
        ?: error(
            "action=revert requires the SessionActionTool to be constructed with a ProjectStore — " +
                "this AppContainer didn't wire one. Register the tool with `projects=` so the " +
                "revert path can roll back the project timeline.",
        )
    val resolvedBus = bus
        ?: error(
            "action=revert requires the SessionActionTool to be constructed with an EventBus — " +
                "this AppContainer didn't wire one. Register the tool with `bus=` so the " +
                "revert path can publish BusEvent.SessionReverted for UI refresh.",
        )

    val sessionId = SessionId(sessionIdStr)
    val anchorId = MessageId(anchorIdStr)
    val projectId = ProjectId(projectIdStr)

    val revert = SessionRevert(sessions, resolvedProjects, resolvedBus)
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

    val session = sessions.getSession(sessionId)
    val title = session?.title.orEmpty()

    return ToolResult(
        title = "revert session ${sessionId.value}",
        outputForLlm = summary,
        data = SessionActionTool.Output(
            sessionId = sessionId.value,
            action = "revert",
            title = title,
            revertDeletedMessages = result.deletedMessages,
            revertAppliedSnapshotPartId = result.appliedSnapshotPartId?.value,
            revertRestoredClipCount = result.restoredClipCount,
            revertRestoredTrackCount = result.restoredTrackCount,
        ),
    )
}
