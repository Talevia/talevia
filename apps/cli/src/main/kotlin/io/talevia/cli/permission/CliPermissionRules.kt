package io.talevia.cli.permission

import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionRule

/**
 * CLI-specific transform over the core [io.talevia.core.permission.DefaultPermissionRuleset]:
 * when [autoApprove] is true, every `ASK` rule is upgraded to `ALLOW` so the CLI runs
 * one-shot intents ("make me a nice video", `talevia < script.txt`) without blocking
 * on interactive prompts.
 *
 * Why CLI-only: the piped-stdin execution path reads the ASK prompt's answer from the
 * same input stream the agent is taking instructions from. A prompt mid-flow steals the
 * next queued line and the session silently derails. The desktop and server containers
 * don't share this override — they retain the core ASK defaults because they have their
 * own UX affordances (modal dialog / explicit API deny-by-default).
 *
 * Opt out by setting `TALEVIA_CLI_PROMPT_ON_ASK=1` in the CLI's env, handled by the
 * caller; this function is pure on its inputs.
 */
internal fun cliPermissionRules(
    base: List<PermissionRule>,
    autoApprove: Boolean,
): List<PermissionRule> =
    if (!autoApprove) {
        base
    } else {
        base.map { rule ->
            if (rule.action == PermissionAction.ASK) rule.copy(action = PermissionAction.ALLOW) else rule
        }
    }
