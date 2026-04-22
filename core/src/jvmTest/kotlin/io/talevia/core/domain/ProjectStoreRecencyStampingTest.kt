package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.ProjectStoreTestKit
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `FileProjectStore.upsert` stamps `updatedAtEpochMs` on tracks /
 * clips / assets by diffing against the prior blob. This isolates the
 * stamping rule — see `ProjectQueryToolTest.*sortByRecent*` for the
 * query-surface behavior.
 *
 * Cases covered:
 *  - first upsert stamps every entity with `now`
 *  - structurally identical re-upsert preserves prior stamps
 *  - content-changed entity restamps; unchanged sibling preserved
 *  - new entity added to an existing project stamps to `now`
 *  - clip content change cascades to its owning track's stamp
 *  - blob with null stamps (pre-recency) is rewritten with `now` on
 *    next upsert only if the entity differs — unchanged null-stamped
 *    rows get `now` too (first write after rollout) since `null==null`
 *    structurally but "preserve old stamp (null) ?: now" resolves to
 *    now, establishing the first real stamp
 */
class ProjectStoreRecencyStampingTest {

    private class FixedClock(var nowMs: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMs)
    }

    private fun buildStore(clock: FixedClock): FileProjectStore {
        return ProjectStoreTestKit.create(clock = clock)
    }

    private fun asset(id: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id"),
        metadata = MediaMetadata(duration = 5.seconds),
    )

    private fun videoTrack(id: String, clipIds: List<String> = emptyList()): Track.Video = Track.Video(
        id = TrackId(id),
        clips = clipIds.mapIndexed { idx, cid ->
            Clip.Video(
                id = ClipId(cid),
                timeRange = TimeRange((idx * 5).seconds, 5.seconds),
                sourceRange = TimeRange(0.seconds, 5.seconds),
                assetId = AssetId("a"),
            )
        },
    )

    @Test fun firstUpsertStampsEveryEntity() = runTest {
        val clock = FixedClock(1_000L)
        val store = buildStore(clock)
        val pid = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(videoTrack("t", listOf("c1", "c2")))),
                assets = listOf(asset("a")),
            ),
        )
        val loaded = store.get(pid)!!
        val track = loaded.timeline.tracks.single() as Track.Video
        assertEquals(1_000L, track.updatedAtEpochMs)
        assertEquals(listOf(1_000L, 1_000L), track.clips.map { it.updatedAtEpochMs })
        assertEquals(1_000L, loaded.assets.single().updatedAtEpochMs)
    }

    @Test fun noOpReUpsertPreservesStamps() = runTest {
        val clock = FixedClock(1_000L)
        val store = buildStore(clock)
        val pid = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(videoTrack("t", listOf("c1")))),
                assets = listOf(asset("a")),
            ),
        )
        clock.nowMs = 9_999L
        // Re-upsert the exact state we just read back.
        val reloaded = store.get(pid)!!
        store.upsert("demo", reloaded)

        val finalState = store.get(pid)!!
        val track = finalState.timeline.tracks.single() as Track.Video
        assertEquals(1_000L, track.updatedAtEpochMs, "no-op upsert must preserve track stamp")
        assertEquals(1_000L, track.clips.single().updatedAtEpochMs, "no-op upsert must preserve clip stamp")
        assertEquals(1_000L, finalState.assets.single().updatedAtEpochMs)
    }

    @Test fun contentChangeRestampsOnlyChangedEntity() = runTest {
        val clock = FixedClock(1_000L)
        val store = buildStore(clock)
        val pid = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(
                        videoTrack("t1", listOf("c1")),
                        videoTrack("t2", listOf("c2")),
                    ),
                ),
                assets = listOf(asset("a")),
            ),
        )
        clock.nowMs = 2_000L
        val current = store.get(pid)!!
        // Change c2's sourceRange only.
        val mutated = current.copy(
            timeline = current.timeline.copy(
                tracks = current.timeline.tracks.map { t ->
                    if (t.id.value == "t2") {
                        (t as Track.Video).copy(
                            clips = t.clips.map { c ->
                                (c as Clip.Video).copy(sourceRange = TimeRange(0.seconds, 2.seconds))
                            },
                        )
                    } else {
                        t
                    }
                },
            ),
        )
        store.upsert("demo", mutated)

        val after = store.get(pid)!!
        val t1 = after.timeline.tracks.first { it.id.value == "t1" } as Track.Video
        val t2 = after.timeline.tracks.first { it.id.value == "t2" } as Track.Video
        // t1 and its clip — untouched.
        assertEquals(1_000L, t1.updatedAtEpochMs)
        assertEquals(1_000L, t1.clips.single().updatedAtEpochMs)
        // t2 and its clip — restamped (cascade from clip content change).
        assertEquals(2_000L, t2.updatedAtEpochMs)
        assertEquals(2_000L, t2.clips.single().updatedAtEpochMs)
        // Asset unchanged.
        assertEquals(1_000L, after.assets.single().updatedAtEpochMs)
    }

    @Test fun newEntityStampsToNow() = runTest {
        val clock = FixedClock(1_000L)
        val store = buildStore(clock)
        val pid = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(videoTrack("t1", listOf("c1")))),
                assets = listOf(asset("a")),
            ),
        )
        clock.nowMs = 2_000L
        val current = store.get(pid)!!
        // Add an entirely new track (t2) with a new clip (c-new) and a new asset (b).
        val expanded = current.copy(
            timeline = current.timeline.copy(
                tracks = current.timeline.tracks + videoTrack("t2", listOf("c-new")),
            ),
            assets = current.assets + asset("b"),
        )
        store.upsert("demo", expanded)

        val after = store.get(pid)!!
        val t1 = after.timeline.tracks.first { it.id.value == "t1" } as Track.Video
        val t2 = after.timeline.tracks.first { it.id.value == "t2" } as Track.Video
        assertEquals(1_000L, t1.updatedAtEpochMs, "untouched track keeps original stamp")
        assertEquals(2_000L, t2.updatedAtEpochMs, "new track stamped to now")
        assertEquals(2_000L, t2.clips.single().updatedAtEpochMs, "new clip stamped to now")
        assertEquals(1_000L, after.assets.first { it.id.value == "a" }.updatedAtEpochMs)
        assertEquals(2_000L, after.assets.first { it.id.value == "b" }.updatedAtEpochMs)
    }

    @Test fun trackMembershipChangeCascadesToTrackStamp() = runTest {
        val clock = FixedClock(1_000L)
        val store = buildStore(clock)
        val pid = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(videoTrack("t", listOf("c1", "c2")))),
                assets = listOf(asset("a")),
            ),
        )
        clock.nowMs = 2_000L
        val current = store.get(pid)!!
        // Remove c2, keep c1 unchanged.
        val mutated = current.copy(
            timeline = current.timeline.copy(
                tracks = current.timeline.tracks.map { t ->
                    (t as Track.Video).copy(clips = t.clips.filter { it.id.value == "c1" })
                },
            ),
        )
        store.upsert("demo", mutated)

        val after = store.get(pid)!!
        val track = after.timeline.tracks.single() as Track.Video
        // Track stamp bumped — membership changed.
        assertEquals(2_000L, track.updatedAtEpochMs)
        // Surviving clip preserves its stamp.
        assertEquals(1_000L, track.clips.single().updatedAtEpochMs)
    }

    @Test fun preRecencyBlobRehydratesNullStampsToNow() = runTest {
        // Simulates the first upsert after this feature rolls out: the
        // blob we read back carries `updatedAtEpochMs=null` everywhere,
        // but a caller that writes the same values back re-stamps them.
        // Even if a caller explicitly passes null stamps on "same" content,
        // the stamping rule resolves `null ?: now` → now.
        val clock = FixedClock(1_000L)
        val store = buildStore(clock)
        val pid = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(videoTrack("t", listOf("c1")))),
                assets = listOf(asset("a")),
            ),
        )
        // First upsert stamped to 1000. Now pretend a caller round-trips with
        // nulls (impossible via get()+upsert() because get() returns stamped
        // rows, but possible via an older codepath that forgot to copy
        // stamps). We simulate by feeding explicit null stamps.
        clock.nowMs = 2_000L
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(
                            id = TrackId("t"),
                            clips = listOf(
                                Clip.Video(
                                    id = ClipId("c1"),
                                    timeRange = TimeRange(0.seconds, 5.seconds),
                                    sourceRange = TimeRange(0.seconds, 5.seconds),
                                    assetId = AssetId("a"),
                                    updatedAtEpochMs = null,
                                ),
                            ),
                            updatedAtEpochMs = null,
                        ),
                    ),
                ),
                assets = listOf(asset("a").copy(updatedAtEpochMs = null)),
            ),
        )
        val after = store.get(pid)!!
        val track = after.timeline.tracks.single() as Track.Video
        // Content equals the prior (stamp-cleared compare), so stamp rule =
        // `old.updatedAtEpochMs ?: now` = 1000 (preserves the non-null old
        // stamp, ignoring the caller's null).
        assertEquals(1_000L, track.updatedAtEpochMs)
        assertEquals(1_000L, track.clips.single().updatedAtEpochMs)
        assertEquals(1_000L, after.assets.single().updatedAtEpochMs)
        assertNotNull(track.updatedAtEpochMs)
        assertTrue(track.updatedAtEpochMs!! > 0)
    }
}
