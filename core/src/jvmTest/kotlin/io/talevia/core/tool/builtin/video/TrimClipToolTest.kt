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
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `trim_clip` adjusts an existing clip's `sourceRange` and/or duration without
 * removing/re-adding (which would lose attached filters/transforms). Tests
 * exercise: tail trim (shrink), head trim (advance source.start, preserve
 * timeline.start), simultaneous head+tail, audio-clip parity, the Text-clip
 * rejection, the both-fields-omitted rejection, the asset-bounds guard, the
 * negative-time and zero-duration guards, the missing-clip fail-loud, and the
 * post-mutation snapshot for revert_timeline.
 */
class TrimClipToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val media: InMemoryMediaStorage,
        val tool: TrimClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
        val emittedParts: MutableList<Part>,
    )

    private suspend fun newRig(project: Project): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val media = InMemoryMediaStorage()
        val tool = TrimClipTool(store, media)
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
        return Rig(store, media, tool, ctx, project.id, parts)
    }

    private suspend fun Rig.importAsset(path: String, durationSeconds: Double): AssetId =
        media.import(MediaSource.File(path)) { MediaMetadata(duration = durationSeconds.seconds) }.id

    @Test fun trimsTailToShorterDuration() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/v.mp4", durationSeconds = 30.0)
        val clip = Clip.Video(
            id = ClipId("c1"),
            timeRange = TimeRange(2.seconds, 10.seconds),
            sourceRange = TimeRange(5.seconds, 10.seconds),
            assetId = assetId,
        )
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )

        val out = rig.tool.execute(
            TrimClipTool.Input(rig.projectId.value, "c1", newDurationSeconds = 4.0),
            rig.ctx,
        )
        assertEquals("c1", out.data.clipId)
        assertEquals("v1", out.data.trackId)
        assertEquals(5.0, out.data.newSourceStartSeconds, 0.001) // unchanged
        assertEquals(4.0, out.data.newDurationSeconds, 0.001)
        assertEquals(6.0, out.data.newTimelineEndSeconds, 0.001) // 2 + 4

        val refreshed = rig.store.get(rig.projectId)!!
        val trimmed = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(2.seconds, trimmed.timeRange.start) // preserved
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
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )

        rig.tool.execute(
            TrimClipTool.Input(rig.projectId.value, "c1", newSourceStartSeconds = 3.0),
            rig.ctx,
        )
        val refreshed = rig.store.get(rig.projectId)!!
        val trimmed = refreshed.timeline.tracks.single().clips.single() as Clip.Video
        // Timeline anchor stays at 7s — caller chains move_clip if they want to slide.
        assertEquals(7.seconds, trimmed.timeRange.start)
        assertEquals(10.seconds, trimmed.timeRange.duration) // duration unchanged
        assertEquals(3.seconds, trimmed.sourceRange.start)
        assertEquals(10.seconds, trimmed.sourceRange.duration) // duration unchanged
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
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )

        rig.tool.execute(
            TrimClipTool.Input(
                projectId = rig.projectId.value,
                clipId = "c1",
                newSourceStartSeconds = 4.0,
                newDurationSeconds = 3.5,
            ),
            rig.ctx,
        )
        val trimmed = rig.store.get(rig.projectId)!!
            .timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(0.seconds, trimmed.timeRange.start)
        assertEquals(3.5.seconds, trimmed.timeRange.duration)
        assertEquals(4.seconds, trimmed.sourceRange.start)
        assertEquals(3.5.seconds, trimmed.sourceRange.duration)
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
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Audio(TrackId("a1"), listOf(clip)))),
            ),
        )

        rig.tool.execute(
            TrimClipTool.Input(rig.projectId.value, "ac1", newDurationSeconds = 2.0),
            rig.ctx,
        )
        val trimmed = rig.store.get(rig.projectId)!!
            .timeline.tracks.single().clips.single() as Clip.Audio
        assertEquals(2.seconds, trimmed.timeRange.duration)
        assertEquals(2.seconds, trimmed.sourceRange.duration)
        // Volume (and other Audio-specific fields) preserved through trim.
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
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Subtitle(TrackId("s1"), listOf(text)))),
            ),
        )

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                TrimClipTool.Input(rig.projectId.value, "t1", newDurationSeconds = 2.0),
                rig.ctx,
            )
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
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(TrimClipTool.Input(rig.projectId.value, "c1"), rig.ctx)
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
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )

        // sourceStart 3 + duration 4 = 7s, asset is 5s — must refuse.
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                TrimClipTool.Input(
                    projectId = rig.projectId.value,
                    clipId = "c1",
                    newSourceStartSeconds = 3.0,
                    newDurationSeconds = 4.0,
                ),
                rig.ctx,
            )
        }
        assertTrue("extends past" in ex.message!!, ex.message)
        // Original clip untouched on failure.
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
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                TrimClipTool.Input(rig.projectId.value, "c1", newSourceStartSeconds = -0.5),
                rig.ctx,
            )
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
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                TrimClipTool.Input(rig.projectId.value, "c1", newDurationSeconds = 0.0),
                rig.ctx,
            )
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
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                TrimClipTool.Input(rig.projectId.value, "ghost", newDurationSeconds = 2.0),
                rig.ctx,
            )
        }
        assertTrue("ghost" in ex.message!!, ex.message)
        val refreshed = rig.store.get(rig.projectId)!!
        // Original duration untouched on failure.
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
        rig.store.upsert(
            "test",
            Project(
                id = rig.projectId,
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(clip)))),
            ),
        )

        rig.tool.execute(
            TrimClipTool.Input(rig.projectId.value, "c1", newDurationSeconds = 2.5),
            rig.ctx,
        )
        val snap = rig.emittedParts.filterIsInstance<Part.TimelineSnapshot>().single()
        val trimmed = snap.timeline.tracks.single().clips.single() as Clip.Video
        assertEquals(2.5.seconds, trimmed.timeRange.duration)
        assertEquals(2.5.seconds, trimmed.sourceRange.duration)
    }
}
