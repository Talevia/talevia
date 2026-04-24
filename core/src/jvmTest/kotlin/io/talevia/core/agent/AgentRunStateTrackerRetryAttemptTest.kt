package io.talevia.core.agent

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the `retryAttempt` propagation from
 * [BusEvent.AgentRunStateChanged] to [StateTransition] (cycle-58). Before
 * the plumbing, the tracker discarded the attempt count — downstream
 * consumers (`session_query(select=run_failure).maxRetryAttemptObserved`)
 * had no way to report how many retries burned before a turn failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunStateTrackerRetryAttemptTest {

    @Test fun retryAttemptPropagatesIntoStateTransition() = runTest {
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val sid = SessionId("s-retry")

        bus.publish(
            BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating, retryAttempt = 2),
        )
        advanceUntilIdle()
        yield()

        val history = tracker.history(sid)
        assertEquals(1, history.size)
        assertEquals(2, history.single().retryAttempt, "StateTransition must carry retryAttempt from the bus event")
    }

    @Test fun retryAttemptDefaultsNullWhenEventOmitsIt() = runTest {
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val sid = SessionId("s-no-retry")

        // Default-constructed event (no retryAttempt arg) must surface null
        // — legacy transitions stay null, no "0 vs null" confusion.
        bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating))
        advanceUntilIdle()
        yield()

        val history = tracker.history(sid)
        assertEquals(1, history.size)
        assertNull(history.single().retryAttempt, "legacy transitions stay null, not 0")
    }

    @Test fun maxRetryAttemptAcrossHistoryIsRecoverableByConsumers() = runTest {
        // Guards the query-side use-case: RunFailureQuery computes max
        // retryAttempt across a failed turn's window. That requires every
        // intermediate retry-marked transition to retain its attempt count
        // — if the tracker coalesces or collapses them, the max query
        // would come out wrong.
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val sid = SessionId("s-seq")

        bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating, retryAttempt = 1))
        advanceUntilIdle()
        yield()
        bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating, retryAttempt = 3))
        advanceUntilIdle()
        yield()
        bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating, retryAttempt = 2))
        advanceUntilIdle()
        yield()

        val history = tracker.history(sid)
        val maxObserved = history.mapNotNull { it.retryAttempt }.max()
        assertEquals(3, maxObserved, "max retry attempt across history must reflect the highest publish")
    }
}
