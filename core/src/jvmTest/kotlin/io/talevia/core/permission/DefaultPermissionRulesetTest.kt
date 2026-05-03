package io.talevia.core.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression lock for [DefaultPermissionRuleset.rules] — the
 * security-sensitive default permission policy that ships out of the
 * box. Every tool's first turn looks at this list to decide whether a
 * call is silent (ALLOW), prompts the user (ASK), or fails outright
 * (REJECT).
 *
 * Pre-cycle 77 this object had **zero test coverage** (cycle 76 audit
 * verified via grep — no transitive references in any test file).
 * Cycles 69-76 coverage-gap pattern surfaced this gap; cycle 77
 * closes it.
 *
 * The tests here pin each rule's action explicitly so that any
 * unintentional flip (e.g. `media.export.write` accidentally becoming
 * ALLOW during a refactor, silently granting blanket write access to
 * every export call) fails immediately. The test suite is intentionally
 * verbose — a parameterised "for each rule check action" loop would
 * read cleaner but lose the ability to fail one assertion at a time
 * with the exact regressed permission key in the error message.
 */
class DefaultPermissionRulesetTest {

    private val rules = DefaultPermissionRuleset.rules
    private val byPermission: Map<String, PermissionRule> by lazy {
        rules.associateBy { it.permission }
    }

    @Test fun ruleListIsNonEmpty() {
        assertTrue(rules.isNotEmpty(), "default ruleset must ship with at least one rule")
    }

    @Test fun noDuplicatePermissionKeys() {
        // Each permission key should appear exactly once in the default
        // ruleset — duplicates would indicate a merge / copy-paste bug
        // and the resolver's behaviour on duplicates (first-wins?
        // last-wins?) shouldn't be load-bearing.
        val seen = mutableSetOf<String>()
        val dupes = mutableListOf<String>()
        for (rule in rules) {
            if (!seen.add(rule.permission)) dupes += rule.permission
        }
        assertTrue(dupes.isEmpty(), "duplicate permission keys: ${dupes.joinToString()}")
    }

    @Test fun everyRuleUsesWildcardPatternByDefault() {
        // Default ruleset uses pattern="*" for every entry. Specific
        // patterns are added at runtime via PermissionRulesPersistence
        // ("Always allow with this exact path/host/...") — never baked
        // into the default. A regression embedding a specific pattern
        // here would silently scope a default differently than expected.
        for (rule in rules) {
            assertEquals(
                "*",
                rule.pattern,
                "default rule for '${rule.permission}' must use wildcard pattern, not '${rule.pattern}'",
            )
        }
    }

    @Test fun localOnlyToolsAreAllowSilent() {
        // ALLOW rules: tools whose effects are purely local state mutations
        // — no I/O, no cost, no security blast radius. Prompting on every
        // call would make the tool useless.
        val allowExpected = listOf(
            "echo",
            "todowrite",
            "draft_plan",
            "media.import",
            "timeline.write",
            "source.read",
            "source.write",
            "project.read",
            "project.write",
            "session.read",
            "session.write",
            "provider.read",
            "tool.read",
        )
        for (key in allowExpected) {
            val rule = byPermission[key]
            assertNotNull(rule, "ALLOW-expected permission '$key' is missing from default ruleset")
            assertEquals(
                PermissionAction.ALLOW,
                rule.action,
                "permission '$key' must default to ALLOW (local-only state)",
            )
        }
    }

    @Test fun externalEffectToolsAreAskByDefault() {
        // ASK rules: tools that hit network / disk / paid providers /
        // destructive paths. User must confirm by default.
        val askExpected = listOf(
            "media.export.write",
            "media.network.fetch",
            "media.network.upload",
            "timeline.destructive",
            "project.destructive",
            "session.destructive",
            "aigc.generate",
            "aigc.budget",
            "ml.transcribe",
            "ml.describe",
            "fs.read",
            "fs.write",
            "fs.list",
            "bash.exec",
            "web.fetch",
            "web.search",
        )
        for (key in askExpected) {
            val rule = byPermission[key]
            assertNotNull(rule, "ASK-expected permission '$key' is missing from default ruleset")
            assertEquals(
                PermissionAction.ASK,
                rule.action,
                "permission '$key' must default to ASK (external effects / cost / destructive)",
            )
        }
    }

    @Test fun aigcCapabilitiesGateOnUserConfirmation() {
        // VISION §5.2 / §5.7: AIGC calls cost real money; user must
        // explicitly consent. This is the highest-stakes regression
        // surface — silently flipping aigc.generate to ALLOW would
        // let the agent rack up unbounded provider bills without prompts.
        assertEquals(PermissionAction.ASK, byPermission.getValue("aigc.generate").action)
        assertEquals(PermissionAction.ASK, byPermission.getValue("aigc.budget").action)
    }

    @Test fun bashExecAndFilesystemRequireConfirmation() {
        // bash.exec and fs.* are blanket-blast-radius capabilities — a
        // single misclick on "Always allow" with pattern="*" would grant
        // the LLM unbounded shell + filesystem access. Default-ASK is
        // load-bearing: the user-visible prompt is the only thing
        // between the agent and arbitrary execution.
        assertEquals(PermissionAction.ASK, byPermission.getValue("bash.exec").action)
        assertEquals(PermissionAction.ASK, byPermission.getValue("fs.read").action)
        assertEquals(PermissionAction.ASK, byPermission.getValue("fs.write").action)
        assertEquals(PermissionAction.ASK, byPermission.getValue("fs.list").action)
    }

    @Test fun destructiveTiersDifferFromTheirNonDestructiveSiblings() {
        // The `*.write` / `*.destructive` split is meaningful: write is
        // local state mutation (recoverable via revert); destructive
        // is irreversible (delete project, delete snapshot). Ensure
        // they're at different tiers — if both were ALLOW, the
        // distinction would be cosmetic.
        assertEquals(PermissionAction.ALLOW, byPermission.getValue("project.write").action)
        assertEquals(PermissionAction.ASK, byPermission.getValue("project.destructive").action)
        assertEquals(PermissionAction.ALLOW, byPermission.getValue("session.write").action)
        assertEquals(PermissionAction.ASK, byPermission.getValue("session.destructive").action)
        assertEquals(PermissionAction.ALLOW, byPermission.getValue("timeline.write").action)
        assertEquals(PermissionAction.ASK, byPermission.getValue("timeline.destructive").action)
    }

    @Test fun noRulesAtRejectTier() {
        // Default ruleset only uses ALLOW + ASK. REJECT is reserved for
        // server-side overrides (server containers auto-reject ASK by
        // default) and explicit user-set deny rules in
        // PermissionRulesPersistence. Defaults should never hard-reject
        // — a tool that's never callable shouldn't ship registered.
        for (rule in rules) {
            assertTrue(
                rule.action == PermissionAction.ALLOW || rule.action == PermissionAction.ASK,
                "default rule for '${rule.permission}' uses unexpected action ${rule.action}; defaults must be ALLOW or ASK only",
            )
        }
    }
}
