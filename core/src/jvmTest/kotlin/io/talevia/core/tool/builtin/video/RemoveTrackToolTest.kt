package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RemoveTrackToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: RemoveTrackTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = RemoveTrackTool(store)
        val parts = mutableListOf<Part>()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { parts += it },
            messages = emptyList(),
        )
        kotlinx.coroutines.runBlocking { store.upsert("test", project) }
        return Rig(store, tool, ctx, project.id, parts)
    }

    private fun videoClip(id: String, start: Duration, duration: Duration): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start, duration),
        sourceRange = TimeRange(Duration.ZERO, duration),
        assetId = AssetId("a-$id"),
    )

    private fun single(trackId: String, force: Boolean = false) = RemoveTrackTool.Input(
        projectId = "p",
        trackIds = listOf(trackId),
        force = force,
    )

    @Test fun dropsEmptyTrackCleanly() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1")),
                        Track.Audio(TrackId("a1")),
                    ),
                ),
            ),
        )

        val out = rig.tool.execute(single("v1"), rig.ctx).data
        val only = out.results.single()
        assertEquals("v1", only.trackId)
        assertEquals("video", only.trackKind)
        assertEquals(0, only.droppedClipCount)

        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(1, refreshed.timeline.tracks.size)
        assertTrue(refreshed.timeline.tracks.single() is Track.Audio)
    }

    @Test fun dropsMultipleTracksAtomically() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1")),
                        Track.Audio(TrackId("a1")),
                        Track.Subtitle(TrackId("s1")),
                    ),
                ),
            ),
        )

        rig.tool.execute(RemoveTrackTool.Input("p", listOf("v1", "s1")), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(listOf("a1"), refreshed.timeline.tracks.map { it.id.value })
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1")),
                        Track.Audio(TrackId("a1")),
                    ),
                ),
            ),
        )
        val before = rig.store.get(rig.projectId)!!
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(RemoveTrackTool.Input("p", listOf("v1", "ghost")), rig.ctx)
        }
        val after = rig.store.get(rig.projectId)!!
        assertEquals(before.timeline, after.timeline)
    }

    @Test fun nonEmptyTrackWithoutForceThrows() = runTest {
        val c1 = videoClip("c1", Duration.ZERO, 3.seconds)
        val c2 = videoClip("c2", 3.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("v1"), listOf(c1, c2))),
                    duration = 6.seconds,
                ),
            ),
        )

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("v1"), rig.ctx)
        }
        val msg = ex.message!!
        assertTrue("2" in msg, "expected clip count in message: $msg")
        assertTrue("force=true" in msg, "expected force=true guidance in message: $msg")

        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(1, refreshed.timeline.tracks.size)
        assertEquals(2, refreshed.timeline.tracks.single().clips.size)
    }

    @Test fun nonEmptyTrackWithForceDropsTrackAndClips() = runTest {
        val c1 = videoClip("c1", Duration.ZERO, 3.seconds)
        val c2 = videoClip("c2", 3.seconds, 3.seconds)
        val keep = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 4.seconds),
            sourceRange = TimeRange(Duration.ZERO, 4.seconds),
            assetId = AssetId("voice"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1"), listOf(c1, c2)),
                        Track.Audio(TrackId("a1"), listOf(keep)),
                    ),
                    duration = 6.seconds,
                ),
            ),
        )

        val out = rig.tool.execute(single("v1", force = true), rig.ctx).data
        assertEquals(2, out.results.single().droppedClipCount)
        assertEquals("video", out.results.single().trackKind)

        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(1, refreshed.timeline.tracks.size)
        assertTrue(refreshed.timeline.tracks.single() is Track.Audio)
        assertEquals(4.seconds, refreshed.timeline.duration)
    }

    @Test fun removingOnlyTrackLeavesTimelineEmpty() = runTest {
        val c1 = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(Track.Video(TrackId("v1"), listOf(c1))),
                    duration = 3.seconds,
                ),
            ),
        )

        rig.tool.execute(single("v1", force = true), rig.ctx)

        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(0, refreshed.timeline.tracks.size)
        assertEquals(Duration.ZERO, refreshed.timeline.duration)
    }

    @Test fun missingTrackIdThrows() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1")))),
            ),
        )

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("ghost"), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)

        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(1, refreshed.timeline.tracks.size)
    }

    @Test fun emitsOneSnapshotPerBatch() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1")),
                        Track.Audio(TrackId("a1")),
                        Track.Subtitle(TrackId("s1")),
                    ),
                ),
            ),
        )
        rig.tool.execute(RemoveTrackTool.Input("p", listOf("v1", "s1")), rig.ctx)
        val snaps = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>()
        assertEquals(1, snaps.size)
        val remainingIds = snaps.single().timeline.tracks.map { it.id.value }
        assertEquals(listOf("a1"), remainingIds)
    }

    @Test fun otherTracksAreUntouched() = runTest {
        val v1 = videoClip("vc", Duration.ZERO, 5.seconds)
        val keep = Clip.Audio(
            id = ClipId("ac"),
            timeRange = TimeRange(Duration.ZERO, 7.seconds),
            sourceRange = TimeRange(Duration.ZERO, 7.seconds),
            assetId = AssetId("voice"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1"), listOf(v1)),
                        Track.Audio(TrackId("a1"), listOf(keep)),
                        Track.Subtitle(TrackId("s1")),
                    ),
                    duration = 7.seconds,
                ),
            ),
        )

        rig.tool.execute(single("v1", force = true), rig.ctx)

        val refreshed = rig.store.get(rig.projectId)!!
        val trackIds = refreshed.timeline.tracks.map { it.id.value }.toSet()
        assertEquals(setOf("a1", "s1"), trackIds)
        assertFalse(trackIds.contains("v1"))
        val audioClips = refreshed.timeline.tracks.filterIsInstance<Track.Audio>().single().clips
        assertEquals(1, audioClips.size)
        assertEquals("ac", audioClips.single().id.value)
        assertEquals(7.seconds, audioClips.single().timeRange.duration)
    }

    @Test fun rejectsEmptyList() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(RemoveTrackTool.Input("p", emptyList()), rig.ctx)
        }
    }
}
