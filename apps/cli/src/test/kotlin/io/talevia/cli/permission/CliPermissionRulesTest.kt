package io.talevia.cli.permission

import io.talevia.core.permission.DefaultPermissionRuleset
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliPermissionRulesTest {

    @Test fun autoApproveFlipsAskRulesToAllow() {
        val base = listOf(
            PermissionRule("fs.write", "*", PermissionAction.ASK),
            PermissionRule("aigc.generate", "*", PermissionAction.ASK),
            PermissionRule("timeline.write", "*", PermissionAction.ALLOW),
        )
        val out = cliPermissionRules(base, autoApprove = true)
        assertEquals(
            listOf(PermissionAction.ALLOW, PermissionAction.ALLOW, PermissionAction.ALLOW),
            out.map { it.action },
        )
    }

    @Test fun autoApproveOffLeavesRulesUntouched() {
        val base = listOf(
            PermissionRule("fs.write", "*", PermissionAction.ASK),
            PermissionRule("timeline.write", "*", PermissionAction.ALLOW),
        )
        val out = cliPermissionRules(base, autoApprove = false)
        assertEquals(base, out, "autoApprove=false must be identity")
    }

    @Test fun denyRulesAreNotUpgraded() {
        // Defensive: a future DENY (e.g. a project-local override) must survive
        // auto-approve untouched, since the user explicitly forbade it.
        val base = listOf(
            PermissionRule("bash.exec", "rm -rf *", PermissionAction.DENY),
            PermissionRule("fs.write", "*", PermissionAction.ASK),
        )
        val out = cliPermissionRules(base, autoApprove = true)
        assertEquals(PermissionAction.DENY, out[0].action)
        assertEquals(PermissionAction.ALLOW, out[1].action)
    }

    @Test fun defaultRulesetHasNoAskAfterAutoApprove() {
        // End-to-end: running auto-approve over the real core default must leave zero
        // ASK entries, so the CLI is guaranteed never to block on a permission prompt.
        val out = cliPermissionRules(DefaultPermissionRuleset.rules, autoApprove = true)
        assertTrue(
            out.none { it.action == PermissionAction.ASK },
            "auto-approved rules still contain ASK entries: " +
                out.filter { it.action == PermissionAction.ASK }.joinToString { it.permission },
        )
        // And the transformation isn't a no-op — the core default set actually has ASK rows.
        assertTrue(
            DefaultPermissionRuleset.rules.any { it.action == PermissionAction.ASK },
            "core default set has no ASK rules — this test is no longer meaningful",
        )
    }
}
