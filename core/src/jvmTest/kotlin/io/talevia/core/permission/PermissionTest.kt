package io.talevia.core.permission

import io.talevia.core.JsonConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Direct tests for the cross-cutting types in
 * `core/permission/Permission.kt`: [PermissionAction]
 * (3-variant enum), [PermissionRule] (data carrier),
 * [PermissionSpec] (per-tool declaration with optional
 * input-aware override). Cycle 155 audit: 39 LOC, 0
 * transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`PermissionAction` SerialName values are
 *    lowercase strings.** `@SerialName("allow") ALLOW`
 *    etc. — the on-wire JSON form is the lowercase, NOT
 *    the enum constant name. Drift to UPPER_SNAKE in
 *    serialisation would break every persisted
 *    `PermissionRule` JSON across the user's
 *    `permission-rules.json` files.
 *
 * 2. **`PermissionRule.pattern` defaults to `"*"`.** Per
 *    file shape: a rule constructed with just `(permission,
 *    action)` matches everything that comes through that
 *    permission key. Drift to `""` (empty pattern) would
 *    silently never match anything, breaking every default-
 *    rule entry in `DefaultPermissionRuleset` that omits the
 *    pattern.
 *
 * 3. **`PermissionSpec.fixed(p)` and `PermissionSpec(p)`
 *    produce equivalent specs whose `permissionFrom` lambda
 *    returns `p` regardless of input.** The fixed factory is
 *    the common case; tools wanting per-input dispatch
 *    construct via the primary constructor and override
 *    `permissionFrom`. Drift here would break every tool
 *    that uses `PermissionSpec.fixed(...)` (the vast
 *    majority).
 */
class PermissionTest {

    // ── PermissionAction enum + serialization ───────────────────

    @Test fun permissionActionEnumHasExactlyThreeVariants() {
        // Pin: 3 variants — adding / removing one would change
        // the rule-evaluator's switch exhaustiveness expectations.
        assertEquals(3, PermissionAction.entries.size)
        assertEquals(
            setOf(
                PermissionAction.ALLOW,
                PermissionAction.ASK,
                PermissionAction.DENY,
            ),
            PermissionAction.entries.toSet(),
        )
    }

    @Test fun permissionActionSerialNamesAreLowercase() {
        // The marquee on-wire pin: SerialName values are
        // lowercase. Round-trip through kotlinx.serialization
        // confirms each variant encodes / decodes via its
        // documented string.
        val json = JsonConfig.default
        val ser = PermissionAction.serializer()
        assertEquals("\"allow\"", json.encodeToString(ser, PermissionAction.ALLOW))
        assertEquals("\"ask\"", json.encodeToString(ser, PermissionAction.ASK))
        assertEquals("\"deny\"", json.encodeToString(ser, PermissionAction.DENY))
        // Decode the lowercase back into the enum constants.
        assertEquals(PermissionAction.ALLOW, json.decodeFromString(ser, "\"allow\""))
        assertEquals(PermissionAction.ASK, json.decodeFromString(ser, "\"ask\""))
        assertEquals(PermissionAction.DENY, json.decodeFromString(ser, "\"deny\""))
    }

    // ── PermissionRule defaults + roundtrip ─────────────────────

    @Test fun permissionRulePatternDefaultsToWildcard() {
        // Marquee default pin: omitting `pattern` defaults to
        // "*". DefaultPermissionRuleset relies on this — most
        // entries write `PermissionRule(permission = "...",
        // action = ...)` without the pattern.
        val rule = PermissionRule(
            permission = "fs.read",
            action = PermissionAction.ASK,
        )
        assertEquals("*", rule.pattern, "default pattern is '*' (wildcard)")
    }

    @Test fun permissionRuleExplicitPatternOverridesDefault() {
        val rule = PermissionRule(
            permission = "web.fetch",
            pattern = "https://github.com/*",
            action = PermissionAction.ALLOW,
        )
        assertEquals("https://github.com/*", rule.pattern)
    }

    @Test fun permissionRuleRoundTripsThroughJsonWithLowercaseAction() {
        // Pin: end-to-end JSON round-trip preserves the
        // PermissionRule shape with lowercase action.
        val original = PermissionRule(
            permission = "bash.exec",
            pattern = "git",
            action = PermissionAction.ALLOW,
        )
        val json = JsonConfig.default
        val encoded = json.encodeToString(PermissionRule.serializer(), original)
        // Pin: `action` field in JSON is "allow" not "ALLOW".
        assertEquals(
            true,
            "\"allow\"" in encoded,
            "lowercase action surfaces in JSON; got: $encoded",
        )
        val decoded = json.decodeFromString(PermissionRule.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test fun permissionRuleListSerializerRoundTripsForRulesPersistence() {
        // Pin: the `permission-rules.json` file uses the list
        // serializer (per FilePermissionRulesPersistence).
        // Confirm the list shape works for typical user-grant
        // entries.
        val rules = listOf(
            PermissionRule(permission = "fs.read", action = PermissionAction.ALLOW),
            PermissionRule(
                permission = "web.fetch",
                pattern = "github.com",
                action = PermissionAction.ALLOW,
            ),
        )
        val json = JsonConfig.default
        val encoded = json.encodeToString(ListSerializer(PermissionRule.serializer()), rules)
        val decoded = json.decodeFromString(ListSerializer(PermissionRule.serializer()), encoded)
        assertEquals(rules, decoded)
    }

    // ── PermissionSpec.fixed factory + lambda semantics ─────────

    @Test fun permissionSpecFixedReturnsSpecWithBasePermissionAsDefault() {
        // Pin: PermissionSpec.fixed("p") produces a spec whose
        // `permission` field is "p" AND whose `permissionFrom`
        // lambda returns "p" for any input.
        val spec = PermissionSpec.fixed("project.read")
        assertEquals("project.read", spec.permission)
        // permissionFrom returns the same string for arbitrary
        // input — confirms it's NOT input-aware.
        assertEquals("project.read", spec.permissionFrom("{}"))
        assertEquals(
            "project.read",
            spec.permissionFrom("""{"action":"delete"}"""),
            "fixed permission ignores input",
        )
        // patternFrom default is "*".
        assertEquals("*", spec.patternFrom("anything"))
    }

    @Test fun permissionSpecPrimaryConstructorAllowsPerInputDispatch() {
        // Pin: construct via the primary constructor with an
        // input-aware permissionFrom. The action-tier
        // dispatch use case from the kdoc: snapshot tool's
        // `action=save` → project.write,
        // `action=delete` → project.destructive.
        val spec = PermissionSpec(
            permission = "project.write",
            permissionFrom = { input ->
                if ("\"delete\"" in input) "project.destructive" else "project.write"
            },
        )
        assertEquals("project.write", spec.permission, "base tier echoed in `permission`")
        assertEquals("project.write", spec.permissionFrom("""{"action":"save"}"""))
        assertEquals(
            "project.destructive",
            spec.permissionFrom("""{"action":"delete"}"""),
            "input-aware override fires for delete",
        )
    }

    @Test fun permissionSpecPatternFromDefaultsToWildcard() {
        // Pin: patternFrom default is `{ "*" }`. Drift to
        // `{ inputJson }` would default-leak the entire input
        // into the pattern, which the rule-evaluator then
        // matches literally.
        val spec = PermissionSpec(permission = "any")
        assertEquals("*", spec.patternFrom(""))
        assertEquals("*", spec.patternFrom("anything"))
        assertEquals("*", spec.patternFrom("""{"key":"value"}"""))
    }

    @Test fun permissionSpecPrimaryConstructorWithCustomPatternFrom() {
        // Pin: tools like `bash` populate the pattern with
        // the first command token (so an "Always" rule scopes
        // to that command). The patternFrom lambda is the
        // hook for that.
        val spec = PermissionSpec(
            permission = "bash.exec",
            patternFrom = { input -> input.substringBefore(' ').takeIf { it.isNotBlank() } ?: "*" },
        )
        assertEquals("git", spec.patternFrom("git status"))
        assertEquals("ls", spec.patternFrom("ls -la /tmp"))
        // Empty / blank → wildcard (not empty pattern).
        assertEquals("*", spec.patternFrom(""))
    }

    @Test fun permissionSpecFixedAndPrimaryConstructorAreEquivalentForSamePermission() {
        // Pin: PermissionSpec.fixed("p") is equivalent to
        // PermissionSpec(permission = "p") for behavior
        // (both default lambdas resolve identically). Drift
        // would mean tools using fixed() vs primary
        // constructor get subtly different runtime behavior.
        val viaFactory = PermissionSpec.fixed("session.read")
        val viaConstructor = PermissionSpec(permission = "session.read")
        assertEquals(viaFactory.permission, viaConstructor.permission)
        // Lambdas are equal-by-reference, not value, so we
        // can't `assertEquals(spec1, spec2)` directly. Compare
        // their evaluated outputs instead.
        assertEquals(viaFactory.permissionFrom("x"), viaConstructor.permissionFrom("x"))
        assertEquals(viaFactory.patternFrom("x"), viaConstructor.patternFrom("x"))
    }

    // ── data class equality + hashing sanity ────────────────────

    @Test fun permissionRuleDataClassEquality() {
        // Pin: two rules with same fields ARE equal — the data
        // class generates equals/hashCode. Drift to non-data
        // class would break Set / Map semantics in the
        // evaluator.
        val a = PermissionRule(permission = "p", pattern = "*", action = PermissionAction.ALLOW)
        val b = PermissionRule(permission = "p", pattern = "*", action = PermissionAction.ALLOW)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        // Different action → not equal.
        val c = a.copy(action = PermissionAction.ASK)
        assertNotEquals(a, c)
    }
}
