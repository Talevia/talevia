package io.talevia.core.permission

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the rule evaluator's ordering guarantees and the ASK/reply handshake.
 * These are the rules clients rely on when writing permission policies; a
 * regression here would silently change what the agent is allowed to do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionServiceTest {

    private val sessionId = SessionId("s-1")
    private fun req(permission: String, pattern: String = "*") =
        PermissionRequest(sessionId = sessionId, permission = permission, pattern = pattern)

    @Test fun denyBeatsAllowRegardlessOfOrder() = runTest {
        val service = DefaultPermissionService(EventBus())
        val rules = listOf(
            PermissionRule("media.export.write", "*", PermissionAction.ALLOW),
            PermissionRule("media.export.write", "*", PermissionAction.DENY),
        )
        assertEquals(PermissionDecision.Reject, service.check(rules, req("media.export.write")))

        val rulesFlipped = listOf(
            PermissionRule("media.export.write", "*", PermissionAction.DENY),
            PermissionRule("media.export.write", "*", PermissionAction.ALLOW),
        )
        assertEquals(PermissionDecision.Reject, service.check(rulesFlipped, req("media.export.write")))
    }

    @Test fun allowResolvesToOnce() = runTest {
        val service = DefaultPermissionService(EventBus())
        val rules = listOf(PermissionRule("echo", "*", PermissionAction.ALLOW))
        assertEquals(PermissionDecision.Once, service.check(rules, req("echo")))
    }

    @Test fun askWinsOverAllowWhenBothMatch() = runTest {
        // An explicit ASK must not be silently upgraded to ALLOW by a subsequent matching
        // rule — ASK is a stronger signal (the user wants a prompt), not a weaker one.
        val service = DefaultPermissionService(EventBus())
        val rules = listOf(
            PermissionRule("media.export.write", "*", PermissionAction.ALLOW),
            PermissionRule("media.export.write", "*", PermissionAction.ASK),
        )
        // ASK means: raise a request. We'll reply Reject to resolve the suspend.
        val bus = EventBus()
        val asking = DefaultPermissionService(bus)
        val decision = async {
            asking.check(rules, req("media.export.write"))
        }
        val asked = bus.subscribe<BusEvent.PermissionAsked>().first()
        asking.reply(asked.requestId, PermissionDecision.Reject)
        assertEquals(PermissionDecision.Reject, decision.await())
    }

    @Test fun defaultsToAskWhenNoRuleMatches() = runTest {
        val bus = EventBus()
        val service = DefaultPermissionService(bus)
        val decision = async { service.check(emptyList(), req("media.network.fetch")) }
        val asked = bus.subscribe<BusEvent.PermissionAsked>().first()
        assertEquals("media.network.fetch", asked.permission)
        service.reply(asked.requestId, PermissionDecision.Once)
        assertEquals(PermissionDecision.Once, decision.await())
    }

    @Test fun nonMatchingPermissionNameIsIgnored() = runTest {
        val bus = EventBus()
        val service = DefaultPermissionService(bus)
        val rules = listOf(PermissionRule("timeline.write", "*", PermissionAction.ALLOW))
        val decision = async { service.check(rules, req("media.import")) }
        val asked = bus.subscribe<BusEvent.PermissionAsked>().first()
        service.reply(asked.requestId, PermissionDecision.Once)
        assertEquals(PermissionDecision.Once, decision.await())
    }

    @Test fun globPatternMatchesPrefix() = runTest {
        val service = DefaultPermissionService(EventBus())
        val rules = listOf(PermissionRule("media.export.write", "/tmp/*", PermissionAction.ALLOW))
        assertEquals(PermissionDecision.Once, service.check(rules, req("media.export.write", "/tmp/a.mp4")))
    }

    @Test fun globPatternDoesNotMatchUnrelated() = runTest {
        // A /tmp/* rule MUST NOT grant access to /etc/...; otherwise a narrowly-scoped
        // user rule would silently widen.
        val bus = EventBus()
        val service = DefaultPermissionService(bus)
        val rules = listOf(PermissionRule("media.export.write", "/tmp/*", PermissionAction.ALLOW))
        val decision = async { service.check(rules, req("media.export.write", "/etc/passwd")) }
        val asked = bus.subscribe<BusEvent.PermissionAsked>().first()
        service.reply(asked.requestId, PermissionDecision.Reject)
        assertEquals(PermissionDecision.Reject, decision.await())
    }

    @Test fun wildcardRuleMatchesEverything() = runTest {
        val service = DefaultPermissionService(EventBus())
        val rules = listOf(PermissionRule("echo", "*", PermissionAction.ALLOW))
        assertEquals(PermissionDecision.Once, service.check(rules, req("echo", "anything-here")))
    }

    @Test fun replyPublishesPermissionReplied() = runTest {
        val bus = EventBus()
        val service = DefaultPermissionService(bus)
        val replied = async { bus.subscribe<BusEvent.PermissionReplied>().first() }
        val decision = async { service.check(emptyList(), req("media.network.fetch")) }
        val asked = bus.subscribe<BusEvent.PermissionAsked>().first()
        service.reply(asked.requestId, PermissionDecision.Always)
        val event = replied.await()
        assertTrue(event.accepted)
        assertTrue(event.remembered)
        assertEquals(PermissionDecision.Always, decision.await())
    }

    @Test fun replyForUnknownRequestIsNoop() = runTest {
        // Late or duplicate replies must not crash or publish a ghost event — the
        // server cancels pending sessions and may race with the user's reply.
        val bus = EventBus()
        val service = DefaultPermissionService(bus)
        service.reply("does-not-exist", PermissionDecision.Once)
        // Nothing to assert beyond "didn't throw"; the publish path is gated on
        // `pending.remove()` returning non-null, so no event is emitted.
    }

    @Test fun rejectDecisionIsNotGranted() {
        assertFalse(PermissionDecision.Reject.granted)
        assertTrue(PermissionDecision.Once.granted)
        assertTrue(PermissionDecision.Always.granted)
    }
}
