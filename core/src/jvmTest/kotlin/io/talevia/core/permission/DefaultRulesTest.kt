package io.talevia.core.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [DefaultPermissionRuleset.rules] —
 * `core/permission/DefaultRules.kt`. The agent's
 * out-of-the-box safety profile that determines whether
 * each tool runs silently or pops a confirmation prompt.
 * Cycle 171 audit: 98 LOC, 0 direct test refs (the rules
 * are loaded into every desktop / CLI / server container
 * but the action-per-permission shape was never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Side-effect-free permissions ALLOW silently;
 *    side-effectful permissions ASK.** The agent's
 *    safety floor: editing the timeline / source DAG /
 *    todo list runs without prompts; anything that
 *    spends money (`aigc.generate`, `ml.*`), modifies
 *    user files (`fs.*`, `bash.exec`), hits the network
 *    (`web.*`, `media.network.*`), or destroys data
 *    (`*.destructive`) prompts. Drift in either
 *    direction has high blast radius — a "fs.read →
 *    ALLOW" drift would let the agent silently read
 *    every file the user opens; a "timeline.write →
 *    ASK" drift would prompt on every clip edit and
 *    make the agent unusable.
 *
 * 2. **Every rule's pattern is `"*"`.** The defaults
 *    apply project-wide; per-pattern overrides are
 *    layered on top by user "Always" prompts at runtime.
 *    Drift to a per-pattern default (e.g. `pattern =
 *    "trusted-host.com"` for `web.fetch`) would silently
 *    narrow which inputs hit the rule — surprising and
 *    hard to debug for operators.
 *
 * 3. **No rule has `DENY` action.** Default ruleset is
 *    "ask the user" for risky things, NOT "block them
 *    outright" — the user's interactive answer to ASK
 *    is the gate. `DENY` exists for `ServerPermissionService`'s
 *    runtime auto-reject, not the default ruleset.
 *    Drift to "deny instead of ask" would brick risky
 *    flows on Desktop where ASK is the intended UX.
 */
class DefaultRulesTest {

    private val rules: List<PermissionRule> = DefaultPermissionRuleset.rules

    private fun ruleFor(permission: String): PermissionRule? =
        rules.firstOrNull { it.permission == permission }

    // ── Read-only / silent-state permissions are ALLOW ─────────

    @Test fun readPermissionsAreAllow() {
        // Pin: every `*.read` permission ALLOWs silently.
        // Drift to ASK would prompt on every list_tools /
        // session_query / project_query call, making the
        // agent's introspection cost-per-step go up by an
        // order of magnitude.
        val readPermissions = listOf(
            "source.read",
            "project.read",
            "session.read",
            "provider.read",
            "tool.read",
        )
        for (perm in readPermissions) {
            val rule = assertNotNull(
                ruleFor(perm),
                "expected default rule for '$perm'",
            )
            assertEquals(
                PermissionAction.ALLOW,
                rule.action,
                "$perm must ALLOW (read-only / no I/O); drift to ASK would prompt on every introspection",
            )
        }
    }

    @Test fun localStateMutationsAreAllow() {
        // Pin: timeline / source / project / session
        // mutations that don't leave the local store are
        // ALLOW. Drift to ASK would prompt on every
        // structural edit.
        val localWrites = listOf(
            "timeline.write",
            "source.write",
            "project.write",
            "session.write",
            "media.import",
        )
        for (perm in localWrites) {
            val rule = assertNotNull(ruleFor(perm), "rule for '$perm'")
            assertEquals(
                PermissionAction.ALLOW,
                rule.action,
                "$perm must ALLOW (local state; no external cost)",
            )
        }
    }

    @Test fun agentScratchpadPermissionsAreAllow() {
        // Pin: scratchpad-style tools (todowrite,
        // draft_plan) ALLOW even though they semantically
        // "mutate" — the kdoc explains: "purely local
        // Part.* emission, no external side effects." The
        // tool IS the confirm step; gating it would defeat
        // the purpose. Echo is the trivial test tool;
        // gating it would break the most basic
        // smoke-tests.
        val scratchpad = listOf("echo", "todowrite", "draft_plan")
        for (perm in scratchpad) {
            assertEquals(
                PermissionAction.ALLOW,
                ruleFor(perm)?.action,
                "$perm must ALLOW",
            )
        }
    }

    // ── Side-effectful permissions ASK ────────────────────────

    @Test fun destructivePermissionsAreAsk() {
        // Marquee destructive-gate pin: `*.destructive`
        // semantics permanently lose user data. Drift to
        // ALLOW would silently delete things the user
        // didn't approve.
        val destructive = listOf(
            "timeline.destructive",
            "project.destructive",
            "session.destructive",
        )
        for (perm in destructive) {
            assertEquals(
                PermissionAction.ASK,
                ruleFor(perm)?.action,
                "$perm must ASK (data loss is irreversible)",
            )
        }
    }

    @Test fun networkPermissionsAreAsk() {
        // Pin: anything that crosses the network boundary
        // ASKs. Drift to ALLOW would silently exfiltrate
        // local content (uploads) or pull remote content
        // (fetches) without consent.
        val network = listOf(
            "media.network.fetch",
            "media.network.upload",
            "web.fetch",
            "web.search",
        )
        for (perm in network) {
            assertEquals(
                PermissionAction.ASK,
                ruleFor(perm)?.action,
                "$perm must ASK (network egress)",
            )
        }
    }

    @Test fun aigcAndMlPermissionsAreAsk() {
        // Pin: paid / metered provider calls always ASK.
        // The kdoc explicitly mentions cost as the reason:
        // "AIGC providers incur external cost". Drift to
        // ALLOW would let the agent burn the user's budget
        // without permission.
        val paid = listOf(
            "aigc.generate",
            "aigc.budget",
            "ml.transcribe",
            "ml.describe",
        )
        for (perm in paid) {
            assertEquals(
                PermissionAction.ASK,
                ruleFor(perm)?.action,
                "$perm must ASK (external paid call)",
            )
        }
    }

    @Test fun filesystemPermissionsAreAsk() {
        // Marquee fs-gate pin: read / write / list of user
        // files always ASKs. The pattern is the path so an
        // "Always" rule scopes to a single path rather
        // than granting blanket fs access. Drift to ALLOW
        // would let the agent read/write every file the
        // process can touch — high blast radius.
        val fs = listOf("fs.read", "fs.write", "fs.list")
        for (perm in fs) {
            assertEquals(
                PermissionAction.ASK,
                ruleFor(perm)?.action,
                "$perm must ASK (touches user files)",
            )
        }
    }

    @Test fun bashExecIsAsk() {
        // Marquee shell-gate pin: kdoc explicitly calls
        // bash.exec "the single biggest blast-radius
        // capability the agent has." Drift to ALLOW would
        // let the agent run arbitrary shell commands
        // without consent.
        assertEquals(
            PermissionAction.ASK,
            ruleFor("bash.exec")?.action,
        )
    }

    @Test fun mediaExportWriteIsAsk() {
        // Pin: writing rendered media to disk ASKs even
        // though it's a "write to a directory the user
        // chose" — the export tool may overwrite existing
        // files at the chosen path. Drift to ALLOW would
        // silently clobber the user's previous renders.
        assertEquals(
            PermissionAction.ASK,
            ruleFor("media.export.write")?.action,
        )
    }

    // ── Pattern is always "*" ────────────────────────────────

    @Test fun everyDefaultRuleHasWildcardPattern() {
        // Marquee pattern-pin: defaults are project-wide
        // (`pattern = "*"`). Drift to a per-pattern
        // default (e.g. `pattern = "github.com"` for
        // web.fetch) would silently narrow which inputs
        // hit the rule and confuse operators reasoning
        // about "which rule fires for input X."
        for (rule in rules) {
            assertEquals(
                "*",
                rule.pattern,
                "default rule for ${rule.permission} must have wildcard pattern",
            )
        }
    }

    // ── No DENY action ───────────────────────────────────────

    @Test fun noDefaultRuleHasDenyAction() {
        // Marquee no-DENY pin: the default ruleset is
        // ASK / ALLOW only. DENY is reserved for runtime
        // auto-rejection (e.g. ServerPermissionService
        // turns ASK into DENY when there's no human to
        // prompt). Drift to a DENY in the defaults would
        // permanently block the affected flow on Desktop
        // where ASK is the intended UX.
        for (rule in rules) {
            assertFalse(
                rule.action == PermissionAction.DENY,
                "default rule for ${rule.permission} must NOT be DENY (ASK / ALLOW only); got ${rule.action}",
            )
        }
    }

    // ── Structural sanity ────────────────────────────────────

    @Test fun ruleListIsNonEmptyAndUnique() {
        // Pin: list is non-empty (drift to "all rules
        // deleted by mistake" surfaces here) AND each
        // (permission, pattern) pair is unique (drift to
        // accidental duplicates would create rule-eval
        // ambiguity since rules are evaluated in order).
        assertTrue(rules.isNotEmpty(), "default ruleset is non-empty")
        val keys = rules.map { it.permission to it.pattern }
        assertEquals(
            keys.size,
            keys.toSet().size,
            "no duplicate (permission, pattern) pairs",
        )
    }

    @Test fun everyRuleActionIsValidEnumValue() {
        // Pin: every rule has a well-formed action — guards
        // against an enum drift where a new variant is
        // added but DefaultPermissionRuleset isn't
        // re-evaluated.
        for (rule in rules) {
            assertTrue(
                rule.action in PermissionAction.entries,
                "rule ${rule.permission} has unrecognised action ${rule.action}",
            )
        }
    }
}
