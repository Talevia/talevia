package io.talevia.core.metrics

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The metrics sink is the one place downstream dashboards look to answer
 * "what is the agent doing, and is any of it failing?". A miscounted event or
 * a missing case would silently blind operators.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetricsTest {

    private val sessionId = SessionId("s-1")

    @Test fun incrementsAccumulate() = runTest {
        val r = MetricsRegistry()
        r.increment("foo")
        r.increment("foo", by = 2L)
        r.increment("bar")
        assertEquals(3L, r.get("foo"))
        assertEquals(1L, r.get("bar"))
        assertEquals(mapOf("foo" to 3L, "bar" to 1L), r.snapshot())
    }

    @Test fun snapshotIsDefensiveCopy() = runTest {
        val r = MetricsRegistry()
        r.increment("foo")
        val snap = r.snapshot()
        r.increment("foo")
        assertEquals(mapOf("foo" to 1L), snap)
        assertEquals(2L, r.get("foo"))
    }

    @Test fun resetClearsEverything() = runTest {
        val r = MetricsRegistry()
        r.increment("foo"); r.increment("bar")
        r.reset()
        assertTrue(r.snapshot().isEmpty())
    }

    @Test fun getReturnsZeroForUnknown() = runTest {
        assertEquals(0L, MetricsRegistry().get("nope"))
    }

    @Test fun sinkCountsEachBusEventOnce() = runTest {
        val bus = EventBus()
        val registry = MetricsRegistry()
        val job = EventBusMetricsSink(bus, registry).attach(this)
        // Let the sink's collector reach its suspension point before publishing —
        // SharedFlow with replay=0 drops emissions that arrive before any
        // subscriber is active.
        runCurrent()

        bus.publish(BusEvent.SessionCreated(sessionId))
        bus.publish(BusEvent.SessionUpdated(sessionId))
        bus.publish(BusEvent.AgentRunFailed(sessionId, "cid-1", "boom"))
        bus.publish(BusEvent.AgentRunFailed(sessionId, "cid-2", "boom"))
        advanceUntilIdle()

        assertEquals(2L, registry.get("agent.run.failed"))
        assertEquals(1L, registry.get("session.created"))
        assertEquals(1L, registry.get("session.updated"))

        job.cancel()
    }

    @Test fun permissionRepliedSplitsOnAccepted() = runTest {
        val bus = EventBus()
        val registry = MetricsRegistry()
        val job = EventBusMetricsSink(bus, registry).attach(this)
        runCurrent()

        bus.publish(BusEvent.PermissionReplied(sessionId, "r1", accepted = true, remembered = false))
        bus.publish(BusEvent.PermissionReplied(sessionId, "r2", accepted = false, remembered = false))
        bus.publish(BusEvent.PermissionReplied(sessionId, "r3", accepted = true, remembered = true))
        advanceUntilIdle()

        assertEquals(2L, registry.get("permission.granted"))
        assertEquals(1L, registry.get("permission.rejected"))
        job.cancel()
    }

    @Test fun publishBeforeSubscribeIsDropped() = runTest {
        // Regression guard: if the sink Job hasn't started collecting yet,
        // SharedFlow with replay=0 drops the emission. Callers must wait for
        // the subscription to be live before relying on counts.
        val bus = EventBus()
        val registry = MetricsRegistry()
        // Publish BEFORE subscriber is active.
        bus.publish(BusEvent.SessionCreated(sessionId))
        val job = EventBusMetricsSink(bus, registry).attach(this)
        runCurrent()
        advanceUntilIdle()
        assertEquals(0L, registry.get("session.created"))
        job.cancel()
    }
}
