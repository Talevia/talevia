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
import kotlin.test.assertTrue

/**
 * Pins the eviction invariant for
 * `debt-bound-agent-run-state-tracker-evict-on-delete` (audit finding B2
 * in `docs/decisions/2026-04-23-debt-audit-unbounded-mutable-collections.md`).
 * Before the change, `_states` and `historyFlowInternal` grew monotonically
 * with lifetime session count — nothing ever removed an entry, even after
 * `SqlDelightSessionStore.deleteSession(sid)` dropped the row. A long-lived
 * server doing N session create / delete cycles would leak ~3 KB per
 * historical session (a sealed `AgentRunState` head + up to 256-entry
 * transition list × 24 bytes each).
 *
 * The fix: the tracker subscribes to `BusEvent.SessionDeleted` (which
 * `SqlDelightSessionStore.deleteSession` already publishes) and removes
 * the session id from both maps. These tests guard against a silent
 * regression where either the collector gets removed, the event changes
 * shape, or `Map.minus(key)` is replaced by something that preserves
 * the entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunStateTrackerEvictionTest {

    @Test fun sessionDeletedEvictsBothMaps() = runTest {
        val bus = EventBus()
        // `backgroundScope` is runTest's built-in scope for forever-running
        // work like the tracker's bus-collect — it's cancelled automatically
        // at test end. Mirrors the pattern in AgentRunStateTest, which
        // uses the same idiom for bus subscribers.
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        // Drain pending work so the tracker's collector is ACTIVELY collecting
        // before we publish — MutableSharedFlow with no replay drops events
        // emitted before the collector subscribes, so a missing yield-point
        // here silently swallows the seed event and the assertions below fail.
        advanceUntilIdle()
        yield()
        val sid = SessionId("s-evict")

        // Seed state: emit an AgentRunStateChanged so both maps get an entry.
        bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating))
        advanceUntilIdle()
        yield()
        assertEquals(
            AgentRunState.Generating,
            tracker.currentState(sid),
            "collector must populate _states on AgentRunStateChanged",
        )
        assertEquals(1, tracker.history(sid).size, "history must grow with the transition")
        assertTrue(sid in tracker.states.value, "sessionId must be a key in _states")
        assertTrue(sid in tracker.historyFlow.value, "sessionId must be a key in historyFlowInternal")

        // Delete the session.
        bus.publish(BusEvent.SessionDeleted(sid))
        advanceUntilIdle()
        yield()

        assertNull(tracker.currentState(sid), "_states must drop the entry on SessionDeleted")
        assertTrue(
            tracker.history(sid).isEmpty(),
            "history must be empty after SessionDeleted — the entry is gone, not just empty",
        )
        assertTrue(
            sid !in tracker.states.value,
            "sessionId must NOT be a key in _states after delete",
        )
        assertTrue(
            sid !in tracker.historyFlow.value,
            "sessionId must NOT be a key in historyFlowInternal after delete",
        )
    }

    @Test fun sessionDeletedOnUntrackedSessionIsNoOp() = runTest {
        // Publishing SessionDeleted for a session the tracker never saw must
        // not crash, must not leave phantom entries, and must not disturb
        // other tracked sessions.
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()

        val live = SessionId("s-live")
        val ghost = SessionId("s-never-existed")
        bus.publish(BusEvent.AgentRunStateChanged(live, AgentRunState.Generating))
        advanceUntilIdle()
        yield()
        val liveStateBefore = tracker.currentState(live)
        val liveHistoryBefore = tracker.history(live)
        assertEquals(AgentRunState.Generating, liveStateBefore)

        bus.publish(BusEvent.SessionDeleted(ghost))
        advanceUntilIdle()
        yield()

        assertNull(
            tracker.currentState(ghost),
            "untracked sessionId must still resolve to null (no phantom entry created)",
        )
        assertEquals(
            liveStateBefore,
            tracker.currentState(live),
            "deleting an unrelated session must not affect other tracked entries",
        )
        assertEquals(
            liveHistoryBefore,
            tracker.history(live),
            "deleting an unrelated session must not touch other sessions' history",
        )
    }

    @Test fun createDeleteCreateRebuildsCleanState() = runTest {
        // Delete-then-recreate-same-id path: after eviction, subsequent
        // AgentRunStateChanged for the same id must start a FRESH history
        // (not carry leftover transitions from the pre-delete life). The
        // guard against a silent regression where eviction forgets to
        // clear history but drops from _states (or vice versa).
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val sid = SessionId("s-reused")

        bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating))
        bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.AwaitingTool))
        advanceUntilIdle()
        yield()
        assertEquals(2, tracker.history(sid).size, "two transitions must land in the pre-delete history")

        bus.publish(BusEvent.SessionDeleted(sid))
        advanceUntilIdle()
        yield()
        assertNull(tracker.currentState(sid))
        assertTrue(tracker.history(sid).isEmpty())

        // Re-use the same session id after delete.
        bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating))
        advanceUntilIdle()
        yield()
        assertEquals(AgentRunState.Generating, tracker.currentState(sid))
        assertEquals(
            1,
            tracker.history(sid).size,
            "post-delete reuse must start with exactly ONE transition — no residue from the pre-delete life",
        )
    }
}
