package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * One [io.talevia.core.permission.PermissionRule] surfaced to the
 * agent's plan-time view.
 *
 * `source` distinguishes:
 *  - `"builtin"`: a rule baked into [DefaultPermissionRuleset.rules].
 *    These ship with every container; the agent can rely on them
 *    without needing the user to set anything.
 *  - `"session"`: a rule attached to this specific [Session.permissionRules]
 *    list (typically empty today; populated by future per-session
 *    permission-grant tools or by a fork that copied user-level
 *    overrides into the session model).
 *
 * The persistence-backed "Always" rules CLI / Desktop containers
 * load from `~/.talevia/permission-rules.json` are NOT surfaced here:
 * those are runtime container state, not session state, and a
 * session_query lane should answer "what rules apply to THIS
 * session", not "what's loaded in THIS process".
 *
 * When a session-scoped rule shadows a builtin (same permission +
 * pattern, different action), both rows appear — the agent sees the
 * override AND the underlying default. Precedence is the
 * service's job; surfacing both is the query's job.
 */
@Serializable
data class PermissionRuleRow(
    val permission: String,
    val pattern: String,
    val action: String,
    val source: String,
)

/**
 * `select=permission_rules` — list of permission rules in scope for
 * this session, source-tagged.
 *
 * Today the agent that wants this view has to read the system
 * prompt and reconstruct, since `Session.permissionRules` is buried
 * inside the JSON blob and the static [DefaultPermissionRuleset] is
 * pure code. This select bridges both, returning rows the LLM can
 * pattern-match without a prose parse.
 *
 * Sort: builtin rules first (alphabetical by permission, then
 * pattern), then session-scoped rules (preserving the order the
 * session stored them — usually meaningful since later rules can
 * override earlier ones). `total` is the row count.
 */
internal suspend fun runPermissionRulesQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_PERMISSION_RULES}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )
    val sid = SessionId(sessionIdStr)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover " +
                "valid session ids.",
        )

    val builtins = DefaultPermissionRuleset.rules
        .map { rule ->
            PermissionRuleRow(
                permission = rule.permission,
                pattern = rule.pattern,
                action = rule.action.name,
                source = "builtin",
            )
        }
        .sortedWith(compareBy({ it.permission }, { it.pattern }))
    val sessionRules = session.permissionRules.map { rule ->
        PermissionRuleRow(
            permission = rule.permission,
            pattern = rule.pattern,
            action = rule.action.name,
            source = "session",
        )
    }

    val rowsAll = builtins + sessionRules
    val total = rowsAll.size
    val rows = rowsAll.drop(offset).take(limit)
    val jsonRows = encodeRows(ListSerializer(PermissionRuleRow.serializer()), rows)

    val builtinCount = builtins.size
    val sessionCount = sessionRules.size
    val narrative = if (rowsAll.isEmpty()) {
        "Session ${sid.value} has no permission rules in scope (no builtins wired? unusual)."
    } else {
        "${rows.size} of $total rule(s) for session ${sid.value}: " +
            "$builtinCount builtin + $sessionCount session-scoped."
    }

    return ToolResult(
        title = "session_query permission_rules ${sid.value} (${rows.size}/$total)",
        outputForLlm = narrative,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_PERMISSION_RULES,
            total = total,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
