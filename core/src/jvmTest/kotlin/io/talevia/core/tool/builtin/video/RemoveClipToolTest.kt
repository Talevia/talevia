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

class RemoveClipToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: RemoveClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = RemoveClipTool(store)
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

    private fun single(clipId: String, ripple: Boolean = false) = RemoveClipTool.Input(
        projectId = "p",
        clipIds = listOf(clipId),
        ripple = ripple,
    )

    @Test fun removesNamedClipAndKeepsSiblings() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val c = videoClip("c3", 6.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b, c)))),
            ),
        )

        val out = rig.tool.execute(single("c2"), rig.ctx).data
        assertEquals(1, out.results.size)
        val only = out.results.single()
        assertEquals("c2", only.clipId)
        assertEquals("v1", only.trackId)

        val refreshed = rig.store.get(rig.projectId)!!
        val ids = refreshed.timeline.tracks.single().clips.map { it.id.value }
        assertEquals(listOf("c1", "c3"), ids)
        val survivors = refreshed.timeline.tracks.single().clips
        assertEquals(Duration.ZERO, survivors[0].timeRange.start)
        assertEquals(6.seconds, survivors[1].timeRange.start)
    }

    @Test fun removesMultipleClipsAtomically() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val c = videoClip("c3", 6.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b, c)))),
            ),
        )

        val out = rig.tool.execute(
            RemoveClipTool.Input("p", listOf("c1", "c3"), ripple = false),
            rig.ctx,
        ).data
        assertEquals(2, out.results.size)
        val remainingIds = rig.store.get(rig.projectId)!!.timeline.tracks.single().clips.map { it.id.value }
        assertEquals(listOf("c2"), remainingIds)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b)))),
            ),
        )
        val before = rig.store.get(rig.projectId)!!
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                RemoveClipTool.Input("p", listOf("c1", "ghost")),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("clipIds[1]"), ex.message)
        val after = rig.store.get(rig.projectId)!!
        assertEquals(before.timeline, after.timeline)
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
        rig.tool.execute(single("vc"), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        assertTrue(refreshed.timeline.tracks.filterIsInstance<Track.Video>().single().clips.isEmpty())
        assertEquals(1, refreshed.timeline.tracks.filterIsInstance<Track.Audio>().single().clips.size)
    }

    @Test fun emptyTrackIsLeftInPlace() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a)))),
            ),
        )
        rig.tool.execute(single("c1"), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(1, refreshed.timeline.tracks.size)
        assertTrue(refreshed.timeline.tracks.single().clips.isEmpty())
    }

    @Test fun removesAudioClipFromAudioTrack() = runTest {
        val audio = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("voice"),
            volume = 0.6f,
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a"), listOf(audio)))),
            ),
        )
        val out = rig.tool.execute(single("a1"), rig.ctx).data
        assertEquals("a", out.results.single().trackId)
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
            rig.tool.execute(single("ghost"), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(1, refreshed.timeline.tracks.single().clips.size)
    }

    @Test fun emitsOneSnapshotPerBatch() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val c = videoClip("c3", 6.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b, c)))),
            ),
        )
        rig.tool.execute(RemoveClipTool.Input("p", listOf("c1", "c3")), rig.ctx)
        val snaps = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>()
        assertEquals(1, snaps.size)
        val ids = snaps.single().timeline.tracks.single().clips.map { it.id.value }
        assertEquals(listOf("c2"), ids)
    }

    @Test fun rippleShiftsDownstreamClipsLeft() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val c = videoClip("c3", 6.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b, c)))),
            ),
        )

        val out = rig.tool.execute(single("c2", ripple = true), rig.ctx).data
        assertTrue(out.rippled)
        assertEquals(1, out.results.single().shiftedClipCount)
        assertEquals(3.0, out.results.single().durationSeconds)

        val clips = rig.store.get(rig.projectId)!!.timeline.tracks.single().clips
        assertEquals(listOf("c1", "c3"), clips.map { it.id.value })
        assertEquals(Duration.ZERO, clips[0].timeRange.start)
        assertEquals(3.seconds, clips[1].timeRange.start)
    }

    @Test fun rippleDoesNotTouchOverlappingClipsOnSameTrack() = runTest {
        val c1 = videoClip("c1", Duration.ZERO, 5.seconds)
        val c2 = videoClip("c2", 2.seconds, 2.seconds)
        val c3 = videoClip("c3", 10.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(c1, c2, c3)))),
            ),
        )

        val out = rig.tool.execute(single("c1", ripple = true), rig.ctx).data
        assertEquals(1, out.results.single().shiftedClipCount)
        val clips = rig.store.get(rig.projectId)!!.timeline.tracks.single().clips
        val byId = clips.associateBy { it.id.value }
        assertEquals(2.seconds, byId["c2"]!!.timeRange.start, "overlapping clip must stay put")
        assertEquals(5.seconds, byId["c3"]!!.timeRange.start, "downstream clip shifted by 5s")
    }

    @Test fun rippleIsSingleTrackOnly() = runTest {
        val v1 = videoClip("v1", Duration.ZERO, 4.seconds)
        val v2 = videoClip("v2", 4.seconds, 2.seconds)
        val a1 = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 6.seconds),
            sourceRange = TimeRange(Duration.ZERO, 6.seconds),
            assetId = AssetId("voice"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("vt"), listOf(v1, v2)),
                        Track.Audio(TrackId("at"), listOf(a1)),
                    ),
                ),
            ),
        )

        rig.tool.execute(single("v1", ripple = true), rig.ctx)
        val project = rig.store.get(rig.projectId)!!
        val video = project.timeline.tracks.filterIsInstance<Track.Video>().single()
        val audio = project.timeline.tracks.filterIsInstance<Track.Audio>().single()
        assertEquals(Duration.ZERO, video.clips.single().timeRange.start)
        assertEquals(Duration.ZERO, audio.clips.single().timeRange.start)
        assertEquals(6.seconds, audio.clips.single().timeRange.duration)
    }

    @Test fun rejectsEmptyClipIdList() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(RemoveClipTool.Input("p", emptyList()), rig.ctx)
        }
    }
}
