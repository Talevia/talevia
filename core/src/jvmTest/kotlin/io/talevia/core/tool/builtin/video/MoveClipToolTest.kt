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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MoveClipToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: MoveClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = MoveClipTool(store)
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

    private fun singleMove(
        clipId: String,
        timelineStartSeconds: Double? = null,
        toTrackId: String? = null,
    ) = MoveClipTool.Input(
        projectId = "p",
        items = listOf(MoveClipTool.Item(clipId, timelineStartSeconds, toTrackId)),
    )

    @Test fun movesClipAndReordersSiblings() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val c = videoClip("c3", 6.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b, c)))),
            ),
        )

        val out = rig.tool.execute(singleMove("c1", timelineStartSeconds = 9.0), rig.ctx).data
        val only = out.results.single()
        assertEquals("c1", only.clipId)
        assertEquals("v1", only.fromTrackId)
        assertEquals("v1", only.toTrackId)
        assertEquals(false, only.changedTrack)
        assertEquals(0.0, only.oldStartSeconds)
        assertEquals(9.0, only.newStartSeconds)

        val refreshed = rig.store.get(rig.projectId)!!
        val ids = refreshed.timeline.tracks.single().clips.map { it.id.value }
        assertEquals(listOf("c2", "c3", "c1"), ids)
    }

    @Test fun preservesDurationAndSourceRange() = runTest {
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(2.seconds, 5.seconds),
            sourceRange = TimeRange(10.seconds, 5.seconds),
            assetId = AssetId("a1"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )
        rig.tool.execute(singleMove("c1", timelineStartSeconds = 20.0), rig.ctx)
        val moved = rig.store.get(rig.projectId)!!
            .timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(20.seconds, moved.timeRange.start)
        assertEquals(5.seconds, moved.timeRange.duration)
        assertEquals(10.seconds, moved.sourceRange.start)
        assertEquals(5.seconds, moved.sourceRange.duration)
    }

    @Test fun batchMovesMultipleClipsAtomically() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val c = videoClip("c3", 6.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b, c)))),
            ),
        )
        rig.tool.execute(
            MoveClipTool.Input(
                projectId = "p",
                items = listOf(
                    MoveClipTool.Item("c1", timelineStartSeconds = 10.0),
                    MoveClipTool.Item("c2", timelineStartSeconds = 20.0),
                ),
            ),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val byId = refreshed.timeline.tracks.single().clips.associateBy { it.id.value }
        assertEquals(10.seconds, byId["c1"]!!.timeRange.start)
        assertEquals(20.seconds, byId["c2"]!!.timeRange.start)
        assertEquals(6.seconds, byId["c3"]!!.timeRange.start)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a)))),
            ),
        )
        val before = rig.store.get(rig.projectId)!!
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                MoveClipTool.Input(
                    projectId = "p",
                    items = listOf(
                        MoveClipTool.Item("c1", timelineStartSeconds = 5.0),
                        MoveClipTool.Item("ghost", timelineStartSeconds = 5.0),
                    ),
                ),
                rig.ctx,
            )
        }
        assertEquals(before.timeline, rig.store.get(rig.projectId)!!.timeline)
    }

    @Test fun otherTracksUnaffected() = runTest {
        val v = videoClip("vc", Duration.ZERO, 5.seconds)
        val a = Clip.Audio(
            id = ClipId("ac"),
            timeRange = TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("a"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1"), listOf(v)),
                        Track.Audio(TrackId("a1"), listOf(a)),
                    ),
                ),
            ),
        )
        rig.tool.execute(singleMove("vc", timelineStartSeconds = 10.0), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val audio = refreshed.timeline.tracks.filterIsInstance<Track.Audio>().single().clips.single()
        assertEquals(Duration.ZERO, audio.timeRange.start)
    }

    @Test fun overlappingMoveIsAllowed() = runTest {
        val a = videoClip("c1", Duration.ZERO, 5.seconds)
        val b = videoClip("c2", 5.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b)))),
            ),
        )
        rig.tool.execute(singleMove("c2", timelineStartSeconds = 2.0), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val moved = refreshed.timeline.tracks.single().clips.first { it.id.value == "c2" }
        assertEquals(2.seconds, moved.timeRange.start)
    }

    @Test fun missingClipFailsLoudly() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(singleMove("ghost", timelineStartSeconds = 5.0), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(Duration.ZERO, refreshed.timeline.tracks.single().clips.single().timeRange.start)
    }

    @Test fun negativeStartIsRejected() = runTest {
        val a = videoClip("c1", 3.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a)))),
            ),
        )
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(singleMove("c1", timelineStartSeconds = -1.0), rig.ctx)
        }
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(3.seconds, refreshed.timeline.tracks.single().clips.single().timeRange.start)
    }

    @Test fun crossTrackMovePreservesTime() = runTest {
        val v = videoClip("c1", 2.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1"), listOf(v)),
                        Track.Video(TrackId("v2")),
                    ),
                ),
            ),
        )
        val out = rig.tool.execute(singleMove("c1", toTrackId = "v2"), rig.ctx).data
        val only = out.results.single()
        assertEquals("v1", only.fromTrackId)
        assertEquals("v2", only.toTrackId)
        assertEquals(true, only.changedTrack)
        assertEquals(2.0, only.oldStartSeconds)
        assertEquals(2.0, only.newStartSeconds)
        val refreshed = rig.store.get(rig.projectId)!!
        val src = refreshed.timeline.tracks.first { it.id.value == "v1" }.clips
        val dst = refreshed.timeline.tracks.first { it.id.value == "v2" }.clips
        assertTrue(src.isEmpty(), "source track emptied")
        assertEquals(1, dst.size)
        assertEquals(2.seconds, dst.single().timeRange.start)
    }

    @Test fun crossTrackMoveWithRepositionShiftsTime() = runTest {
        val v = videoClip("c1", 1.seconds, 5.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1"), listOf(v)),
                        Track.Video(TrackId("v2")),
                    ),
                ),
            ),
        )
        rig.tool.execute(singleMove("c1", timelineStartSeconds = 10.0, toTrackId = "v2"), rig.ctx)
        val dst = rig.store.get(rig.projectId)!!.timeline.tracks.first { it.id.value == "v2" }.clips
        assertEquals(10.seconds, dst.single().timeRange.start)
    }

    @Test fun crossTrackKindMismatchFailsLoud() = runTest {
        val v = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1"), listOf(v)),
                        Track.Audio(TrackId("a1")),
                    ),
                ),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(singleMove("c1", toTrackId = "a1"), rig.ctx)
        }
        assertTrue("video clip onto audio track" in ex.message!!, ex.message)
        val refreshed = rig.store.get(rig.projectId)!!
        val src = refreshed.timeline.tracks.first { it.id.value == "v1" }.clips
        assertEquals(1, src.size)
    }

    @Test fun unknownTargetTrackFailsLoud() = runTest {
        val v = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(v)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(singleMove("c1", toTrackId = "nope"), rig.ctx)
        }
        assertTrue("nope" in ex.message!!, ex.message)
    }

    @Test fun toTrackIdEqualToCurrentIsSameTrackPath() = runTest {
        val v = videoClip("c1", 2.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(v)))),
            ),
        )
        val out = rig.tool.execute(
            singleMove("c1", timelineStartSeconds = 5.0, toTrackId = "v1"),
            rig.ctx,
        ).data
        val only = out.results.single()
        assertEquals("v1", only.fromTrackId)
        assertEquals("v1", only.toTrackId)
        assertEquals(false, only.changedTrack)
        assertEquals(5.0, only.newStartSeconds)
    }

    @Test fun emptyInputFailsLoud() = runTest {
        val v = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(v)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(singleMove("c1"), rig.ctx)
        }
        assertTrue("at least one of" in ex.message!!, ex.message)
    }

    @Test fun emitsOneSnapshotPerBatch() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a)))),
            ),
        )
        rig.tool.execute(singleMove("c1", timelineStartSeconds = 7.0), rig.ctx)
        val snaps = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>()
        assertEquals(1, snaps.size)
        val moved = snaps.single().timeline.tracks.single().clips.single()
        assertEquals(7.seconds, moved.timeRange.start)
    }
}
