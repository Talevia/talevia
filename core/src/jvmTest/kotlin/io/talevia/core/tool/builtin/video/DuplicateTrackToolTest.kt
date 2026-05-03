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
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Cycle 151 absorbed the standalone `DuplicateTrackTool` into
 * [TimelineActionTool] as `action="duplicate"`; these tests pin the same
 * semantics on the dispatcher (rename of class, same fixture, same
 * assertions) so a future fold doesn't silently lose the contract.
 */
class DuplicateTrackToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: TimelineActionTool,
        val ctx: ToolContext,
        val emittedParts: MutableList<Part>,
    )

    private fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        kotlinx.coroutines.runBlocking { store.upsert("t", project) }
        val emitted = mutableListOf<Part>()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { emitted += it },
            messages = emptyList(),
        )
        return Rig(store, TimelineActionTool(store), ctx, emitted)
    }

    private fun single(sourceTrackId: String, newTrackId: String? = null) =
        TimelineActionTool.Input(
            projectId = "p",
            action = "duplicate_track",
            items = listOf(TimelineActionTool.DuplicateItem(sourceTrackId, newTrackId)),
        )

    @Test fun duplicatesVideoTrackWithThreeClipsAndFreshIds() = runTest {
        val clips = (0 until 3).map { i ->
            Clip.Video(
                id = ClipId("v$i"),
                timeRange = TimeRange((i * 5).seconds, 5.seconds),
                sourceRange = TimeRange(Duration.ZERO, 5.seconds),
                assetId = AssetId("asset-$i"),
                filters = listOf(Filter(name = "sepia"), Filter(name = "brightness", params = mapOf("amount" to 0.3f))),
                transforms = listOf(Transform(scaleX = 0.5f, translateX = 10f)),
                sourceBinding = setOf(SourceNodeId("node-$i")),
            )
        }
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("vt"), clips)),
                duration = 15.seconds,
            ),
        )
        val rig = newRig(project)

        val out = rig.tool.execute(single("vt"), rig.ctx).data
        val only = out.duplicateResults.single()
        assertEquals("vt", only.sourceTrackId)
        assertEquals("vt-copy-1", only.newTrackId)
        assertEquals(3, only.clipCount)
        assertEquals("duplicate_track", out.action)

        val saved = rig.store.get(ProjectId("p"))!!
        assertEquals(2, saved.timeline.tracks.size)
        val copy = saved.timeline.tracks.first { it.id.value == "vt-copy-1" } as Track.Video
        assertEquals(3, copy.clips.size)
        val originalIds = clips.map { it.id.value }.toSet()
        val copyIds = copy.clips.map { it.id.value }.toSet()
        assertTrue(copyIds.none { it in originalIds })
        assertEquals(3, copyIds.size)
        copy.clips.forEachIndexed { i, cloned ->
            cloned as Clip.Video
            val src = clips[i]
            assertEquals(src.timeRange, cloned.timeRange)
            assertEquals(src.sourceRange, cloned.sourceRange)
            assertEquals(src.assetId, cloned.assetId)
            assertEquals(src.filters, cloned.filters)
            assertEquals(src.transforms, cloned.transforms)
            assertEquals(src.sourceBinding, cloned.sourceBinding)
        }
    }

    @Test fun duplicatesAudioTrackPreservingVolumeAndFades() = runTest {
        val clip = Clip.Audio(
            id = ClipId("a1"),
            timeRange = TimeRange(Duration.ZERO, 6.seconds),
            sourceRange = TimeRange(Duration.ZERO, 6.seconds),
            assetId = AssetId("voice"),
            volume = 0.7f,
            fadeInSeconds = 0.5f,
            fadeOutSeconds = 1.25f,
            sourceBinding = setOf(SourceNodeId("dialogue")),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Audio(TrackId("at"), listOf(clip))),
                duration = 6.seconds,
            ),
        )
        val rig = newRig(project)

        rig.tool.execute(single("at"), rig.ctx)

        val saved = rig.store.get(ProjectId("p"))!!
        val copy = saved.timeline.tracks.first { it.id.value == "at-copy-1" } as Track.Audio
        val dup = copy.clips.single() as Clip.Audio
        assertNotEquals("a1", dup.id.value)
        assertEquals(0.7f, dup.volume)
        assertEquals(0.5f, dup.fadeInSeconds)
        assertEquals(1.25f, dup.fadeOutSeconds)
        assertEquals(setOf(SourceNodeId("dialogue")), dup.sourceBinding)
    }

    @Test fun duplicatesSubtitleTrackPreservingTextAndStyle() = runTest {
        val t1 = Clip.Text(
            id = ClipId("t1"),
            timeRange = TimeRange(Duration.ZERO, 2.seconds),
            text = "Hello",
            style = TextStyle(fontSize = 64f, color = "#00FF00", bold = true, italic = true),
        )
        val t2 = Clip.Text(
            id = ClipId("t2"),
            timeRange = TimeRange(3.seconds, 2.seconds),
            text = "World",
            style = TextStyle(fontSize = 32f, color = "#0000FF"),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Subtitle(TrackId("st"), listOf(t1, t2))),
                duration = 5.seconds,
            ),
        )
        val rig = newRig(project)

        rig.tool.execute(single("st"), rig.ctx)

        val saved = rig.store.get(ProjectId("p"))!!
        val copy = saved.timeline.tracks.first { it.id.value == "st-copy-1" } as Track.Subtitle
        assertEquals(2, copy.clips.size)
        val first = copy.clips[0] as Clip.Text
        val second = copy.clips[1] as Clip.Text
        assertNotEquals("t1", first.id.value)
        assertNotEquals("t2", second.id.value)
        assertEquals("Hello", first.text)
        assertEquals("World", second.text)
        assertEquals(64f, first.style.fontSize)
        assertTrue(first.style.bold)
        assertTrue(first.style.italic)
        assertEquals("#0000FF", second.style.color)
    }

    @Test fun duplicatesEffectTrack() = runTest {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Effect(TrackId("et"), emptyList())),
            ),
        )
        val rig = newRig(project)

        val out = rig.tool.execute(single("et"), rig.ctx).data
        assertEquals(0, out.duplicateResults.single().clipCount)
        val saved = rig.store.get(ProjectId("p"))!!
        val copy = saved.timeline.tracks.firstOrNull { it.id.value == "et-copy-1" }
        assertTrue(copy is Track.Effect, "expected Track.Effect copy, got ${copy?.let { it::class.simpleName }}")
    }

    @Test fun explicitNewTrackIdIsRespected() = runTest {
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(Duration.ZERO, 3.seconds),
            sourceRange = TimeRange(Duration.ZERO, 3.seconds),
            assetId = AssetId("a"),
        )
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(Track.Video(TrackId("main"), listOf(clip))),
                duration = 3.seconds,
            ),
        )
        val rig = newRig(project)

        val out = rig.tool.execute(single("main", newTrackId = "variantA"), rig.ctx).data
        assertEquals("variantA", out.duplicateResults.single().newTrackId)
        val saved = rig.store.get(ProjectId("p"))!!
        assertTrue(saved.timeline.tracks.any { it.id.value == "variantA" })
    }

    @Test fun batchDuplicatesMultipleTracksAtomically() = runTest {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("vt")),
                    Track.Audio(TrackId("at")),
                ),
            ),
        )
        val rig = newRig(project)

        rig.tool.execute(
            TimelineActionTool.Input(
                projectId = "p",
                action = "duplicate_track",
                items = listOf(
                    TimelineActionTool.DuplicateItem("vt"),
                    TimelineActionTool.DuplicateItem("at"),
                ),
            ),
            rig.ctx,
        )
        val saved = rig.store.get(ProjectId("p"))!!
        val ids = saved.timeline.tracks.map { it.id.value }.toSet()
        assertEquals(setOf("vt", "at", "vt-copy-1", "at-copy-1"), ids)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("vt")),
                    Track.Audio(TrackId("at")),
                ),
            ),
        )
        val rig = newRig(project)
        val before = rig.store.get(ProjectId("p"))!!
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                TimelineActionTool.Input(
                    projectId = "p",
                    action = "duplicate_track",
                    items = listOf(
                        TimelineActionTool.DuplicateItem("vt"),
                        TimelineActionTool.DuplicateItem("ghost"),
                    ),
                ),
                rig.ctx,
            )
        }
        assertEquals(before.timeline, rig.store.get(ProjectId("p"))!!.timeline)
    }

    @Test fun unknownSourceTrackIdThrows() = runTest {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("vt")))),
        )
        val rig = newRig(project)

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("ghost"), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)
    }

    @Test fun collidingExplicitNewTrackIdThrows() = runTest {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("main")),
                    Track.Audio(TrackId("dialogue")),
                ),
            ),
        )
        val rig = newRig(project)

        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("main", newTrackId = "dialogue"), rig.ctx)
        }
        assertTrue("dialogue" in ex.message!!, ex.message)
    }

    @Test fun emitsExactlyOneTimelineSnapshot() = runTest {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("vt")))),
        )
        val rig = newRig(project)

        rig.tool.execute(single("vt"), rig.ctx)

        val snaps = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>()
        assertEquals(1, snaps.size)
        assertEquals(2, snaps.single().timeline.tracks.size)
    }

    @Test fun autoGeneratedIdSkipsCollisions() = runTest {
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(
                tracks = listOf(
                    Track.Video(TrackId("vt")),
                    Track.Video(TrackId("vt-copy-1")),
                    Track.Video(TrackId("vt-copy-2")),
                ),
            ),
        )
        val rig = newRig(project)

        val out = rig.tool.execute(single("vt"), rig.ctx).data
        assertEquals("vt-copy-3", out.duplicateResults.single().newTrackId)
    }
}
