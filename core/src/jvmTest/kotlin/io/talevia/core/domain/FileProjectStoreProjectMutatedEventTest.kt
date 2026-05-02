package io.talevia.core.domain

import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * M7 §4 #1 cross-platform Project-mutation event surface.
 * `FileProjectStore.mutate(...)` must publish exactly one
 * `BusEvent.ProjectMutated` per successful mutate, with
 * `mutatedAtEpochMs` matching the bundle's post-mutate timestamp.
 * Failed mutates (block throws) publish zero events. UI subscribers
 * (desktop / iOS / Android) consume this in lieu of polling
 * `projects.get(projectId)` to detect change.
 */
class FileProjectStoreProjectMutatedEventTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun successfulMutateEmitsExactlyOneEventWithCorrectProjectIdAndTimestamp() = runTest {
        val bus = EventBus()
        val captured = mutableListOf<BusEvent>()
        val collector = launch { bus.events.collect { captured += it } }
        runCurrent()

        val fixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))
        val store = ProjectStoreTestKit.create(bus = bus, clock = fixedClock)

        val pid = ProjectId("p-mutated-1")
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(tracks = emptyList())),
        )
        // upsert is not in scope for ProjectMutated (M7 #1 contract is mutate-only).
        // Drain any non-mutate events that landed and reset.
        advanceUntilIdle()
        captured.clear()

        // Mutate: append a Track to the timeline.
        store.mutate(pid) { it.copy(timeline = Timeline(tracks = listOf(Track.Video(id = io.talevia.core.TrackId("v0"))))) }
        advanceUntilIdle()

        val mutatedEvents = captured.filterIsInstance<BusEvent.ProjectMutated>()
        assertEquals(1, mutatedEvents.size, "exactly one ProjectMutated per successful mutate; got $mutatedEvents")
        assertEquals(pid, mutatedEvents.single().projectId)
        assertEquals(
            1_700_000_000_000L,
            mutatedEvents.single().mutatedAtEpochMs,
            "mutatedAtEpochMs reflects the clock at mutate time (matches bundle envelope timestamp)",
        )

        collector.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun failedMutateEmitsZeroEvents() = runTest {
        // Block throwing inside mutate must propagate without publishing
        // a ProjectMutated event. The bundle on disk should also be left
        // unchanged (mutex.withLock + writeBundleLocked sequence is
        // atomic — write only happens on a clean block return).
        val bus = EventBus()
        val captured = mutableListOf<BusEvent>()
        val collector = launch { bus.events.collect { captured += it } }
        runCurrent()

        val store = ProjectStoreTestKit.create(bus = bus)
        val pid = ProjectId("p-mutate-fails")
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(tracks = emptyList())),
        )
        advanceUntilIdle()
        captured.clear()

        assertFails {
            store.mutate(pid) { error("block intentionally throws") }
        }
        advanceUntilIdle()

        val mutatedEvents = captured.filterIsInstance<BusEvent.ProjectMutated>()
        assertEquals(
            0,
            mutatedEvents.size,
            "failed mutate must not emit ProjectMutated; got $mutatedEvents",
        )

        collector.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun threeMutatesEmitThreeEventsInOrder() = runTest {
        val bus = EventBus()
        val captured = mutableListOf<BusEvent>()
        val collector = launch { bus.events.collect { captured += it } }
        runCurrent()

        val store = ProjectStoreTestKit.create(bus = bus)
        val pid = ProjectId("p-mutate-thrice")
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(tracks = emptyList())),
        )
        advanceUntilIdle()
        captured.clear()

        repeat(3) {
            store.mutate(pid) { it.copy(timeline = Timeline(tracks = emptyList())) }
        }
        advanceUntilIdle()

        val mutatedEvents = captured.filterIsInstance<BusEvent.ProjectMutated>()
        assertEquals(3, mutatedEvents.size, "one event per successful mutate; got $mutatedEvents")
        assertContentEquals(
            listOf(pid, pid, pid),
            mutatedEvents.map { it.projectId },
        )
        // Timestamps non-decreasing (test uses Clock.System; mutates run sequentially under mutex).
        assertTrue(
            mutatedEvents.zipWithNext().all { (a, b) -> b.mutatedAtEpochMs >= a.mutatedAtEpochMs },
            "timestamps non-decreasing across sequential mutates",
        )

        collector.cancel()
    }
}

/** Test-only fixed clock so we can assert mutatedAtEpochMs exactly. */
private class FixedClock(private val instant: Instant) : kotlinx.datetime.Clock {
    override fun now(): Instant = instant
}
