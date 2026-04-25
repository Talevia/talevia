package io.talevia.core.tool.builtin.session.action

import io.talevia.core.SessionId
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionActionTool
import kotlinx.datetime.Clock

/**
 * Per-action handlers for [SessionActionTool]'s lifecycle verbs:
 * `archive` / `unarchive` / `rename` / `delete`. Extracted from the
 * dispatcher class so the dispatcher stays focused on schema +
 * routing; mirrors `clip_action`'s ClipMutateHandlers / ClipCreateHandlers
 * split.
 *
 * Why grouped under "lifecycle": all four mutate the [Session] row's
 * persisted shape (`archived` flag / `title` / row presence). The
 * permission-rules action belongs to a different store (rules
 * persistence) and lives in [executeRemovePermissionRule] instead.
 */

internal suspend fun executeSessionArchive(
    sessions: SessionStore,
    clock: Clock,
    input: SessionActionTool.Input,
    ctx: ToolContext,
): ToolResult<SessionActionTool.Output> {
    val sid = ctx.resolveSessionId(input.sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
        )

    val wasArchived = session.archived
    if (!wasArchived) {
        sessions.updateSession(session.copy(archived = true, updatedAt = clock.now()))
    }

    val verb = if (wasArchived) "was already archived" else "archived"
    return ToolResult(
        title = "archive session ${sid.value}",
        outputForLlm = "Session ${sid.value} '${session.title}' ($verb). session_query(select=sessions) will now " +
            "exclude it; use session_action(action=unarchive) to restore.",
        data = SessionActionTool.Output(
            sessionId = sid.value,
            action = "archive",
            title = session.title,
            wasAlreadyInTargetState = wasArchived,
        ),
    )
}

internal suspend fun executeSessionUnarchive(
    sessions: SessionStore,
    clock: Clock,
    input: SessionActionTool.Input,
    ctx: ToolContext,
): ToolResult<SessionActionTool.Output> {
    val sid = ctx.resolveSessionId(input.sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. The archived-session recovery handle is its " +
                "id — make sure the caller has the right string.",
        )

    val wasUnarchived = !session.archived
    if (session.archived) {
        sessions.updateSession(session.copy(archived = false, updatedAt = clock.now()))
    }

    val verb = if (wasUnarchived) "was already live" else "unarchived"
    return ToolResult(
        title = "unarchive session ${sid.value}",
        outputForLlm = "Session ${sid.value} '${session.title}' ($verb). Now visible in session_query(select=sessions) again.",
        data = SessionActionTool.Output(
            sessionId = sid.value,
            action = "unarchive",
            title = session.title,
            wasAlreadyInTargetState = wasUnarchived,
        ),
    )
}

internal suspend fun executeSessionRename(
    sessions: SessionStore,
    clock: Clock,
    input: SessionActionTool.Input,
    ctx: ToolContext,
): ToolResult<SessionActionTool.Output> {
    val newTitle = input.newTitle
        ?: error("action=rename requires `newTitle`")
    require(newTitle.isNotBlank()) { "newTitle must not be blank" }

    val sid = ctx.resolveSessionId(input.sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
        )

    val previousTitle = session.title
    if (previousTitle == newTitle) {
        return ToolResult(
            title = "rename session (no-op)",
            outputForLlm = "Session ${sid.value} already titled '$previousTitle' — nothing to do.",
            data = SessionActionTool.Output(
                sessionId = sid.value,
                action = "rename",
                title = previousTitle,
                previousTitle = previousTitle,
                newTitle = newTitle,
            ),
        )
    }

    sessions.updateSession(
        session.copy(
            title = newTitle,
            updatedAt = clock.now(),
        ),
    )

    return ToolResult(
        title = "rename session ${sid.value}",
        outputForLlm = "Renamed session ${sid.value}: '$previousTitle' → '$newTitle'.",
        data = SessionActionTool.Output(
            sessionId = sid.value,
            action = "rename",
            title = newTitle,
            previousTitle = previousTitle,
            newTitle = newTitle,
        ),
    )
}

internal suspend fun executeSessionDelete(
    sessions: SessionStore,
    input: SessionActionTool.Input,
): ToolResult<SessionActionTool.Output> {
    val rawSessionId = input.sessionId
        ?: error(
            "action=delete requires explicit `sessionId` (the owning session can't self-delete mid-dispatch).",
        )
    val sid = SessionId(rawSessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session $rawSessionId not found. Call session_query(select=sessions) to discover valid session ids.",
        )

    val snapshot = SessionActionTool.Output(
        sessionId = session.id.value,
        action = "delete",
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
