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
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SplitClipToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: SplitClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = SplitClipTool(store)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        kotlinx.coroutines.runBlocking { store.upsert("test", project) }
        return Rig(store, tool, ctx, project.id)
    }

    private fun videoClip(id: String, start: Duration, duration: Duration, sourceStart: Duration = Duration.ZERO): Clip.Video =
        Clip.Video(
            id = ClipId(id),
            timeRange = TimeRange(start, duration),
            sourceRange = TimeRange(sourceStart, duration),
            assetId = AssetId("a-$id"),
        )

    private fun single(clipId: String, atTimelineSeconds: Double) = SplitClipTool.Input(
        projectId = "p",
        items = listOf(SplitClipTool.Item(clipId, atTimelineSeconds)),
    )

    @Test fun splitAtMidpointPartitionsSourceRange() = runTest {
        val clip = videoClip("c1", start = Duration.ZERO, duration = 10.seconds, sourceStart = 2.seconds)
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(clip)))),
        )
        val rig = newRig(project)

        val out = rig.tool.execute(single("c1", 5.0), rig.ctx).data.results.single()
        val refreshed = rig.store.get(rig.projectId)!!
        val clips = refreshed.timeline.tracks.single().clips.filterIsInstance<Clip.Video>().sortedBy { it.timeRange.start }
        assertEquals(2, clips.size)
        val (left, right) = clips[0] to clips[1]
        assertEquals(out.leftClipId, left.id.value)
        assertEquals(out.rightClipId, right.id.value)
        assertEquals(Duration.ZERO, left.timeRange.start)
        assertEquals(5.seconds, left.timeRange.duration)
        assertEquals(5.seconds, right.timeRange.start)
        assertEquals(5.seconds, right.timeRange.duration)
        assertEquals(2.seconds, left.sourceRange.start)
        assertEquals(5.seconds, left.sourceRange.duration)
        assertEquals(7.seconds, right.sourceRange.start)
        assertEquals(5.seconds, right.sourceRange.duration)
        assertEquals(clip.assetId, left.assetId)
        assertEquals(clip.assetId, right.assetId)
    }

    @Test fun batchSplitsMultipleClipsAtomically() = runTest {
        val c1 = videoClip("c1", start = Duration.ZERO, duration = 10.seconds)
        val c2 = videoClip("c2", start = 10.seconds, duration = 10.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(c1, c2)))),
            ),
        )
        val out = rig.tool.execute(
            SplitClipTool.Input(
                projectId = "p",
                items = listOf(
                    SplitClipTool.Item("c1", 5.0),
                    SplitClipTool.Item("c2", 15.0),
                ),
            ),
            rig.ctx,
        ).data
        assertEquals(2, out.results.size)
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(4, refreshed.timeline.tracks.single().clips.size)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val c1 = videoClip("c1", start = Duration.ZERO, duration = 10.seconds)
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(c1)))),
            ),
        )
        val before = rig.store.get(rig.projectId)!!
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SplitClipTool.Input(
                    projectId = "p",
                    items = listOf(
                        SplitClipTool.Item("c1", 5.0),
                        SplitClipTool.Item("ghost", 3.0),
                    ),
                ),
                rig.ctx,
            )
        }
        assertEquals(before.timeline, rig.store.get(rig.projectId)!!.timeline)
    }

    @Test fun splitAtClipStartIsRejected() = runTest {
        val clip = videoClip("c1", start = 2.seconds, duration = 5.seconds)
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(clip))))))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("c1", 2.0), rig.ctx)
        }
        assertTrue(ex.message!!.contains("outside"), "expected 'outside' guard, got: ${ex.message}")
    }

    @Test fun splitAtClipEndIsRejected() = runTest {
        val clip = videoClip("c1", start = 2.seconds, duration = 5.seconds)
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(clip))))))
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("c1", 7.0), rig.ctx)
        }
    }

    @Test fun splitOnAudioClipPartitionsBothRanges() = runTest {
        val audio = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 10.seconds),
            sourceRange = TimeRange(3.seconds, 10.seconds),
            assetId = AssetId("a"),
            volume = 0.8f,
        )
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(Track.Audio(TrackId("t"), listOf(audio))))))
        rig.tool.execute(single("a1", 4.0), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val clips = refreshed.timeline.tracks.single().clips.filterIsInstance<Clip.Audio>().sortedBy { it.timeRange.start }
        assertEquals(2, clips.size)
        assertEquals(3.seconds, clips[0].sourceRange.start)
        assertEquals(4.seconds, clips[0].sourceRange.duration)
        assertEquals(7.seconds, clips[1].sourceRange.start)
        assertEquals(6.seconds, clips[1].sourceRange.duration)
        assertTrue(clips.all { it.volume == 0.8f })
    }

    @Test fun splitOnTextClipDropsSourceRange() = runTest {
        val text = Clip.Text(
            id = ClipId("t1"),
            timeRange = TimeRange(1.seconds, 4.seconds),
            text = "hello",
        )
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("st"), listOf(text))))))
        rig.tool.execute(single("t1", 3.0), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val clips = refreshed.timeline.tracks.single().clips.filterIsInstance<Clip.Text>().sortedBy { it.timeRange.start }
        assertEquals(2, clips.size)
        assertEquals(2.seconds, clips[0].timeRange.duration)
        assertEquals(2.seconds, clips[1].timeRange.duration)
        assertTrue(clips.all { it.text == "hello" })
        assertTrue(clips.all { it.sourceRange == null })
    }

    @Test fun otherTracksUnaffected() = runTest {
        val video = videoClip("c1", start = Duration.ZERO, duration = 10.seconds)
        val audio = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 10.seconds),
            sourceRange = TimeRange(Duration.ZERO, 10.seconds),
            assetId = AssetId("a"),
        )
        val rig = newRig(Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(
                Track.Video(TrackId("v"), listOf(video)),
                Track.Audio(TrackId("a"), listOf(audio)),
            )),
        ))
        rig.tool.execute(single("c1", 5.0), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val audioTrack = refreshed.timeline.tracks.filterIsInstance<Track.Audio>().single()
        assertEquals(1, audioTrack.clips.size, "audio track must keep its single clip after video split")
        assertEquals("a1", audioTrack.clips.single().id.value)
        val videoTrack = refreshed.timeline.tracks.filterIsInstance<Track.Video>().single()
        assertEquals(2, videoTrack.clips.size)
    }

    @Test fun splitMissingClipIsRejected() = runTest {
        val clip = videoClip("c1", start = Duration.ZERO, duration = 5.seconds)
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(clip))))))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("missing", 1.0), rig.ctx)
        }
        assertTrue(ex.message!!.contains("not found"), ex.message)
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(1, refreshed.timeline.tracks.single().clips.size)
    }
}
