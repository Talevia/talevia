package io.talevia.core.agent

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coverage for [AgentProviderFallbackTracker] — records
 * [BusEvent.AgentProviderFallback] events per session so
 * `session_query(select=run_failure)` can answer "which providers did
 * the Agent try before giving up?" without having tailed the bus.
 *
 * Edges (§3a #9): empty session (no hops), ordered accumulation, ring-
 * buffer cap drop, session eviction on SessionDeleted.
 */
class AgentProviderFallbackTrackerTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)

    @AfterTest fun teardown() {
        job.cancel()
    }

    @Test fun noHopsReturnsEmpty() = runTest {
        val bus = EventBus()
        val tracker = AgentProviderFallbackTracker(bus, scope)
        assertEquals(emptyList(), tracker.hops(SessionId("never-fired")))
    }

    @Test fun fallbackPublishAccumulatesHopsInOrder() = runTest {
        val bus = EventBus()
        val tracker = AgentProviderFallbackTracker(bus, scope)
        val sid = SessionId("s1")

        bus.publish(BusEvent.AgentProviderFallback(sid, "anthropic", "openai", "503"))
        yield()
        bus.publish(BusEvent.AgentProviderFallback(sid, "openai", "gemini", "429"))
        yield()

        val hops = tracker.hops(sid)
        assertEquals(2, hops.size)
        assertEquals("anthropic", hops[0].fromProviderId)
        assertEquals("openai", hops[0].toProviderId)
        assertEquals("503", hops[0].reason)
        assertEquals("openai", hops[1].fromProviderId)
        assertEquals("gemini", hops[1].toProviderId)
        assertEquals("429", hops[1].reason)
        // epochMs monotonic non-decreasing — tracker timestamps in order.
        assertTrue(hops[1].epochMs >= hops[0].epochMs)
    }

    @Test fun separateSessionsDoNotCrossContaminate() = runTest {
        val bus = EventBus()
        val tracker = AgentProviderFallbackTracker(bus, scope)
        val a = SessionId("a")
        val b = SessionId("b")

        bus.publish(BusEvent.AgentProviderFallback(a, "p1", "p2", "overload"))
        yield()
        bus.publish(BusEvent.AgentProviderFallback(b, "p3", "p4", "timeout"))
        yield()

        assertEquals(1, tracker.hops(a).size)
        assertEquals("p1", tracker.hops(a).single().fromProviderId)
        assertEquals(1, tracker.hops(b).size)
        assertEquals("p3", tracker.hops(b).single().fromProviderId)
    }

    @Test fun sessionDeletedEvictsTrackedHops() = runTest {
        // Guards the bounded-memory invariant from the audit:
        // long-running process must not accumulate per-session entries
        // for sessions the store no longer has.
        val bus = EventBus()
        val tracker = AgentProviderFallbackTracker(bus, scope)
        val sid = SessionId("s-evict")

        bus.publish(BusEvent.AgentProviderFallback(sid, "p1", "p2", "boom"))
        yield()
        assertEquals(1, tracker.hops(sid).size, "tracker should have registered the hop")

        bus.publish(BusEvent.SessionDeleted(sid))
        yield()

        assertEquals(
            emptyList(),
            tracker.hops(sid),
            "SessionDeleted must evict the per-session ring buffer",
        )
    }

    @Test fun ringBufferDropsOldestWhenCapExceeded() = runTest {
        // Cap = 3 here so the test stays fast. Publish 5 hops → oldest 2
        // drop out; surviving hops start from the 3rd publish.
        val bus = EventBus()
        val tracker = AgentProviderFallbackTracker(bus, scope, historyCap = 3)
        val sid = SessionId("s-cap")

        repeat(5) { i ->
            bus.publish(BusEvent.AgentProviderFallback(sid, "p$i", "p${i + 1}", "r$i"))
            yield()
        }

        val hops = tracker.hops(sid)
        assertEquals(3, hops.size)
        assertEquals("p2", hops[0].fromProviderId)
        assertEquals("p3", hops[1].fromProviderId)
        assertEquals("p4", hops[2].fromProviderId)
    }
}
