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
import io.talevia.core.domain.TextStyle
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
import kotlin.time.Duration.Companion.seconds

/**
 * `set_clip_volume` adjusts an audio clip's `volume` field in place — the
 * "lower the music" primitive that previously had no tool. Tests cover: the
 * happy path, mute (0.0), >1.0 amplification, the audio-clip-only guard
 * (video / text rejection), the negative-volume guard, the cap-exceeded
 * guard, the missing-clip fail-loud, and the post-mutation snapshot for
 * revert_timeline parity.
 */
class SetClipVolumeToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: SetClipVolumeTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private suspend fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = SetClipVolumeTool(store)
        val parts = mutableListOf<Part>()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { parts += it },
            messages = emptyList(),
        )
        store.upsert("test", project)
        return Rig(store, tool, ctx, project.id, parts)
    }

    private fun audioClip(id: String, volume: Float = 1.0f): Clip.Audio = Clip.Audio(
        id = ClipId(id),
        timeRange = TimeRange(0.seconds, 5.seconds),
        sourceRange = TimeRange(0.seconds, 5.seconds),
        assetId = AssetId("a-$id"),
        volume = volume,
    )

    @Test fun setsAudioClipVolumeAndPreservesOtherFields() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1", 1.0f))))),
            ),
        )
        val out = rig.tool.execute(
            SetClipVolumeTool.Input(rig.projectId.value, "c1", volume = 0.3f),
            rig.ctx,
        )
        assertEquals("c1", out.data.clipId)
        assertEquals("a1", out.data.trackId)
        assertEquals(1.0f, out.data.oldVolume)
        assertEquals(0.3f, out.data.newVolume)

        val refreshed = rig.store.get(rig.projectId)!!
        val updated = refreshed.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(0.3f, updated.volume)
        // Non-volume fields preserved.
        assertEquals(0.seconds, updated.timeRange.start)
        assertEquals(5.seconds, updated.sourceRange.duration)
        assertEquals(AssetId("a-c1"), updated.assetId)
    }

    @Test fun zeroVolumeMutesWithoutRemoving() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1", 0.5f))))),
            ),
        )
        rig.tool.execute(
            SetClipVolumeTool.Input(rig.projectId.value, "c1", volume = 0.0f),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val muted = refreshed.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(0.0f, muted.volume)
        // Clip is still on the timeline (mute, not remove).
        assertEquals(1, refreshed.timeline.tracks.single().clips.size)
    }

    @Test fun amplificationAboveOneIsAllowed() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        rig.tool.execute(
            SetClipVolumeTool.Input(rig.projectId.value, "c1", volume = 2.5f),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val amped = refreshed.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(2.5f, amped.volume)
    }

    @Test fun rejectsVideoClip() = runTest {
        val video = Clip.Video(
            id = ClipId("v1"),
            timeRange = TimeRange(0.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = AssetId("av"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(video)))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SetClipVolumeTool.Input(rig.projectId.value, "v1", volume = 0.5f),
                rig.ctx,
            )
        }
        assertTrue("audio" in ex.message!!, ex.message)
    }

    @Test fun rejectsTextClip() = runTest {
        val text = Clip.Text(
            id = ClipId("t1"),
            timeRange = TimeRange(0.seconds, 5.seconds),
            text = "hi",
            style = TextStyle(),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("s1"), listOf(text)))),
            ),
        )
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SetClipVolumeTool.Input(rig.projectId.value, "t1", volume = 0.5f),
                rig.ctx,
            )
        }
    }

    @Test fun rejectsNegativeVolume() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                SetClipVolumeTool.Input(rig.projectId.value, "c1", volume = -0.5f),
                rig.ctx,
            )
        }
        // Original volume untouched on failure.
        val refreshed = rig.store.get(rig.projectId)!!
        val unchanged = refreshed.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(1.0f, unchanged.volume)
    }

    @Test fun rejectsVolumeAboveCap() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                SetClipVolumeTool.Input(rig.projectId.value, "c1", volume = 5.0f),
                rig.ctx,
            )
        }
    }

    @Test fun missingClipFailsLoudly() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SetClipVolumeTool.Input(rig.projectId.value, "ghost", volume = 0.5f),
                rig.ctx,
            )
        }
        assertTrue("ghost" in ex.message!!, ex.message)
    }

    @Test fun emitsTimelineSnapshotForRevert() = runTest {
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(audioClip("c1"))))),
            ),
        )
        rig.tool.execute(
            SetClipVolumeTool.Input(rig.projectId.value, "c1", volume = 0.4f),
            rig.ctx,
        )
        val snap = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>().single()
        val updated = snap.timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(0.4f, updated.volume)
    }
}
