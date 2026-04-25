package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.datetime.Clock

/**
 * Bind the dispatching session to a freshly-created project as part of the
 * same atomic tool dispatch that created it.
 *
 * Background: `switch_project` carries a mid-run guard (VISION §5.6) that
 * rejects rebinds while the agent is `Generating` / `AwaitingTool` /
 * `Compacting` — the next turn's `ToolContext.currentProjectId` would
 * otherwise change under its feet. That guard is correct for *external*
 * rebinds, but `project_action create` / `create_from_template` running
 * inside `AwaitingTool` is an *internal* rebind: the model just minted a
 * project precisely to start working in it. Forcing it to call
 * `switch_project` afterwards always trips the guard (we are still
 * `AwaitingTool` for the very dispatch that created the project), leaving
 * the session pointed at the previous project and the model unable to
 * proceed without a user nudge.
 *
 * This helper bypasses `SwitchProjectTool` entirely: it updates the
 * session's `currentProjectId` directly and publishes the same
 * [BusEvent.SessionProjectBindingChanged] event subscribers already
 * watch, so UI / metrics sinks see one binding change, not zero.
 *
 * - `sessions == null` → no-op (test rigs / legacy compositions that
 *   construct `ProjectActionTool` without a session store).
 * - Same-id is a no-op (the session was already bound here, e.g. an
 *   idempotent re-create with `projectId` echoing the current binding).
 */
internal suspend fun autoBindSessionToProject(
    sessions: SessionStore?,
    clock: Clock,
    ctx: ToolContext,
    projectId: ProjectId,
) {
    if (sessions == null) return
    val sid = ctx.sessionId
    val session = sessions.getSession(sid) ?: return
    val previous = session.currentProjectId
    if (previous?.value == projectId.value) return
    sessions.updateSession(
        session.copy(
            currentProjectId = projectId,
            updatedAt = clock.now(),
        ),
    )
    ctx.publishEvent(
        BusEvent.SessionProjectBindingChanged(
            sessionId = sid,
            previousProjectId = previous,
            newProjectId = projectId,
        ),
    )
}
