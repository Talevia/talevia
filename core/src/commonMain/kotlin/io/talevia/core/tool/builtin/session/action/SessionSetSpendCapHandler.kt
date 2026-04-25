package io.talevia.core.tool.builtin.session.action

import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionActionTool
import kotlinx.datetime.Clock

/**
 * `session_action(action="set_spend_cap", capCents=…)` dispatch
 * handler.
 *
 * Cycle 143 absorbed the standalone `set_session_spend_cap` tool into
 * the action dispatcher, mirroring cycle 142's `set_tool_enabled`
 * fold. Same upsert semantics (§3a #2: no `define_/update_` split):
 *  - `capCents = 100` — set or raise cap to $1.00.
 *  - `capCents = 0`  — set cap to "spend nothing"; every subsequent
 *    paid AIGC call ASKs.
 *  - `capCents = null` — clear the cap; no budget gating.
 *
 * The cap itself is consulted by
 * [io.talevia.core.tool.builtin.aigc.AigcBudgetGuard] on every AIGC
 * dispatch — once cumulative session spend reaches it, the guard
 * raises an `aigc.budget` permission ASK. Inspect current spend via
 * `session_query(select=spend)`.
 *
 * Permission: inherits the dispatcher's base `session.write` tier
 * (no per-action override needed — same level as rename / archive).
 */
internal suspend fun executeSessionSetSpendCap(
    sessions: SessionStore,
    clock: Clock,
    input: SessionActionTool.Input,
    ctx: ToolContext,
): ToolResult<SessionActionTool.Output> {
    val capCents = input.capCents
    if (capCents != null) {
        require(capCents >= 0) {
            "capCents must be ≥ 0 (or null to clear); got $capCents. If you meant dollars, " +
                "multiply by 100: \$5.00 = 500."
        }
    }
    val sid = ctx.resolveSessionId(input.sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover " +
                "valid session ids.",
        )

    val previous = session.spendCapCents
    if (previous == capCents) {
        return ToolResult(
            title = "set_spend_cap (no-op)",
            outputForLlm = "Session ${sid.value} already has cap=${formatCap(capCents)}; nothing to do.",
            data = SessionActionTool.Output(
                sessionId = sid.value,
                action = "set_spend_cap",
                title = session.title,
                previousSpendCapCents = previous,
                spendCapCents = capCents,
            ),
        )
    }
    sessions.updateSession(
        session.copy(
            spendCapCents = capCents,
            updatedAt = clock.now(),
        ),
    )
    return ToolResult(
        title = "set session spend cap ${sid.value}",
        outputForLlm = "Session ${sid.value} spend cap: ${formatCap(previous)} → ${formatCap(capCents)}.",
        data = SessionActionTool.Output(
            sessionId = sid.value,
            action = "set_spend_cap",
            title = session.title,
            previousSpendCapCents = previous,
            spendCapCents = capCents,
        ),
    )
}

private fun formatCap(cents: Long?): String = when {
    cents == null -> "none"
    cents == 0L -> "0¢ (block all)"
    else -> "${cents}¢"
}
