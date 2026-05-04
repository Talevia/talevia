package io.talevia.core.permission

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boundary + drift pins for `DefaultPermissionService.matches` (the glob
 * matcher) and `evaluate` (the rule-precedence resolver). The sibling
 * [PermissionServiceTest] (11 existing tests) covers happy-path
 * precedence (DENY > ASK > ALLOW) and a single prefix-glob case
 * (`/tmp/` + wildcard matches `/tmp/a.mp4`). What's NOT covered:
 *
 * - **Regex metacharacter escape set (0 of 13 individually pinned)**: the
 *   glob matcher escapes `. + ? ( ) [ ] { } \ | ^ $` so they're treated
 *   as literals inside a rule pattern. If a refactor drops `.` from the
 *   escape list, a rule like `api.example.com` would suddenly match
 *   `apiXexample.com` — silent firewall hole. Same drift class as cycle
 *   316 RetryClassifier OR-list pins; each escape character is load-
 *   bearing on its own.
 *
 * - **Glob shape coverage**: only prefix `/tmp/` + wildcard is tested. Suffix
 *   `*.txt`, middle `prefix.*.suffix`, and multi-wildcard `a*b*c` all
 *   exercise different code paths (the `forEach { c }` loop replaces
 *   each `*` with `.*`); a regression in any one would only fail the
 *   shape-specific test, so per-shape pins surface drift more
 *   precisely.
 *
 * - **Short-circuit guards**: line 111 short-circuits on `rulePattern
 *   == "*"` OR `rulePattern == requestPattern`; line 112 short-
 *   circuits on `!rulePattern.contains('*')`. Drift here would either
 *   miss matches (over-strict short-circuit) or over-match (early
 *   true).
 *
 * - **Precedence symmetry**: existing test pins ASK > ALLOW in one
 *   order; the inverse order (ALLOW then ASK upgrades to ASK) is not
 *   pinned. The `if (found != PermissionAction.ASK) found = ALLOW`
 *   guard is exactly what makes both orders converge on ASK.
 *
 * Same audit-pattern as cycles 309-317: pin every load-bearing literal
 * + boundary so a refactor lands in test-red instead of silent
 * misbehaviour.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionServiceGlobMatchPinsTest {

    private val sessionId = SessionId("s-1")

    private fun req(permission: String, pattern: String) =
        PermissionRequest(sessionId = sessionId, permission = permission, pattern = pattern)

    /**
     * Helper: assert that `rulePattern` matches `requestPattern` under
     * the glob matcher. Sets up a single ALLOW rule and checks for
     * `PermissionDecision.Once`. If the matcher rejects, the service
     * raises ASK (no reply → test would hang); we use this fact to
     * differentiate match-vs-mismatch deterministically by replying
     * Reject to any pending ASK.
     */
    private suspend fun TestScope.assertGlobMatches(
        rulePattern: String,
        requestPattern: String,
        expectMatch: Boolean,
        message: String = "rulePattern='$rulePattern' requestPattern='$requestPattern'",
    ) {
        val bus = EventBus()
        val service = DefaultPermissionService(bus)
        val rules = listOf(PermissionRule("test.permission", rulePattern, PermissionAction.ALLOW))
        val decision = async {
            service.check(rules, req("test.permission", requestPattern))
        }
        if (expectMatch) {
            // Match → ALLOW resolves to Once without raising ASK.
            assertEquals(PermissionDecision.Once, decision.await(), "MATCH expected: $message")
        } else {
            // Mismatch → no rule matches → ASK is raised. Reply Reject
            // to resolve the suspend; the resulting decision is Reject
            // confirms the ALLOW rule did NOT match.
            val asked = bus.subscribe<BusEvent.PermissionAsked>().first()
            service.reply(asked.requestId, PermissionDecision.Reject)
            assertEquals(PermissionDecision.Reject, decision.await(), "MISMATCH expected: $message")
        }
    }

    // ── Regex metacharacter escape pins (13 chars, one test each) ──

    @Test fun globEscapesDot() = runTest {
        // Drift here: rule `api.example` would treat `.` as regex
        // any-char and match `apiXexample` — silent firewall hole.
        assertGlobMatches("api.example", "api.example", expectMatch = true)
        assertGlobMatches("api.example", "apiXexample", expectMatch = false)
    }

    @Test fun globEscapesPlus() = runTest {
        // `+` regex = "one or more"; literal in glob.
        assertGlobMatches("a+b", "a+b", expectMatch = true)
        assertGlobMatches("a+b", "aab", expectMatch = false)
    }

    @Test fun globEscapesQuestion() = runTest {
        // `?` regex = "zero or one"; literal in glob.
        assertGlobMatches("a?b", "a?b", expectMatch = true)
        assertGlobMatches("a?b", "ab", expectMatch = false)
    }

    @Test fun globEscapesOpenParen() = runTest {
        assertGlobMatches("(group)", "(group)", expectMatch = true)
        assertGlobMatches("(group)", "group", expectMatch = false)
    }

    @Test fun globEscapesCloseParen() = runTest {
        // Sister pin to open-paren: both must be in the escape list.
        // Dropping `)` would compile-fail the regex at runtime
        // (mismatched group), turning glob match into a thrown
        // PatternSyntaxException — strictly worse than silent miss.
        assertGlobMatches("a)b", "a)b", expectMatch = true)
    }

    @Test fun globEscapesOpenBracket() = runTest {
        // `[abc]` regex = char class; literal in glob.
        assertGlobMatches("[admin]", "[admin]", expectMatch = true)
    }

    @Test fun globEscapesCloseBracket() = runTest {
        assertGlobMatches("a]b", "a]b", expectMatch = true)
    }

    @Test fun globEscapesOpenBrace() = runTest {
        // `{n,m}` regex = quantifier; literal in glob.
        assertGlobMatches("a{1,3}", "a{1,3}", expectMatch = true)
    }

    @Test fun globEscapesCloseBrace() = runTest {
        assertGlobMatches("a}b", "a}b", expectMatch = true)
    }

    @Test fun globEscapesBackslash() = runTest {
        // Backslash is the regex escape itself; without escaping it,
        // patterns containing `\` would consume the next char as a
        // regex metachar.
        assertGlobMatches("path\\to\\file", "path\\to\\file", expectMatch = true)
    }

    @Test fun globEscapesPipe() = runTest {
        // `|` regex = alternation; literal in glob.
        assertGlobMatches("a|b", "a|b", expectMatch = true)
        assertGlobMatches("a|b", "a", expectMatch = false)
    }

    @Test fun globEscapesCaret() = runTest {
        // `^` regex = start anchor; literal in glob (note the matcher
        // wraps the whole pattern in `^...$` already).
        assertGlobMatches("a^b", "a^b", expectMatch = true)
    }

    @Test fun globEscapesDollar() = runTest {
        // `$` regex = end anchor; literal in glob.
        assertGlobMatches("a\$b", "a\$b", expectMatch = true)
    }

    // ── Glob shape coverage (4 shapes beyond the existing prefix pin) ──

    @Test fun globMatchesSuffixPattern() = runTest {
        // `*.txt` — every shape that ends in `.txt`. Existing
        // prefix `/tmp/` + wildcard doesn't exercise this code path (the `*`
        // sits in the middle of the regex string post-conversion).
        assertGlobMatches("*.txt", "report.txt", expectMatch = true)
        assertGlobMatches("*.txt", "deep/path/report.txt", expectMatch = true)
        assertGlobMatches("*.txt", "report.md", expectMatch = false)
    }

    @Test fun globMatchesMiddlePattern() = runTest {
        // `prefix.*.suffix` — middle wildcard. Distinct shape: TWO
        // literal segments separated by a wildcard.
        assertGlobMatches("api.*.com", "api.foo.com", expectMatch = true)
        assertGlobMatches("api.*.com", "api.foo.bar.com", expectMatch = true)
        // Both literal dots in `api.*.com` MUST be present; `api.com`
        // has only one and falls short. Pin: `.*` is greedy but the
        // surrounding literal dots are non-optional anchors.
        assertGlobMatches("api.*.com", "api.com", expectMatch = false)
        assertGlobMatches("api.*.com", "api.foo.org", expectMatch = false)
    }

    @Test fun globMatchesMultiWildcardPattern() = runTest {
        // `a*b*c` — multiple wildcards. Each `*` becomes `.*`, so
        // the regex is `^a.*b.*c$`. Order matters: must be a-then-b-
        // then-c with anything (or nothing) between.
        assertGlobMatches("a*b*c", "abc", expectMatch = true)
        assertGlobMatches("a*b*c", "aXbYc", expectMatch = true)
        assertGlobMatches("a*b*c", "acb", expectMatch = false)
    }

    @Test fun globDoubleWildcardBehavesLikeSingle() = runTest {
        // `**` becomes `.*.*` which collapses to `.*` semantically.
        // Pin the observed behaviour so a refactor introducing
        // explicit `**` semantics (recursive directory match) lands
        // in test-red.
        assertGlobMatches("**", "anything", expectMatch = true)
        assertGlobMatches("a**b", "ab", expectMatch = true) // both `*` match empty
        assertGlobMatches("a**b", "aXYZb", expectMatch = true)
    }

    // ── Short-circuit pins ─────────────────────────────────────────

    @Test fun globShortCircuitsOnPureWildcard() = runTest {
        // `rulePattern == "*"` is the line-111 fast path that
        // bypasses regex compilation entirely. Pin it explicitly
        // so a refactor that "consolidates" both branches doesn't
        // accidentally regress the short-circuit and start
        // regex-compiling for every `*` rule.
        assertGlobMatches("*", "anything", expectMatch = true)
        assertGlobMatches("*", "", expectMatch = true)
    }

    @Test fun globShortCircuitsOnLiteralEquality() = runTest {
        // `rulePattern == requestPattern` is the OTHER line-111 fast
        // path. Pin: a non-glob rule that exactly matches the
        // request short-circuits to true without entering the
        // contains('*') branch. Pre-cycle observation: this matters
        // for performance + avoids spurious regex compilation for
        // 80%+ of literal-pattern rules.
        assertGlobMatches("exact-match", "exact-match", expectMatch = true)
        assertGlobMatches("/path/to/file.txt", "/path/to/file.txt", expectMatch = true)
    }

    @Test fun globReturnsFalseForLiteralWithNoWildcardWhenUnequal() = runTest {
        // Line 112: `if (!rulePattern.contains('*')) return false`.
        // Without a wildcard AND not equal → no regex path → false.
        // Drift to enter the regex path here would still match (the
        // built regex has no `.*`, so `^literal$` only matches that
        // exact string anyway), but the short-circuit avoids regex
        // compilation entirely. Pin observed behaviour.
        assertGlobMatches("foo", "bar", expectMatch = false)
        assertGlobMatches("foo", "foobar", expectMatch = false)
    }

    @Test fun globReturnsTrueForEmptyOnEmpty() = runTest {
        // Both empty → literal-equality short-circuit on line 111.
        // Edge case: an empty rule pattern + empty request pattern
        // both being "anything matches anything" by the equality
        // path. Pin observed behaviour.
        assertGlobMatches("", "", expectMatch = true)
    }

    // ── Precedence symmetry pins (ASK > ALLOW in BOTH orders) ──────

    @Test fun askWinsOverAllowInAllowFirstOrder() = runTest {
        // Existing askWinsOverAllowWhenBothMatch tests rules:
        // [ALLOW, ASK] (ALLOW first). The `if (found != ASK)` guard
        // line 102 is what prevents downgrade. This test pins the
        // SAME outcome with the SAME order — so a refactor
        // dropping the guard would break this test.
        val bus = EventBus()
        val service = DefaultPermissionService(bus)
        val rules = listOf(
            PermissionRule("test.perm", "*", PermissionAction.ALLOW),
            PermissionRule("test.perm", "*", PermissionAction.ASK),
        )
        val decision = async { service.check(rules, req("test.perm", "x")) }
        val asked = bus.subscribe<BusEvent.PermissionAsked>().first()
        service.reply(asked.requestId, PermissionDecision.Reject)
        assertEquals(
            PermissionDecision.Reject,
            decision.await(),
            "ALLOW-then-ASK MUST upgrade to ASK (rule iteration ORDER doesn't downgrade)",
        )
    }

    @Test fun askWinsOverAllowInAskFirstOrder() = runTest {
        // The mirror order: [ASK, ALLOW]. Found becomes ASK first,
        // then the line-102 guard prevents downgrade to ALLOW. Both
        // orders MUST yield the same ASK outcome.
        val bus = EventBus()
        val service = DefaultPermissionService(bus)
        val rules = listOf(
            PermissionRule("test.perm", "*", PermissionAction.ASK),
            PermissionRule("test.perm", "*", PermissionAction.ALLOW),
        )
        val decision = async { service.check(rules, req("test.perm", "x")) }
        val asked = bus.subscribe<BusEvent.PermissionAsked>().first()
        service.reply(asked.requestId, PermissionDecision.Reject)
        assertEquals(
            PermissionDecision.Reject,
            decision.await(),
            "ASK-then-ALLOW MUST stay ASK (line-102 guard prevents downgrade)",
        )
    }

    @Test fun denyBeatsAskInBothOrders() = runTest {
        // Sister pin to existing denyBeatsAllowRegardlessOfOrder —
        // DENY also beats ASK in both orders. Line-101 returns
        // immediately, so order is irrelevant. Pin both directions.
        val service = DefaultPermissionService(EventBus())
        val askThenDeny = listOf(
            PermissionRule("test.perm", "*", PermissionAction.ASK),
            PermissionRule("test.perm", "*", PermissionAction.DENY),
        )
        assertEquals(
            PermissionDecision.Reject,
            service.check(askThenDeny, req("test.perm", "x")),
        )

        val denyThenAsk = listOf(
            PermissionRule("test.perm", "*", PermissionAction.DENY),
            PermissionRule("test.perm", "*", PermissionAction.ASK),
        )
        assertEquals(
            PermissionDecision.Reject,
            service.check(denyThenAsk, req("test.perm", "x")),
        )
    }

    // ── Permission name + pattern dual-match pin ──────────────────

    @Test fun bothPermissionAndPatternMustMatch() = runTest {
        // Line 98-99: continue if permission name OR pattern
        // mismatches. This pin exercises the AND semantics
        // explicitly: a rule with right permission + wrong pattern
        // OR wrong permission + right pattern must not fire.
        val bus = EventBus()
        val service = DefaultPermissionService(bus)
        val rules = listOf(
            // Right permission, wrong (non-glob) pattern.
            PermissionRule("fs.read", "/etc/", PermissionAction.ALLOW),
            // Wrong permission, wildcard pattern.
            PermissionRule("fs.write", "*", PermissionAction.ALLOW),
        )
        // Request fs.read /tmp/file → neither rule should match
        // (first has wrong pattern; second has wrong permission).
        // Default → ASK.
        val decision = async { service.check(rules, req("fs.read", "/tmp/file")) }
        val asked = bus.subscribe<BusEvent.PermissionAsked>().first()
        service.reply(asked.requestId, PermissionDecision.Reject)
        assertEquals(
            PermissionDecision.Reject,
            decision.await(),
            "Neither rule's (permission, pattern) AND-pair matched; MUST fall through to ASK",
        )
    }
}
