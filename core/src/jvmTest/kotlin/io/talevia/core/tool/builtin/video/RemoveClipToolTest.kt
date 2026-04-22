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

/**
 * `remove_clip` is the missing scalpel — until it landed, the only way to drop
 * a clip was `revert_timeline` (which would also discard every later edit) or
 * to never add the clip in the first place. Tests exercise the happy path,
 * cross-track scoping, the absent-clip guard, and the post-mutation snapshot
 * emission so `revert_timeline` keeps working.
 */
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

        val out = rig.tool.execute(RemoveClipTool.Input("p", "c2"), rig.ctx)
        assertEquals("c2", out.data.clipId)
        assertEquals("v1", out.data.trackId)
        assertEquals(2, out.data.remainingClipsOnTrack)

        val refreshed = rig.store.get(rig.projectId)!!
        val ids = refreshed.timeline.tracks.single().clips.map { it.id.value }
        assertEquals(listOf("c1", "c3"), ids)
        // Surviving clips' timeRanges are NOT shifted to fill the gap (no ripple).
        val survivors = refreshed.timeline.tracks.single().clips
        assertEquals(Duration.ZERO, survivors[0].timeRange.start)
        assertEquals(6.seconds, survivors[1].timeRange.start)
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
        rig.tool.execute(RemoveClipTool.Input("p", "vc"), rig.ctx)
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
        rig.tool.execute(RemoveClipTool.Input("p", "c1"), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        // The track itself remains so subsequent add_clip calls have a target.
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
        val out = rig.tool.execute(RemoveClipTool.Input("p", "a1"), rig.ctx)
        assertEquals("a", out.data.trackId)
        assertEquals(0, out.data.remainingClipsOnTrack)
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
            rig.tool.execute(RemoveClipTool.Input("p", "ghost"), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)
        // The original clip is untouched on failure.
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(1, refreshed.timeline.tracks.single().clips.size)
    }

    @Test fun emitsTimelineSnapshotForRevert() = runTest {
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b)))),
            ),
        )
        rig.tool.execute(RemoveClipTool.Input("p", "c1"), rig.ctx)
        val snap = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>().single()
        // Snapshot reflects the *post-mutation* timeline.
        val ids = snap.timeline.tracks.single().clips.map { it.id.value }
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

        val out = rig.tool.execute(RemoveClipTool.Input("p", "c2", ripple = true), rig.ctx)
        assertTrue(out.data.rippled)
        assertEquals(1, out.data.shiftedClipCount)
        assertEquals(3.0, out.data.shiftSeconds)

        val clips = rig.store.get(rig.projectId)!!.timeline.tracks.single().clips
        assertEquals(listOf("c1", "c3"), clips.map { it.id.value })
        // c1 stays where it was; c3 was at 6s, now sits at 3s (shifted by removed 3s duration).
        assertEquals(Duration.ZERO, clips[0].timeRange.start)
        assertEquals(3.seconds, clips[1].timeRange.start)
    }

    @Test fun rippleDoesNotTouchOverlappingClipsOnSameTrack() = runTest {
        // c2 sits INSIDE c1's range (PiP / layered edit). Removing c1 with ripple=true
        // should NOT shift c2 — the overlap was intentional and shifting it left
        // would destroy the layered placement.
        val c1 = videoClip("c1", Duration.ZERO, 5.seconds)
        val c2 = videoClip("c2", 2.seconds, 2.seconds)
        val c3 = videoClip("c3", 10.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(c1, c2, c3)))),
            ),
        )

        val out = rig.tool.execute(RemoveClipTool.Input("p", "c1", ripple = true), rig.ctx)
        // Only c3 (start=10 >= end=5) is shifted.
        assertEquals(1, out.data.shiftedClipCount)
        val clips = rig.store.get(rig.projectId)!!.timeline.tracks.single().clips
        val byId = clips.associateBy { it.id.value }
        assertEquals(2.seconds, byId["c2"]!!.timeRange.start, "overlapping clip must stay put")
        assertEquals(5.seconds, byId["c3"]!!.timeRange.start, "downstream clip shifted by 5s")
    }

    @Test fun rippleIsSingleTrackOnly() = runTest {
        // Video c1 is 4s; after removing c1 with ripple, video clips after
        // it shift — but audio clips on a separate track must NOT move.
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

        rig.tool.execute(RemoveClipTool.Input("p", "v1", ripple = true), rig.ctx)
        val project = rig.store.get(rig.projectId)!!
        val video = project.timeline.tracks.filterIsInstance<Track.Video>().single()
        val audio = project.timeline.tracks.filterIsInstance<Track.Audio>().single()
        // v2 shifted left by 4s → now at 0s.
        assertEquals(Duration.ZERO, video.clips.single().timeRange.start)
        // Audio clip untouched.
        assertEquals(Duration.ZERO, audio.clips.single().timeRange.start)
        assertEquals(6.seconds, audio.clips.single().timeRange.duration)
    }

    @Test fun rippleFalseMatchesPreRippleBehavior() = runTest {
        // Sanity: explicit ripple=false is identical to the old default (no shifting).
        val a = videoClip("c1", Duration.ZERO, 3.seconds)
        val b = videoClip("c2", 3.seconds, 3.seconds)
        val c = videoClip("c3", 6.seconds, 3.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(a, b, c)))),
            ),
        )

        val out = rig.tool.execute(RemoveClipTool.Input("p", "c2", ripple = false), rig.ctx)
        assertEquals(0, out.data.shiftedClipCount)
        val clips = rig.store.get(rig.projectId)!!.timeline.tracks.single().clips
        assertEquals(listOf("c1", "c3"), clips.map { it.id.value })
        assertEquals(6.seconds, clips.last().timeRange.start, "c3 must stay at its original 6s start")
    }
}
