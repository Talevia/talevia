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
import io.talevia.core.domain.TextStyle
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
import kotlin.time.Duration.Companion.seconds

class MoveClipToTrackToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = {},
        messages = emptyList(),
    )

    private suspend fun fixture(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p")

        val videoTrackA = Track.Video(
            id = TrackId("v-a"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c-video"),
                    timeRange = TimeRange(0.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("asset-1"),
                ),
            ),
        )
        val videoTrackB = Track.Video(id = TrackId("v-b"))
        val audioTrack = Track.Audio(
            id = TrackId("a-a"),
            clips = listOf(
                Clip.Audio(
                    id = ClipId("c-audio"),
                    timeRange = TimeRange(0.seconds, 4.seconds),
                    sourceRange = TimeRange(0.seconds, 4.seconds),
                    assetId = AssetId("asset-2"),
                ),
            ),
        )
        val audioTrackB = Track.Audio(id = TrackId("a-b"))
        val subtitleTrackA = Track.Subtitle(
            id = TrackId("sub-a"),
            clips = listOf(
                Clip.Text(
                    id = ClipId("c-text"),
                    timeRange = TimeRange(0.seconds, 3.seconds),
                    text = "hello",
                    style = TextStyle(),
                ),
            ),
        )
        val subtitleTrackB = Track.Subtitle(id = TrackId("sub-b"))

        val project = Project(
            id = pid,
            timeline = Timeline(
                tracks = listOf(videoTrackA, videoTrackB, audioTrack, audioTrackB, subtitleTrackA, subtitleTrackB),
                duration = 5.seconds,
            ),
        )
        store.upsert("demo", project)
        return store to pid
    }

    @Test fun movesVideoClipBetweenVideoTracks() = runTest {
        val (store, pid) = fixture()
        val out = MoveClipToTrackTool(store).execute(
            MoveClipToTrackTool.Input(projectId = pid.value, clipId = "c-video", targetTrackId = "v-b"),
            ctx(),
        ).data
        assertEquals("v-a", out.fromTrackId)
        assertEquals("v-b", out.toTrackId)
        assertEquals(0.0, out.oldStartSeconds)
        assertEquals(0.0, out.newStartSeconds)

        val tracks = store.get(pid)!!.timeline.tracks
        val sourceTrack = tracks.first { it.id.value == "v-a" }
        val targetTrack = tracks.first { it.id.value == "v-b" }
        assertTrue(sourceTrack.clips.none { it.id.value == "c-video" })
        assertTrue(targetTrack.clips.any { it.id.value == "c-video" })
    }

    @Test fun movesWithShift() = runTest {
        val (store, pid) = fixture()
        val out = MoveClipToTrackTool(store).execute(
            MoveClipToTrackTool.Input(
                projectId = pid.value,
                clipId = "c-audio",
                targetTrackId = "a-b",
                newStartSeconds = 10.0,
            ),
            ctx(),
        ).data
        assertEquals(0.0, out.oldStartSeconds)
        assertEquals(10.0, out.newStartSeconds)
        val moved = store.get(pid)!!.timeline.tracks
            .first { it.id.value == "a-b" }.clips
            .first { it.id.value == "c-audio" }
        assertEquals(10.seconds, moved.timeRange.start)
        assertEquals(4.seconds, moved.timeRange.duration)
    }

    @Test fun movesTextClipBetweenSubtitleTracks() = runTest {
        val (store, pid) = fixture()
        val out = MoveClipToTrackTool(store).execute(
            MoveClipToTrackTool.Input(projectId = pid.value, clipId = "c-text", targetTrackId = "sub-b"),
            ctx(),
        ).data
        assertEquals("sub-a", out.fromTrackId)
        assertEquals("sub-b", out.toTrackId)
    }

    @Test fun refusesSameTrackMove() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            MoveClipToTrackTool(store).execute(
                MoveClipToTrackTool.Input(projectId = pid.value, clipId = "c-video", targetTrackId = "v-a"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("move_clip"))
    }

    @Test fun rejectsKindMismatchVideoToAudio() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            MoveClipToTrackTool(store).execute(
                MoveClipToTrackTool.Input(projectId = pid.value, clipId = "c-video", targetTrackId = "a-a"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("cannot move"))
        // Clip should stay on its source track.
        val sourceTrack = store.get(pid)!!.timeline.tracks.first { it.id.value == "v-a" }
        assertTrue(sourceTrack.clips.any { it.id.value == "c-video" })
    }

    @Test fun rejectsKindMismatchAudioToSubtitle() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalStateException> {
            MoveClipToTrackTool(store).execute(
                MoveClipToTrackTool.Input(projectId = pid.value, clipId = "c-audio", targetTrackId = "sub-a"),
                ctx(),
            )
        }
    }

    @Test fun rejectsMissingTargetTrack() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            MoveClipToTrackTool(store).execute(
                MoveClipToTrackTool.Input(projectId = pid.value, clipId = "c-video", targetTrackId = "nope"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("target track nope"))
    }

    @Test fun rejectsMissingClip() = runTest {
        val (store, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            MoveClipToTrackTool(store).execute(
                MoveClipToTrackTool.Input(projectId = pid.value, clipId = "nope", targetTrackId = "v-b"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("clip nope"))
    }

    @Test fun rejectsNegativeNewStart() = runTest {
        val (store, pid) = fixture()
        assertFailsWith<IllegalArgumentException> {
            MoveClipToTrackTool(store).execute(
                MoveClipToTrackTool.Input(
                    projectId = pid.value,
                    clipId = "c-video",
                    targetTrackId = "v-b",
                    newStartSeconds = -1.0,
                ),
                ctx(),
            )
        }
    }

    @Test fun preservesSourceRangeAndAssetId() = runTest {
        val (store, pid) = fixture()
        MoveClipToTrackTool(store).execute(
            MoveClipToTrackTool.Input(projectId = pid.value, clipId = "c-video", targetTrackId = "v-b"),
            ctx(),
        )
        val moved = store.get(pid)!!.timeline.tracks
            .first { it.id.value == "v-b" }.clips
            .first { it.id.value == "c-video" } as Clip.Video
        assertEquals(AssetId("asset-1"), moved.assetId)
        assertEquals(TimeRange(0.seconds, 5.seconds), moved.sourceRange)
    }
}
