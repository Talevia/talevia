package io.talevia.core.tool.builtin.session.action

import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionActionTool
import kotlinx.datetime.Clock

/**
 * `session_action(action="set_system_prompt", systemPromptOverride=…)`.
 *
 * Writes [io.talevia.core.session.Session.systemPromptOverride] verbatim:
 *
 * - Non-null → set the override (empty string is a legitimate "run with
 *   no system prompt" override and is NOT conflated with null).
 * - Null → clear the override so subsequent turns fall back to the
 *   Agent's constructor-level default.
 *
 * Idempotent (writing the same value as before is a no-op except for
 * `updatedAt`). The next [io.talevia.core.agent.Agent.run] step picks
 * up the new value via the per-step `getSession` re-read in
 * `Agent.runLoop` — so a `set_system_prompt` mid-run takes effect on
 * the very next turn without restarting the Agent.
 */
internal suspend fun executeSessionSetSystemPrompt(
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

    val previous = session.systemPromptOverride
    val next = input.systemPromptOverride
    val changed = previous != next
    if (changed) {
        sessions.updateSession(
            session.copy(systemPromptOverride = next, updatedAt = clock.now()),
        )
    }

    val verb = when {
        !changed && next == null -> "no override (unchanged)"
        !changed -> "override unchanged"
        next == null -> "override cleared"
        next.isEmpty() -> "override set to empty string (no system prompt)"
        else -> "override set to ${next.length}-char prompt"
    }
    return ToolResult(
        title = "set system prompt for session ${sid.value}",
        outputForLlm = "Session ${sid.value} '${session.title}': $verb. The next turn will use " +
            (if (next == null) "the Agent default system prompt." else "the new override."),
        data = SessionActionTool.Output(
            sessionId = sid.value,
            action = "set_system_prompt",
            title = session.title,
            previousSystemPromptOverride = previous,
            newSystemPromptOverride = next,
        ),
    )
}
