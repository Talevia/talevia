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
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
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
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Exercises `ClipActionTool(action="trim")` — reshaped from the legacy
 * `TrimClipToolTest` as part of `debt-video-clip-consolidate-verbs-phase-2`.
 * Every semantic case from the old suite is preserved.
 */
@OptIn(ExperimentalUuidApi::class)
class ClipActionTrimTest {

    private class Rig(
        val store: FileProjectStore,
        val tool: ClipActionTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    ) {
        private val assetsById = mutableMapOf<AssetId, MediaAsset>()

        fun importAsset(path: String, durationSeconds: Double): AssetId {
            val assetId = AssetId(Uuid.random().toString())
            assetsById[assetId] = MediaAsset(
                id = assetId,
                source = MediaSource.File(path),
                metadata = MediaMetadata(duration = durationSeconds.seconds),
            )
            return assetId
        }

        suspend fun upsertProject(tracks: List<Track>) {
            store.upsert(
                "test",
                Project(
                    id = projectId,
                    assets = assetsById.values.toList(),
                    timeline = Timeline(tracks = tracks),
                ),
            )
        }
    }

    private suspend fun newRig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        val tool = ClipActionTool(store)
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

    private fun single(
        clipId: String,
        newSourceStartSeconds: Double? = null,
        newDurationSeconds: Double? = null,
    ) = ClipActionTool.Input(
        projectId = "p",
        action = "trim",
        trimItems = listOf(ClipActionTool.TrimItem(clipId, newSourceStartSeconds, newDurationSeconds)),
    )

    @Test fun trimsTailToShorterDuration() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 30.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(2.seconds, 10.seconds),
            sourceRange = TimeRange(5.seconds, 10.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))

        val out = rig.tool.execute(single("c1", newDurationSeconds = 4.0), rig.ctx).data
        assertEquals("trim", out.action)
        val only = out.trimmed.single()
        assertEquals("c1", only.clipId)
        assertEquals("v1", only.trackId)
        assertEquals(5.0, only.newSourceStartSeconds, 0.001)
        assertEquals(4.0, only.newDurationSeconds, 0.001)
        assertEquals(6.0, only.newTimelineEndSeconds, 0.001)

        val refreshed = rig.store.get(rig.projectId)!!
        val trimmed = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(2.seconds, trimmed.timeRange.start)
        assertEquals(4.seconds, trimmed.timeRange.duration)
        assertEquals(5.seconds, trimmed.sourceRange.start)
        assertEquals(4.seconds, trimmed.sourceRange.duration)
    }

    @Test fun trimsHeadAndPreservesTimelineStart() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 30.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(7.seconds, 10.seconds),
            sourceRange = TimeRange(0.seconds, 10.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))

        rig.tool.execute(single("c1", newSourceStartSeconds = 3.0), rig.ctx)
        val refreshed = rig.store.get(rig.projectId)!!
        val trimmed = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(7.seconds, trimmed.timeRange.start)
        assertEquals(10.seconds, trimmed.timeRange.duration)
        assertEquals(3.seconds, trimmed.sourceRange.start)
        assertEquals(10.seconds, trimmed.sourceRange.duration)
    }

    @Test fun trimsHeadAndTailTogether() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 30.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 10.seconds),
            sourceRange = TimeRange(0.seconds, 10.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))

        rig.tool.execute(single("c1", newSourceStartSeconds = 4.0, newDurationSeconds = 3.5), rig.ctx)
        val trimmed = rig.store.get(rig.projectId)!!
            .timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(0.seconds, trimmed.timeRange.start)
        assertEquals(3.5.seconds, trimmed.timeRange.duration)
        assertEquals(4.seconds, trimmed.sourceRange.start)
        assertEquals(3.5.seconds, trimmed.sourceRange.duration)
    }

    @Test fun batchTrimsMultipleClipsAtomically() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 30.0)
        val c1 = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 10.seconds),
            sourceRange = TimeRange(0.seconds, 10.seconds),
            assetId = assetId,
        )
        val c2 = Clip.Video(
            id = ClipId("c2"),
            timeRange = TimeRange(10.seconds, 10.seconds),
            sourceRange = TimeRange(0.seconds, 10.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(c1, c2))))

        rig.tool.execute(
            ClipActionTool.Input(
                projectId = rig.projectId.value,
                action = "trim",
                trimItems = listOf(
                    ClipActionTool.TrimItem("c1", newDurationSeconds = 5.0),
                    ClipActionTool.TrimItem("c2", newSourceStartSeconds = 2.0, newDurationSeconds = 3.0),
                ),
            ),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val byId = refreshed.timeline.tracks.single().clips.filterIsInstance<Clip.Video>().associateBy { it.id.value }
        assertEquals(5.seconds, byId["c1"]!!.timeRange.duration)
        assertEquals(3.seconds, byId["c2"]!!.timeRange.duration)
        assertEquals(2.seconds, byId["c2"]!!.sourceRange.start)
    }

    @Test fun midBatchFailureLeavesProjectUntouched() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 5.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 4.seconds),
            sourceRange = TimeRange(0.seconds, 4.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))
        val before = rig.store.get(rig.projectId)!!
        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                ClipActionTool.Input(
                    projectId = rig.projectId.value,
                    action = "trim",
                    trimItems = listOf(
                        ClipActionTool.TrimItem("c1", newDurationSeconds = 3.0),
                        ClipActionTool.TrimItem("c1", newSourceStartSeconds = 4.0, newDurationSeconds = 4.0),
                    ),
                ),
                rig.ctx,
            )
        }
        assertEquals(before.timeline, rig.store.get(rig.projectId)!!.timeline)
    }

    @Test fun trimsAudioClip() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/voice.m4a", durationSeconds = 60.0)
        val clip = Clip.Audio(
            id = ClipId("ac1"),
            timeRange = TimeRange(1.seconds, 10.seconds),
            sourceRange = TimeRange(0.seconds, 10.seconds),
            assetId = assetId,
            volume = 0.8f,
        )
        rig.upsertProject(listOf(Track.Audio(TrackId("a1"), listOf(clip))))

        rig.tool.execute(single("ac1", newDurationSeconds = 2.0), rig.ctx)
        val trimmed = rig.store.get(rig.projectId)!!
            .timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(2.seconds, trimmed.timeRange.duration)
        assertEquals(2.seconds, trimmed.sourceRange.duration)
        assertEquals(0.8f, trimmed.volume)
    }

    @Test fun rejectsTextClip() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val text = Clip.Text(
            id = ClipId("t1"),
            timeRange = TimeRange(0.seconds, 5.seconds),
            text = "hello",
            style = TextStyle(),
        )
        rig.upsertProject(listOf(Track.Subtitle(TrackId("s1"), listOf(text))))

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("t1", newDurationSeconds = 2.0), rig.ctx)
        }
        assertTrue("text" in ex.message!! || "subtitle" in ex.message!!, ex.message)
    }

    @Test fun rejectsWhenBothFieldsOmitted() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 30.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("c1"), rig.ctx)
        }
        assertTrue("at least one" in ex.message!!, ex.message)
    }

    @Test fun rejectsTrimPastAssetDuration() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 5.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 4.seconds),
            sourceRange = TimeRange(0.seconds, 4.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))

        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("c1", newSourceStartSeconds = 3.0, newDurationSeconds = 4.0), rig.ctx)
        }
        assertTrue("extends past" in ex.message!!, ex.message)
        val refreshed = rig.store.get(rig.projectId)!!
        val unchanged = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(0.seconds, unchanged.sourceRange.start)
        assertEquals(4.seconds, unchanged.sourceRange.duration)
    }

    @Test fun rejectsNegativeSourceStart() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 10.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 3.seconds),
            sourceRange = TimeRange(0.seconds, 3.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))

        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("c1", newSourceStartSeconds = -0.5), rig.ctx)
        }
    }

    @Test fun rejectsZeroDuration() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 10.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 3.seconds),
            sourceRange = TimeRange(0.seconds, 3.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))

        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(single("c1", newDurationSeconds = 0.0), rig.ctx)
        }
    }

    @Test fun missingClipFailsLoudly() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 10.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 3.seconds),
            sourceRange = TimeRange(0.seconds, 3.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(single("ghost", newDurationSeconds = 2.0), rig.ctx)
        }
        assertTrue("ghost" in ex.message!!, ex.message)
        val refreshed = rig.store.get(rig.projectId)!!
        val unchanged = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(3.seconds, unchanged.timeRange.duration)
    }

    @Test fun emitsTimelineSnapshotForRevert() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 10.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 5.seconds),
            sourceRange = TimeRange(0.seconds, 5.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))

        rig.tool.execute(single("c1", newDurationSeconds = 2.5), rig.ctx)
        val snap = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>().single()
        val trimmed = snap.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(2.5.seconds, trimmed.timeRange.duration)
        assertEquals(2.5.seconds, trimmed.sourceRange.duration)
    }

    @Test fun trimRejectsConflictingPayloadFields() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 10.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(0.seconds, 3.seconds),
            sourceRange = TimeRange(0.seconds, 3.seconds),
            assetId = assetId,
        )
        rig.upsertProject(listOf(Track.Video(TrackId("v1"), listOf(clip))))

        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                ClipActionTool.Input(
                    projectId = rig.projectId.value,
                    action = "trim",
                    trimItems = listOf(ClipActionTool.TrimItem("c1", newDurationSeconds = 2.0)),
                    clipIds = listOf("c1"),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("rejects"), ex.message)
    }
}
