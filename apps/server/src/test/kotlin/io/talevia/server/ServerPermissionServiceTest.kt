package io.talevia.server

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.permission.PermissionRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests for [ServerPermissionService] — the headless server
 * permission gate. Every tool call from a server-mode agent flows
 * through this. Cycle 80 audit found zero direct tests.
 *
 * The auto-reject-ASK contract is the load-bearing security property:
 * a server caller MUST explicitly grant a permission via rules, OR
 * tools fail closed. A regression flipping ASK to ALLOW would
 * silently grant unprompted access to every server-mode session.
 */
class ServerPermissionServiceTest {

    private val sessionId = SessionId("s")

    private fun req(permission: String, pattern: String = "*") =
        PermissionRequest(sessionId = sessionId, permission = permission, pattern = pattern)

    @Test fun allowRuleReturnsDecisionOnce() = runBlocking {
        val svc = ServerPermissionService(EventBus())
        val decision = svc.check(
            rules = listOf(PermissionRule("foo", "*", PermissionAction.ALLOW)),
            request = req("foo"),
        )
        assertEquals(PermissionDecision.Once, decision)
    }

    @Test fun denyRuleReturnsDecisionReject() = runBlocking {
        val svc = ServerPermissionService(EventBus())
        val decision = svc.check(
            rules = listOf(PermissionRule("foo", "*", PermissionAction.DENY)),
            request = req("foo"),
        )
        assertEquals(PermissionDecision.Reject, decision)
    }

    @Test fun askRuleReturnsRejectInHeadlessMode() = runBlocking {
        // Load-bearing: ASK without a user means Reject. The kdoc commits
        // to "treat any ASK as Reject because no user is there to answer".
        val svc = ServerPermissionService(EventBus())
        val decision = svc.check(
            rules = listOf(PermissionRule("foo", "*", PermissionAction.ASK)),
            request = req("foo"),
        )
        assertEquals(PermissionDecision.Reject, decision)
    }

    @Test fun noMatchingRuleDefaultsToAskWhichRejects() = runBlocking {
        // No rule for the requested permission → fall through to default
        // ASK in evaluate(), which then auto-rejects in headless mode.
        val svc = ServerPermissionService(EventBus())
        val decision = svc.check(
            rules = listOf(PermissionRule("other", "*", PermissionAction.ALLOW)),
            request = req("foo"),
        )
        assertEquals(PermissionDecision.Reject, decision)
    }

    @Test fun emptyRuleListAlwaysRejects() = runBlocking {
        // No rules at all → default ASK → Reject. A server with no
        // baseline policy is fully closed by default.
        val svc = ServerPermissionService(EventBus())
        val decision = svc.check(rules = emptyList(), request = req("foo"))
        assertEquals(PermissionDecision.Reject, decision)
    }

    @Test fun denyTrumpsAllowForSamePermission() = runBlocking {
        // The evaluate() loop explicitly returns DENY immediately when
        // any matching rule is DENY. ALLOW + DENY for the same key →
        // DENY wins. Pin the contract.
        val svc = ServerPermissionService(EventBus())
        val rules = listOf(
            PermissionRule("foo", "*", PermissionAction.ALLOW),
            PermissionRule("foo", "*", PermissionAction.DENY),
        )
        assertEquals(PermissionDecision.Reject, svc.check(rules, req("foo")))
    }

    @Test fun askPrecedenceOverAllowInSameRuleSet() = runBlocking {
        // evaluate() sets `found = ASK` then never overwrites it with
        // ALLOW. Pin: when both ASK and ALLOW match, ASK wins (so the
        // headless mode auto-rejects). This is the conservative
        // direction — tooling that wanted ALLOW should not also have an
        // ASK rule for the same key.
        val svc = ServerPermissionService(EventBus())
        val rules = listOf(
            PermissionRule("foo", "*", PermissionAction.ALLOW),
            PermissionRule("foo", "*", PermissionAction.ASK),
        )
        assertEquals(PermissionDecision.Reject, svc.check(rules, req("foo")))
    }

    @Test fun wildcardPatternMatchesAnyRequestPattern() = runBlocking {
        val svc = ServerPermissionService(EventBus())
        val rules = listOf(PermissionRule("fs.write", "*", PermissionAction.ALLOW))
        assertEquals(PermissionDecision.Once, svc.check(rules, req("fs.write", pattern = "/tmp/x")))
        assertEquals(PermissionDecision.Once, svc.check(rules, req("fs.write", pattern = "/var/log")))
    }

    @Test fun literalPatternMatchesExactRequestOnly() = runBlocking {
        val svc = ServerPermissionService(EventBus())
        val rules = listOf(PermissionRule("fs.write", "/tmp/exact", PermissionAction.ALLOW))
        assertEquals(PermissionDecision.Once, svc.check(rules, req("fs.write", "/tmp/exact")))
        // Non-matching literal pattern → no rule matches → default ASK → Reject.
        assertEquals(PermissionDecision.Reject, svc.check(rules, req("fs.write", "/tmp/other")))
    }

    @Test fun globPatternWithEmbeddedStarMatchesPrefix() = runBlocking {
        // "github.com/*" should match "github.com/foo" but not
        // "gitlab.com/foo".
        val svc = ServerPermissionService(EventBus())
        val rules = listOf(PermissionRule("web.fetch", "github.com/*", PermissionAction.ALLOW))
        assertEquals(PermissionDecision.Once, svc.check(rules, req("web.fetch", "github.com/foo")))
        assertEquals(PermissionDecision.Once, svc.check(rules, req("web.fetch", "github.com/")))
        assertEquals(PermissionDecision.Reject, svc.check(rules, req("web.fetch", "gitlab.com/foo")))
    }

    @Test fun regexMetacharsInPatternAreEscaped() = runBlocking {
        // The matcher escapes Regex metacharacters so a literal "." in
        // an URL doesn't accidentally match any character.
        val svc = ServerPermissionService(EventBus())
        val rules = listOf(PermissionRule("web.fetch", "github.com/foo", PermissionAction.ALLOW))
        // Regex-style "any-character" must NOT match the literal `.`.
        assertEquals(
            PermissionDecision.Reject,
            svc.check(rules, req("web.fetch", "githubXcom/foo")),
            "literal '.' must NOT match arbitrary character; regex metachars are escaped",
        )
    }

    @Test fun askPathPublishesAskAndRepliedEventsToTheBus() = runBlocking {
        // Pin: ASK path emits BOTH `PermissionAsked` AND `PermissionReplied`
        // events for SSE consumer visibility, even though the decision
        // is auto-Reject. Without `PermissionReplied`, an SSE client that
        // shows asking → answered transitions would hang at "asking".
        val bus = EventBus()
        val scope = CoroutineScope(SupervisorJob())
        val collected = mutableListOf<BusEvent>()
        val job = scope.launch { bus.events.collect { collected += it } }
        // Briefly yield so the subscriber is live before publish.
        yield()

        val svc = ServerPermissionService(bus)
        svc.check(
            rules = listOf(PermissionRule("aigc.generate", "*", PermissionAction.ASK)),
            request = req("aigc.generate"),
        )

        // Wait for both events.
        withTimeout(2_000) {
            while (collected.filterIsInstance<BusEvent.PermissionAsked>().isEmpty() ||
                collected.filterIsInstance<BusEvent.PermissionReplied>().isEmpty()
            ) {
                yield()
            }
        }

        val asked = collected.filterIsInstance<BusEvent.PermissionAsked>()
        val replied = collected.filterIsInstance<BusEvent.PermissionReplied>()
        assertEquals(1, asked.size, "exactly one PermissionAsked emitted")
        assertEquals(1, replied.size, "exactly one PermissionReplied emitted")
        assertEquals("aigc.generate", asked[0].permission)
        assertEquals(false, replied[0].accepted, "headless reply is always rejected")
        assertEquals(false, replied[0].remembered, "headless never persists rules")
        // requestId pairs the events.
        assertEquals(asked[0].requestId, replied[0].requestId)

        job.cancel()
        scope.cancel()
    }
}
