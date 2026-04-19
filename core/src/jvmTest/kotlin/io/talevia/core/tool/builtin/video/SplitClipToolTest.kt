package io.talevia.core.tool.builtin.video

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
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

/**
 * The happy path for SplitClipTool is exercised via the M6 integration test,
 * but the edge cases — split points at the boundary, audio/text clip handling,
 * source-range offset correctness, and independence from other tracks — were
 * uncovered. Regressions in those areas would silently corrupt the timeline
 * (wrong media offsets during export) rather than throw.
 */
class SplitClipToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val tool: SplitClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private fun newRig(project: Project): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val store = SqlDelightProjectStore(db)
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

    @Test fun splitAtMidpointPartitionsSourceRange() = runTest {
        // A 10s clip sourced from [2s..12s) split at timeline 7s must produce
        // a left half of [0s..5s) with source [2s..7s) and a right half of
        // [5s..10s) with source [7s..12s). If sourceRange math drifts the
        // right half will render the wrong region during export.
        val clip = videoClip("c1", start = Duration.ZERO, duration = 10.seconds, sourceStart = 2.seconds)
        val project = Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(clip)))),
        )
        val rig = newRig(project)

        val result = rig.tool.execute(
            SplitClipTool.Input(projectId = "p", clipId = "c1", atTimelineSeconds = 5.0),
            rig.ctx,
        )
        val out = result.data

        val refreshed = rig.store.get(rig.projectId)!!
        val clips = refreshed.timeline.tracks.single().clips.filterIsInstance<Clip.Video>().sortedBy { it.timeRange.start }
        assertEquals(2, clips.size)
        val (left, right) = clips[0] to clips[1]
        assertEquals(out.leftClipId, left.id.value)
        assertEquals(out.rightClipId, right.id.value)
        // Timeline ranges: [0..5) and [5..10)
        assertEquals(Duration.ZERO, left.timeRange.start)
        assertEquals(5.seconds, left.timeRange.duration)
        assertEquals(5.seconds, right.timeRange.start)
        assertEquals(5.seconds, right.timeRange.duration)
        // Source ranges: [2..7) and [7..12)
        assertEquals(2.seconds, left.sourceRange.start)
        assertEquals(5.seconds, left.sourceRange.duration)
        assertEquals(7.seconds, right.sourceRange.start)
        assertEquals(5.seconds, right.sourceRange.duration)
        // AssetId preserved for both halves.
        assertEquals(clip.assetId, left.assetId)
        assertEquals(clip.assetId, right.assetId)
    }

    @Test fun splitAtClipStartIsRejected() = runTest {
        val clip = videoClip("c1", start = 2.seconds, duration = 5.seconds)
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(clip))))))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SplitClipTool.Input(projectId = "p", clipId = "c1", atTimelineSeconds = 2.0),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("outside"), "expected 'outside' guard, got: ${ex.message}")
    }

    @Test fun splitAtClipEndIsRejected() = runTest {
        val clip = videoClip("c1", start = 2.seconds, duration = 5.seconds)
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(Track.Video(TrackId("t"), listOf(clip))))))
        assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SplitClipTool.Input(projectId = "p", clipId = "c1", atTimelineSeconds = 7.0),
                rig.ctx,
            )
        }
        Unit
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
        rig.tool.execute(
            SplitClipTool.Input(projectId = "p", clipId = "a1", atTimelineSeconds = 4.0),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val clips = refreshed.timeline.tracks.single().clips.filterIsInstance<Clip.Audio>().sortedBy { it.timeRange.start }
        assertEquals(2, clips.size)
        assertEquals(3.seconds, clips[0].sourceRange.start)
        assertEquals(4.seconds, clips[0].sourceRange.duration)
        assertEquals(7.seconds, clips[1].sourceRange.start)
        assertEquals(6.seconds, clips[1].sourceRange.duration)
        // Non-range attributes (volume) survive the split.
        assertTrue(clips.all { it.volume == 0.8f })
    }

    @Test fun splitOnTextClipDropsSourceRange() = runTest {
        val text = Clip.Text(
            id = ClipId("t1"),
            timeRange = TimeRange(1.seconds, 4.seconds),
            text = "hello",
        )
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("st"), listOf(text))))))
        rig.tool.execute(
            SplitClipTool.Input(projectId = "p", clipId = "t1", atTimelineSeconds = 3.0),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val clips = refreshed.timeline.tracks.single().clips.filterIsInstance<Clip.Text>().sortedBy { it.timeRange.start }
        assertEquals(2, clips.size)
        assertEquals(2.seconds, clips[0].timeRange.duration)
        assertEquals(2.seconds, clips[1].timeRange.duration)
        assertTrue(clips.all { it.text == "hello" })
        assertTrue(clips.all { it.sourceRange == null })
    }

    @Test fun otherTracksUnaffected() = runTest {
        // Split on video track must not touch clips on an audio track with a
        // different clip id but overlapping timeline range.
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
        rig.tool.execute(
            SplitClipTool.Input(projectId = "p", clipId = "c1", atTimelineSeconds = 5.0),
            rig.ctx,
        )
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
            rig.tool.execute(
                SplitClipTool.Input(projectId = "p", clipId = "missing", atTimelineSeconds = 1.0),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"), ex.message)
        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(1, refreshed.timeline.tracks.single().clips.size)
    }
}
