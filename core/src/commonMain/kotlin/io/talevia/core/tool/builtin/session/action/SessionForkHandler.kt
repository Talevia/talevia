package io.talevia.core.tool.builtin.session.action

import io.talevia.core.MessageId
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionActionTool

/**
 * `session_action(action="fork", anchorMessageId?, newTitle?)` dispatch
 * handler.
 *
 * Cycle 144 absorbed the standalone `fork_session` tool into the
 * session_action dispatcher, mirroring cycle 142–143's
 * `set_tool_enabled` / `set_spend_cap` folds. Branches the parent
 * session into a new sibling pointing at it via `parentId`:
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
 *  - Parent session doesn't exist → loud error with a
 *    `session_query(select=sessions)` hint.
 *  - Anchor doesn't belong to the parent session → loud error (the
 *    store's `require` surfaces it verbatim).
 *
 * Permission: inherits the dispatcher's base `session.write` tier
 * (no per-action override needed — same level as rename / archive /
 * set_tool_enabled / set_spend_cap). Forking is purely local state
 * (no external cost, no network, no filesystem leak), so the default
 * rule continues to be ALLOW; a deny-by-default server deployment
 * can flip it to ASK in config.
 */
internal suspend fun executeSessionFork(
    sessions: SessionStore,
    input: SessionActionTool.Input,
    ctx: ToolContext,
): ToolResult<SessionActionTool.Output> {
    val parentId = ctx.resolveSessionId(input.sessionId)
    val parent = sessions.getSession(parentId)
        ?: error(
            "Session ${parentId.value} not found. Call session_query(select=sessions) to discover " +
                "valid session ids.",
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
        data = SessionActionTool.Output(
            sessionId = parent.id.value,
            action = "fork",
            title = parent.title,
            newTitle = newSession.title,
            forkedSessionId = newSessionId.value,
            forkAnchorMessageId = anchorId?.value,
            forkCopiedMessageCount = copied,
        ),
    )
}
