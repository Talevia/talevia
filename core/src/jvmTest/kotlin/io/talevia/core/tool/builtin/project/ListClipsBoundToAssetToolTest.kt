package io.talevia.core.tool.builtin.project

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
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
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

class ListClipsBoundToAssetToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun fakeAsset(id: String): MediaAsset = MediaAsset(
        id = AssetId(id),
        source = MediaSource.File("/tmp/$id.mp4"),
        metadata = MediaMetadata(duration = 5.seconds),
    )

    /**
     * Fixture:
     *   - assetA: referenced by one video clip (c-v1) and one audio clip (c-a1)
     *   - assetB: referenced by video clip (c-v2) only
     *   - assetC: exists but is unreferenced
     *   - c-t1: a text clip (never matches)
     */
    private suspend fun fixture(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p")
        val videoClip1 = Clip.Video(
            id = ClipId("c-v1"),
            timeRange = TimeRange(0.seconds, 1.seconds),
            sourceRange = TimeRange(0.seconds, 1.seconds),
            assetId = AssetId("assetA"),
        )
        val videoClip2 = Clip.Video(
            id = ClipId("c-v2"),
            timeRange = TimeRange(1.seconds, 2.seconds),
            sourceRange = TimeRange(0.seconds, 2.seconds),
            assetId = AssetId("assetB"),
        )
        val audioClip1 = Clip.Audio(
            id = ClipId("c-a1"),
            timeRange = TimeRange(0.seconds, 3.seconds),
            sourceRange = TimeRange(0.seconds, 3.seconds),
            assetId = AssetId("assetA"),
        )
        val textClip = Clip.Text(
            id = ClipId("c-t1"),
            timeRange = TimeRange(0.seconds, 1.seconds),
            text = "hello",
            style = TextStyle(),
        )
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(id = TrackId("v"), clips = listOf(videoClip1, videoClip2)),
                        Track.Audio(id = TrackId("a"), clips = listOf(audioClip1)),
                        Track.Subtitle(id = TrackId("t"), clips = listOf(textClip)),
                    ),
                    duration = 3.seconds,
                ),
                assets = listOf(fakeAsset("assetA"), fakeAsset("assetB"), fakeAsset("assetC")),
            ),
        )
        return store to pid
    }

    @Test fun matchesSingleVideoClip() = runTest {
        val (store, pid) = fixture()
        val tool = ListClipsBoundToAssetTool(store)
        val out = tool.execute(
            ListClipsBoundToAssetTool.Input(projectId = pid.value, assetId = "assetB"),
            ctx(),
        ).data
        assertEquals(1, out.matchCount)
        val m = out.clips.single()
        assertEquals("c-v2", m.clipId)
        assertEquals("v", m.trackId)
        assertEquals("video", m.kind)
        assertEquals(1.0, m.startSeconds)
        assertEquals(2.0, m.durationSeconds)
    }

    @Test fun matchesSingleAudioClip() = runTest {
        val (store, pid) = fixture()
        // Drop the video reference to assetA so only the audio clip matches.
        val project = store.get(pid)!!
        val newTracks = project.timeline.tracks.map { track ->
            when (track) {
                is Track.Video -> Track.Video(
                    id = track.id,
                    clips = track.clips.filter { it.id.value != "c-v1" },
                )
                else -> track
            }
        }
        store.upsert("demo", project.copy(timeline = project.timeline.copy(tracks = newTracks)))

        val tool = ListClipsBoundToAssetTool(store)
        val out = tool.execute(
            ListClipsBoundToAssetTool.Input(projectId = pid.value, assetId = "assetA"),
            ctx(),
        ).data
        assertEquals(1, out.matchCount)
        val m = out.clips.single()
        assertEquals("c-a1", m.clipId)
        assertEquals("a", m.trackId)
        assertEquals("audio", m.kind)
    }

    @Test fun matchesMultipleClipsAcrossVideoAndAudio() = runTest {
        val (store, pid) = fixture()
        val tool = ListClipsBoundToAssetTool(store)
        val out = tool.execute(
            ListClipsBoundToAssetTool.Input(projectId = pid.value, assetId = "assetA"),
            ctx(),
        ).data
        assertEquals(2, out.matchCount)
        val byClip = out.clips.associateBy { it.clipId }
        assertTrue("c-v1" in byClip, "video clip referencing assetA must match")
        assertTrue("c-a1" in byClip, "audio clip referencing assetA must match")
        assertEquals("video", byClip["c-v1"]!!.kind)
        assertEquals("audio", byClip["c-a1"]!!.kind)
    }

    @Test fun returnsEmptyForUnreferencedAsset() = runTest {
        val (store, pid) = fixture()
        val tool = ListClipsBoundToAssetTool(store)
        val out = tool.execute(
            ListClipsBoundToAssetTool.Input(projectId = pid.value, assetId = "assetC"),
            ctx(),
        ).data
        assertEquals(0, out.matchCount)
        assertTrue(out.clips.isEmpty())
    }

    @Test fun throwsForUnknownAssetId() = runTest {
        val (store, pid) = fixture()
        val tool = ListClipsBoundToAssetTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                ListClipsBoundToAssetTool.Input(projectId = pid.value, assetId = "does-not-exist"),
                ctx(),
            )
        }
        assertTrue(
            ex.message!!.contains("not found"),
            "error should explain why: ${ex.message}",
        )
    }

    @Test fun throwsForMissingProject() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val tool = ListClipsBoundToAssetTool(store)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                ListClipsBoundToAssetTool.Input(projectId = "ghost", assetId = "assetA"),
                ctx(),
            )
        }
        assertTrue(
            ex.message!!.contains("not found"),
            "error should explain why: ${ex.message}",
        )
    }

    @Test fun textClipsNeverMatch() = runTest {
        // Build a project where a text clip shares its id with "assetA" just to be
        // extra-defensive: Clip.Text has no assetId, so the filter must still skip it.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p-text")
        val textClip = Clip.Text(
            id = ClipId("c-t1"),
            timeRange = TimeRange(0.seconds, 1.seconds),
            text = "a caption referencing 'assetA' in its contents",
            style = TextStyle(),
        )
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Subtitle(id = TrackId("t"), clips = listOf(textClip)),
                    ),
                    duration = 1.seconds,
                ),
                assets = listOf(fakeAsset("assetA")),
            ),
        )
        val tool = ListClipsBoundToAssetTool(store)
        val out = tool.execute(
            ListClipsBoundToAssetTool.Input(projectId = pid.value, assetId = "assetA"),
            ctx(),
        ).data
        assertEquals(0, out.matchCount, "text clips have no assetId and must never match")
    }
}
