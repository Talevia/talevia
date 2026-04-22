package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Filter
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.Transform
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class DuplicateClipToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: DuplicateClipTool,
        val ctx: ToolContext,
    )

    private fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        kotlinx.coroutines.runBlocking { store.upsert("t", project) }
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = {},
            messages = emptyList(),
        )
        return Rig(store, DuplicateClipTool(store), ctx)
    }

    @Test fun duplicatesVideoClipPreservingFiltersAndBindings() = runTest {
        val v = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(Duration.ZERO, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("asset-a"),
            filters = listOf(Filter(name = "sepia"), Filter(name = "brightness", params = mapOf("amount" to 0.2f))),
            transforms = listOf(Transform(scaleX = 0.5f, scaleY = 0.5f, translateX = 100f)),
            sourceBinding = setOf(SourceNodeId("mei"), SourceNodeId("style")),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("vt"), listOf(v))),
                duration = 3.seconds,
            ),
        )
        val rig = newRig(project)

        val out = rig.tool.execute(
            DuplicateClipTool.Input("p", "c1", timelineStartSeconds = 10.0),
            rig.ctx,
        )
        assertNotEquals("c1", out.data.newClipId)
        assertEquals("vt", out.data.sourceTrackId)
        assertEquals("vt", out.data.targetTrackId)

        val saved = rig.store.get(ProjectId("p"))!!
        val track = saved.timeline.tracks.single() as Track.Video
        assertEquals(2, track.clips.size)
        val dup = track.clips.first { it.id.value == out.data.newClipId } as Clip.Video
        assertEquals(10.seconds, dup.timeRange.start)
        assertEquals(3.seconds, dup.timeRange.duration)
        // Attached state carried across.
        assertEquals(2, dup.filters.size)
        assertEquals("sepia", dup.filters[0].name)
        assertEquals(1, dup.transforms.size)
        assertEquals(0.5f, dup.transforms[0].scaleX)
        assertEquals(setOf(SourceNodeId("mei"), SourceNodeId("style")), dup.sourceBinding)
        assertEquals(AssetId("asset-a"), dup.assetId)
    }

    @Test fun duplicatesAudioClipWithEnvelope() = runTest {
        val a = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("voice"),
            volume = 0.6f,
            fadeInSeconds = 0.5f,
            fadeOutSeconds = 1.0f,
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Audio(TrackId("at"), listOf(a))),
                duration = 5.seconds,
            ),
        )
        val rig = newRig(project)

        rig.tool.execute(DuplicateClipTool.Input("p", "a1", 30.0), rig.ctx)
        val saved = rig.store.get(ProjectId("p"))!!
        val dup = (saved.timeline.tracks.single() as Track.Audio).clips
            .first { it.id.value != "a1" } as Clip.Audio
        assertEquals(0.6f, dup.volume)
        assertEquals(0.5f, dup.fadeInSeconds)
        assertEquals(1.0f, dup.fadeOutSeconds)
    }

    @Test fun duplicatesTextClipWithStyle() = runTest {
        val t = Clip.Text(
            id = ClipId("t1"),
            timeRange = TimeRange(Duration.ZERO, 2.seconds),
            text = "Hello world",
            style = TextStyle(fontSize = 72f, color = "#FF0000", bold = true),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Subtitle(TrackId("st"), listOf(t))),
                duration = 2.seconds,
            ),
        )
        val rig = newRig(project)

        rig.tool.execute(DuplicateClipTool.Input("p", "t1", 5.0), rig.ctx)
        val saved = rig.store.get(ProjectId("p"))!!
        val dup = (saved.timeline.tracks.single() as Track.Subtitle).clips
            .first { it.id.value != "t1" } as Clip.Text
        assertEquals("Hello world", dup.text)
        assertEquals(72f, dup.style.fontSize)
        assertEquals("#FF0000", dup.style.color)
        assertTrue(dup.style.bold)
    }

    @Test fun movesToDifferentSameKindTrack() = runTest {
        val v1 = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(Duration.ZERO, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("a"),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("main"), listOf(v1)),
                    Track.Video(TrackId("overlay"), emptyList()),
                ),
                duration = 3.seconds,
            ),
        )
        val rig = newRig(project)

        val out = rig.tool.execute(
            DuplicateClipTool.Input("p", "c1", 0.0, trackId = "overlay"),
            rig.ctx,
        )
        assertEquals("main", out.data.sourceTrackId)
        assertEquals("overlay", out.data.targetTrackId)
        val saved = rig.store.get(ProjectId("p"))!!
        val main = saved.timeline.tracks.first { it.id.value == "main" }
        val overlay = saved.timeline.tracks.first { it.id.value == "overlay" }
        assertEquals(1, main.clips.size)
        assertEquals(1, overlay.clips.size)
    }

    @Test fun refusesCrossKindDuplicate() = runTest {
        val v = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(Duration.ZERO, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("a"),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("vt"), listOf(v)),
                    Track.Audio(TrackId("at"), emptyList()),
                ),
                duration = 3.seconds,
            ),
        )
        val rig = newRig(project)

        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                DuplicateClipTool.Input("p", "c1", 0.0, trackId = "at"),
                rig.ctx,
            )
        }
        assertTrue("audio" in ex.message!! && "video" in ex.message!!, ex.message)
    }

    @Test fun refusesMissingClip() = runTest {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = emptyList()),
        )
        val rig = newRig(project)
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(DuplicateClipTool.Input("p", "ghost", 0.0), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)
    }

    @Test fun newClipIdIsUnique() = runTest {
        val v = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(Duration.ZERO, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("a"),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("vt"), listOf(v))),
                duration = 3.seconds,
            ),
        )
        val rig = newRig(project)
        val first = rig.tool.execute(DuplicateClipTool.Input("p", "c1", 10.0), rig.ctx)
        val second = rig.tool.execute(DuplicateClipTool.Input("p", "c1", 20.0), rig.ctx)
        assertNotEquals(first.data.newClipId, second.data.newClipId)
        val saved = rig.store.get(ProjectId("p"))!!
        assertEquals(3, (saved.timeline.tracks.single() as Track.Video).clips.size)
    }
}
