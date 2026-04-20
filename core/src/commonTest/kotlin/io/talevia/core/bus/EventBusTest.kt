package io.talevia.core.bus

import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct guards on [EventBus]. Up to now the bus has only been exercised
 * transitively through PermissionServiceTest / SessionStoreSmokeTest, so its
 * own contracts (no replay, per-subscriber ordering, late subscribers miss
 * events, cancellation cleans up) had no regression net.
 */
class EventBusTest {

    private val sid = SessionId("s")
    private val mid = MessageId("m")

    private fun delta(i: Int) = BusEvent.PartDelta(sid, mid, PartId("p"), "text", "$i")

    @Test
    fun multipleSubscribersEachReceiveAllEvents() = runTest {
        val bus = EventBus()

        // Two independent subscribers, each capturing the next 3 deltas.
        val subA = async(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe<BusEvent.PartDelta>().take(3).toList()
        }
        val subB = async(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe<BusEvent.PartDelta>().take(3).toList()
        }

        for (i in 0 until 3) bus.publish(delta(i))

        val a = subA.await().map { it.delta }
        val b = subB.await().map { it.delta }
        assertEquals(listOf("0", "1", "2"), a)
        assertEquals(listOf("0", "1", "2"), b)
    }

    @Test
    fun perSubscriberOrderingIsPreserved() = runTest {
        val bus = EventBus()

        // A subscriber that suspends briefly between events still sees them
        // in publish order — SharedFlow guarantees per-collector ordering.
        val received = mutableListOf<String>()
        val sub = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe<BusEvent.PartDelta>().take(5).collect {
                received += it.delta
                delay(1)
            }
        }
        for (i in 0 until 5) bus.publish(delta(i))
        sub.join()

        assertEquals(listOf("0", "1", "2", "3", "4"), received)
    }

    @Test
    fun lateSubscriberMissesPriorEvents() = runTest {
        // Documented contract: EventBus does not replay. Subscribers added
        // after `publish()` must rely on a SessionStore read for backfill.
        val bus = EventBus()

        for (i in 0 until 5) bus.publish(delta(i))
        val late = withTimeoutOrNull(100) {
            bus.subscribe<BusEvent.PartDelta>().first()
        }
        assertNull(late, "late subscriber must not see replayed events")
    }

    @Test
    fun typedSubscriptionFiltersOutOtherEventTypes() = runTest {
        val bus = EventBus()

        val onlyDeltas = async(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe<BusEvent.PartDelta>().take(2).toList()
        }
        val onlyAsks = async(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe<BusEvent.PermissionAsked>().take(1).toList()
        }

        bus.publish(delta(0))
        bus.publish(BusEvent.PermissionAsked(sid, "rid-1", "echo", listOf("*")))
        bus.publish(delta(1))

        assertEquals(listOf("0", "1"), onlyDeltas.await().map { it.delta })
        assertEquals("rid-1", onlyAsks.await().single().requestId)
    }

    @Test
    fun forSessionFiltersByTargetSessionOnly() = runTest {
        val bus = EventBus()
        val target = SessionId("target")
        val other = SessionId("other")

        val targetEvents = async(start = CoroutineStart.UNDISPATCHED) {
            bus.forSession(target).take(2).toList()
        }
        bus.publish(BusEvent.SessionUpdated(other))
        bus.publish(BusEvent.SessionUpdated(target))
        bus.publish(BusEvent.SessionUpdated(other))
        bus.publish(BusEvent.SessionUpdated(target))

        val seen = targetEvents.await()
        assertEquals(2, seen.size)
        assertTrue(seen.all { it.sessionId == target })
    }

    @Test
    fun cancelledSubscriberDoesNotBlockOtherSubscribers() = runTest {
        // A cancelled or completed subscription must not jam future publishes.
        // Concretely: if the bus held onto a dead collector, publish() to a
        // remaining subscriber would still go through, but a regression in
        // unsubscribe semantics could surface as backpressure.
        val bus = EventBus()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe<BusEvent.PartDelta>().collect { /* never returns */ }
        }
        cancelled.cancel()

        val survivor = async(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe<BusEvent.PartDelta>().take(3).toList()
        }
        for (i in 0 until 3) bus.publish(delta(i))
        assertEquals(listOf("0", "1", "2"), survivor.await().map { it.delta })
    }

    @Test
    fun publishedEventsAreDeliveredWithoutDropsToSlowSubscriber() = runTest {
        // EventBus uses BufferOverflow.SUSPEND (default for SharedFlow) — a slow
        // subscriber must NOT lose events; instead publish() blocks the producer
        // once the buffer fills. Practical relevance: a UI that lags behind the
        // agent stream still sees every PartDelta after it catches up.
        val bus = EventBus(extraBufferCapacity = 4)
        val total = 50

        val received = mutableListOf<String>()
        val sub = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.subscribe<BusEvent.PartDelta>().take(total).collect {
                received += it.delta
                // Yield each iteration so the producer fills the buffer and
                // is forced to suspend.
                delay(1)
            }
        }
        for (i in 0 until total) bus.publish(delta(i))
        sub.join()

        assertEquals((0 until total).map { it.toString() }, received)
    }
}
