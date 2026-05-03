package io.talevia.core.agent

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [AgentBusCancelWatcher] — the side-channel that
 * routes [BusEvent.SessionCancelRequested] events from anywhere on the
 * bus (CLI signal handler / HTTP / IDE abort) back to `Agent.cancel`.
 * The class is internal and was previously zero-coverage (no transitive
 * references in any test). Pin the contract: subscribes once,
 * `awaitReady` synchronises with the subscription, idle-session cancels
 * are swallowed, and cancelSession exceptions don't propagate.
 */
class AgentBusCancelWatcherTest {

    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest fun cleanup() {
        scope.cancel()
    }

    @Test fun routesCancelRequestedEventToCallback() = runBlocking {
        val bus = EventBus()
        val captured = mutableListOf<SessionId>()
        val watcher = AgentBusCancelWatcher(bus, scope) { sid ->
            captured += sid
        }
        // Wait for the subscription to be live before publishing — SharedFlow
        // with replay=0 drops emissions before any subscriber is collecting.
        watcher.awaitReady()

        bus.publish(BusEvent.SessionCancelRequested(SessionId("alpha")))
        bus.publish(BusEvent.SessionCancelRequested(SessionId("beta")))

        // Wait briefly for the events to flow through the bus → callback path.
        withTimeout(2_000) {
            while (captured.size < 2) kotlinx.coroutines.yield()
        }
        assertEquals(listOf(SessionId("alpha"), SessionId("beta")), captured)
    }

    @Test fun ignoresUnrelatedBusEvents() = runBlocking {
        // The watcher only filters for `SessionCancelRequested`. Other
        // bus events must pass through without invoking cancelSession.
        val bus = EventBus()
        val captured = mutableListOf<SessionId>()
        val watcher = AgentBusCancelWatcher(bus, scope) { sid ->
            captured += sid
        }
        watcher.awaitReady()

        // Publish unrelated events — none should reach the callback.
        bus.publish(BusEvent.SessionCreated(SessionId("a")))
        bus.publish(BusEvent.SessionUpdated(SessionId("a")))
        // Then a cancel — only this should fire the callback.
        bus.publish(BusEvent.SessionCancelRequested(SessionId("target")))

        withTimeout(2_000) {
            while (captured.size < 1) kotlinx.coroutines.yield()
        }
        assertEquals(1, captured.size, "only the SessionCancelRequested event reaches cancelSession")
        assertEquals(SessionId("target"), captured[0])
    }

    @Test fun callbackThrowableDoesNotPropagateOutOfScope() = runBlocking {
        // kdoc: "runCatching around the call swallows throwables so a
        // cancel during shutdown never propagates out of the background
        // scope". Pin: a throwing callback must not crash the scope or
        // stop subsequent events from being routed.
        val bus = EventBus()
        var firstFired = false
        var secondFired = false
        val watcher = AgentBusCancelWatcher(bus, scope) { sid ->
            if (sid.value == "first") {
                firstFired = true
                error("simulated callback failure")
            } else {
                secondFired = true
            }
        }
        watcher.awaitReady()

        bus.publish(BusEvent.SessionCancelRequested(SessionId("first")))
        bus.publish(BusEvent.SessionCancelRequested(SessionId("second")))

        withTimeout(2_000) {
            while (!secondFired) kotlinx.coroutines.yield()
        }
        assertTrue(firstFired, "the throwing callback was invoked")
        assertTrue(secondFired, "second event still routed despite first callback throwing")
    }

    @Test fun awaitReadyCompletesAfterSubscriptionIsLive() = runBlocking {
        // The `ready` CompletableDeferred is used by tests to synchronize
        // publish against subscribe (SharedFlow with replay=0 drops events
        // before subscription). Pin the test hook.
        val bus = EventBus()
        val watcher = AgentBusCancelWatcher(bus, scope) { /* noop */ }
        // awaitReady must complete in bounded time — i.e. after the
        // collect's onSubscription callback fires.
        withTimeout(2_000) {
            watcher.awaitReady()
        }
        // Verify by publishing — event must reach a hypothetical second
        // subscriber that joins now.
        var fired = false
        val watcher2 = AgentBusCancelWatcher(bus, scope) { fired = true }
        watcher2.awaitReady()
        bus.publish(BusEvent.SessionCancelRequested(SessionId("s")))
        withTimeout(2_000) {
            while (!fired) kotlinx.coroutines.yield()
        }
        assertTrue(fired)
    }
}
