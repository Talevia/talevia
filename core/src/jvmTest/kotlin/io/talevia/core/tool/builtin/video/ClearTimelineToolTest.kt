package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ClearTimelineToolTest {

    private val emittedSnapshots = mutableListOf<PartId>()

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { part -> if (part is Part.TimelineSnapshot) emittedSnapshots += part.id },
        messages = emptyList(),
    )

    private fun videoAsset(id: String) = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
        metadata = MediaMetadata(duration = 5.seconds, videoCodec = "h264"),
    )

    private suspend fun fixture(): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-1")

        val videoTrack = Track.Video(
            id = TrackId("v"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-1"),
                    timeRange = TimeRange(0.seconds, 4.seconds),
                    sourceRange = TimeRange(0.seconds, 4.seconds),
                    assetId = AssetId("v-a"),
                ),
                Clip.Video(
                    id = ClipId("c-2"),
                    timeRange = TimeRange(4.seconds, 4.seconds),
                    sourceRange = TimeRange(0.seconds, 4.seconds),
                    assetId = AssetId("v-a"),
                ),
            ),
        )
        val audioTrack = Track.Audio(
            id = TrackId("a"),
            clips = listOf(
                Clip.Audio(
                    id = ClipId("c-3"),
                    timeRange = TimeRange(0.seconds, 8.seconds),
                    sourceRange = TimeRange(0.seconds, 8.seconds),
                    assetId = AssetId("a-a"),
                ),
            ),
        )
        store.upsert(
            "title",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(videoTrack, audioTrack),
                    duration = 8.seconds,
                ),
                assets = listOf(videoAsset("v-a"), videoAsset("a-a")),
            ),
        )
        return store to pid
    }

    @Test
    fun clears_all_clips_preserving_tracks_by_default() = runTest {
        val (store, pid) = fixture()
        val tool = ClearTimelineTool(store)

        val result = tool.execute(ClearTimelineTool.Input(pid.value), ctx())

        assertEquals(3, result.data.removedClipCount)
        assertEquals(0, result.data.removedTrackCount)
        assertEquals(2, result.data.remainingTrackCount)

        val after = store.get(pid)!!
        assertEquals(2, after.timeline.tracks.size)
        assertTrue(after.timeline.tracks.all { it.clips.isEmpty() })
        assertEquals(Duration.ZERO, after.timeline.duration)
    }

    @Test
    fun drops_tracks_when_preserveTracks_false() = runTest {
        val (store, pid) = fixture()
        val tool = ClearTimelineTool(store)

        val result = tool.execute(
            ClearTimelineTool.Input(pid.value, preserveTracks = false),
            ctx(),
        )

        assertEquals(3, result.data.removedClipCount)
        assertEquals(2, result.data.removedTrackCount)
        assertEquals(0, result.data.remainingTrackCount)

        val after = store.get(pid)!!
        assertEquals(0, after.timeline.tracks.size)
        assertEquals(Duration.ZERO, after.timeline.duration)
    }

    @Test
    fun preserves_non_timeline_state() = runTest {
        val (store, pid) = fixture()
        val before = store.get(pid)!!
        val tool = ClearTimelineTool(store)

        tool.execute(ClearTimelineTool.Input(pid.value, preserveTracks = false), ctx())

        val after = store.get(pid)!!
        assertEquals(before.assets, after.assets)
        assertEquals(before.source, after.source)
        assertEquals(before.lockfile, after.lockfile)
        assertEquals(before.renderCache, after.renderCache)
        assertEquals(before.snapshots, after.snapshots)
        assertEquals(before.outputProfile, after.outputProfile)
        assertEquals(before.id, after.id)
    }

    @Test
    fun emits_timeline_snapshot() = runTest {
        val (store, pid) = fixture()
        val tool = ClearTimelineTool(store)

        tool.execute(ClearTimelineTool.Input(pid.value), ctx())

        assertEquals(1, emittedSnapshots.size)
    }

    @Test
    fun empty_timeline_is_no_op_but_still_succeeds() = runTest {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-empty")
        store.upsert("t", Project(id = pid, timeline = Timeline()))

        val tool = ClearTimelineTool(store)
        val result = tool.execute(ClearTimelineTool.Input(pid.value), ctx())

        assertEquals(0, result.data.removedClipCount)
        assertEquals(0, result.data.removedTrackCount)
        assertEquals(0, result.data.remainingTrackCount)
    }

    @Test
    fun rejects_missing_project() = runTest {
        val store = ProjectStoreTestKit.create()
        val tool = ClearTimelineTool(store)

        assertFailsWith<IllegalStateException> {
            tool.execute(ClearTimelineTool.Input("no-such-project"), ctx())
        }
    }

    @Test
    fun preserves_track_ids_when_preserveTracks_true() = runTest {
        val (store, pid) = fixture()
        val beforeIds = store.get(pid)!!.timeline.tracks.map { it.id }.toSet()

        ClearTimelineTool(store).execute(ClearTimelineTool.Input(pid.value), ctx())

        val afterIds = store.get(pid)!!.timeline.tracks.map { it.id }.toSet()
        assertEquals(beforeIds, afterIds)
    }

    @Test
    fun clear_persisted_across_reads() = runTest {
        val (store, pid) = fixture()
        ClearTimelineTool(store).execute(ClearTimelineTool.Input(pid.value), ctx())

        val firstRead = store.get(pid)!!
        val secondRead = store.get(pid)!!
        assertEquals(firstRead.timeline, secondRead.timeline)
        assertTrue(firstRead.timeline.tracks.all { it.clips.isEmpty() })
    }
}
