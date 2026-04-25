package io.talevia.core.tool.builtin.session.action

import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionActionTool
import kotlinx.datetime.Clock

/**
 * `session_action(action="set_tool_enabled", …)` dispatch handler.
 *
 * Cycle 142 absorbed the standalone `set_tool_enabled` tool into
 * `session_action`, mirroring the precedent set by
 * `set_system_prompt` / `export_bus_trace` (both action arms). The
 * write itself is unchanged: flips a single
 * [io.talevia.core.session.Session.disabledToolIds] entry; disabled
 * tools are filtered at `ToolRegistry.specs(ctx)` *before* the LLM
 * sees them, so "stop using generate_video" → call this with
 * `enabled=false` and the model genuinely cannot dispatch it for the
 * rest of the session.
 *
 * Upsert shape (§3a #2: no define_/update_ split):
 *  - `enabled=false` adds `toolId` to the disabled set (no-op when
 *    already disabled).
 *  - `enabled=true` removes `toolId` from the disabled set (no-op
 *    when already enabled).
 *
 * Does **not** validate `toolId` against the live registry — the
 * disabled set is per-session persisted state and may legitimately
 * reference an env-gated tool that isn't loaded right now (it'll
 * still be filtered out if it's ever loaded).
 *
 * Permission: inherits the dispatcher's base `session.write` tier
 * (no per-action override needed — same level as rename / archive).
 */
internal suspend fun executeSessionSetToolEnabled(
    sessions: SessionStore,
    clock: Clock,
    input: SessionActionTool.Input,
    ctx: ToolContext,
): ToolResult<SessionActionTool.Output> {
    val toolId = input.toolId
    require(!toolId.isNullOrBlank()) {
        "action=set_tool_enabled requires non-blank `toolId`"
    }
    val enabled = input.enabled
        ?: error("action=set_tool_enabled requires `enabled` (true to enable, false to disable)")
    val sid = ctx.resolveSessionId(input.sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover " +
                "valid session ids.",
        )
    val currentlyDisabled = toolId in session.disabledToolIds
    val shouldDisable = !enabled
    val changed = currentlyDisabled != shouldDisable

    if (!changed) {
        return ToolResult(
            title = "set_tool_enabled (no-op)",
            outputForLlm = "Session ${sid.value}: $toolId already " +
                (if (enabled) "enabled" else "disabled") + "; nothing to do.",
            data = SessionActionTool.Output(
                sessionId = sid.value,
                action = "set_tool_enabled",
                title = session.title,
                toolId = toolId,
                enabled = enabled,
                toolEnabledChanged = false,
            ),
        )
    }

    val nextDisabled = if (shouldDisable) {
        session.disabledToolIds + toolId
    } else {
        session.disabledToolIds - toolId
    }
    sessions.updateSession(
        session.copy(
            disabledToolIds = nextDisabled,
            updatedAt = clock.now(),
        ),
    )
    val verb = if (enabled) "enabled" else "disabled"
    return ToolResult(
        title = "$verb $toolId for ${sid.value}",
        outputForLlm = "Session ${sid.value}: $toolId → $verb. " +
            (if (enabled) "Visible in next turn's tool spec." else "Hidden from next turn's tool spec."),
        data = SessionActionTool.Output(
            sessionId = sid.value,
            action = "set_tool_enabled",
            title = session.title,
            toolId = toolId,
            enabled = enabled,
            toolEnabledChanged = true,
        ),
    )
}
