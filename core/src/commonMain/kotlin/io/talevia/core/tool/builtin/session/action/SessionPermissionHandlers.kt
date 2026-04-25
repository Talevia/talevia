package io.talevia.core.tool.builtin.session.action

import io.talevia.core.permission.PermissionRulesPersistence
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionActionTool

/**
 * `action=remove_permission_rule` — drop a persisted Always rule
 * matching `(permission, pattern)`. Symmetrical with the
 * interactive `[Always]` add path (which appends to
 * [PermissionRulesPersistence]); without this, removing a previously-
 * granted rule required hand-editing
 * `~/.talevia/permission-rules.json`.
 *
 * Match semantics: exact-match on both `permission` AND `pattern`.
 * Multiple persisted rules with the same pair (legitimate when the
 * user clicked Always on the same prompt twice across sessions) all
 * get removed in one call; `removedRuleCount` carries the dropped
 * count so the agent sees it.
 *
 * No-match is a successful no-op with `removedRuleCount=0` —
 * agents can re-issue without worrying about pre-checking.
 */
internal suspend fun executeRemovePermissionRule(
    sessions: SessionStore,
    permissionRulesPersistence: PermissionRulesPersistence,
    input: SessionActionTool.Input,
    ctx: ToolContext,
): ToolResult<SessionActionTool.Output> {
    val permission = input.permission?.takeIf { it.isNotBlank() }
        ?: error(
            "action=remove_permission_rule requires `permission` (the rule keyword to drop, " +
                "e.g. fs.write). Call session_query(select=permission_rules) to list active rules.",
        )
    val pattern = input.pattern?.takeIf { it.isNotBlank() }
        ?: error(
            "action=remove_permission_rule requires `pattern` (the rule pattern to drop, " +
                "e.g. /tmp/...). Call session_query(select=permission_rules) to list active rules.",
        )
    // sessionId resolution stays consistent with the other actions —
    // the operation is process-wide (rules persistence is per-machine,
    // not per-session) but we still echo a sessionId in the output for
    // the standard SessionActionTool.Output shape.
    val sid = ctx.resolveSessionId(input.sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
        )

    val before = permissionRulesPersistence.load()
    val matched = before.filter { it.permission == permission && it.pattern == pattern }
    val after = before.filterNot { it.permission == permission && it.pattern == pattern }
    if (matched.isNotEmpty()) {
        permissionRulesPersistence.save(after)
    }

    val verb = when {
        matched.isEmpty() -> "no matching rule"
        matched.size == 1 -> "removed 1 rule"
        else -> "removed ${matched.size} duplicate rules"
    }
    return ToolResult(
        title = "remove_permission_rule $permission $pattern",
        outputForLlm = "Persisted Always rules: $verb for ($permission, $pattern). " +
            "${after.size} rule(s) remain in the file. Re-grant via the next interactive " +
            "permission prompt if needed.",
        data = SessionActionTool.Output(
            sessionId = sid.value,
            action = "remove_permission_rule",
            title = session.title,
            removedRuleCount = matched.size,
            remainingRuleCount = after.size,
        ),
    )
}
