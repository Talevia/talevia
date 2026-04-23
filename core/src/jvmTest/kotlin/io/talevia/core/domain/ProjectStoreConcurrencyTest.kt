package io.talevia.core.domain

import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.ProjectStoreTestKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ProjectStore.mutate` promises that concurrent tool dispatches within the
 * same Agent turn cannot interleave on the same project. If that guarantee
 * regresses, tools like SplitClipTool + TransitionActionTool firing in parallel
 * would produce lost updates (one tool's write clobbering the other's view
 * of the Project). These tests force a real parallel contention through the
 * JdbcSqliteDriver + mutex and assert no updates are lost.
 *
 * Uses runBlocking + Dispatchers.Default so the coroutines actually run on
 * parallel threads (runTest's single-threaded scheduler would mask races).
 */
class ProjectStoreConcurrencyTest {

    private class MutableClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private fun newStore(): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val id = ProjectId("p-1")
        val project = Project(id = id, timeline = Timeline())
        runBlocking { store.upsert("demo", project) }
        return store to id
    }

    @Test fun parallelMutationsAllApplySerialized() = runBlocking {
        val (store, id) = newStore()
        val parallelism = 32

        withContext(Dispatchers.Default) {
            (1..parallelism).map { index ->
                async {
                    store.mutate(id) { current ->
                        // Simulate work inside the critical section. If the mutex
                        // released too early, two coroutines could each read the
                        // same starting state and overwrite each other's track
                        // additions.
                        delay(1)
                        current.copy(
                            timeline = current.timeline.copy(
                                tracks = current.timeline.tracks + Track.Video(TrackId("t-$index")),
                            ),
                        )
                    }
                }
            }.awaitAll()
        }

        val final = store.get(id)!!
        assertEquals(parallelism, final.timeline.tracks.size, "lost updates under contention")
        val ids = final.timeline.tracks.map { it.id.value }.toSet()
        assertEquals((1..parallelism).map { "t-$it" }.toSet(), ids)
    }

    @Test fun mutateExceptionDoesNotPoisonMutex() = runBlocking {
        val (store, id) = newStore()

        // First call throws mid-block.
        val first = kotlin.runCatching {
            store.mutate(id) { _ ->
                throw IllegalStateException("simulated tool failure")
            }
        }
        check(first.isFailure)

        // Subsequent mutation must still acquire the lock and succeed; if the
        // exception had left the mutex held this would deadlock.
        val updated = store.mutate(id) { current ->
            current.copy(
                timeline = current.timeline.copy(
                    tracks = current.timeline.tracks + Track.Video(TrackId("after-failure")),
                ),
            )
        }
        assertEquals("after-failure", updated.timeline.tracks.single().id.value)
    }

    @Test fun mutateSeesLatestStateAfterPreviousWrite() = runBlocking {
        // Regression guard: the block argument must receive the *current*
        // persisted state, not a stale snapshot captured before acquiring
        // the lock. Otherwise readers racing writers see pre-mutate data.
        val (store, id) = newStore()
        val readings = mutableListOf<Int>()

        withContext(Dispatchers.Default) {
            val jobs = (1..10).map { i ->
                async {
                    store.mutate(id) { current ->
                        synchronized(readings) { readings += current.timeline.tracks.size }
                        current.copy(
                            timeline = current.timeline.copy(
                                tracks = current.timeline.tracks + Track.Video(TrackId("t-$i")),
                            ),
                        )
                    }
                }
            }
            jobs.awaitAll()
        }

        // Each iteration must have observed one more track than the previous —
        // i.e. the sizes it saw are a permutation of 0..9, exactly.
        assertEquals((0 until 10).toSet(), readings.toSet())
    }

    @Test fun upsertPreservesOriginalCreationTimestamp() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val store = ProjectStoreTestKit.create(clock = clock)
        val id = ProjectId("p-ts")

        store.upsert("demo", Project(id = id, timeline = Timeline()))
        val created = store.summary(id)!!

        clock.instant = Instant.parse("2026-01-01T00:10:00Z")
        store.upsert("demo-2", Project(id = id, timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"))))))
        val updated = store.summary(id)!!

        assertEquals(created.createdAtEpochMs, updated.createdAtEpochMs)
        assertEquals(clock.instant.toEpochMilliseconds(), updated.updatedAtEpochMs)
    }
}
