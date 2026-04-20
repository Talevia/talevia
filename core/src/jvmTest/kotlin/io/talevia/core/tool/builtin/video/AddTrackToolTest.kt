package io.talevia.core.tool.builtin.video

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
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

class AddTrackToolTest {

    private val emittedSnapshots = mutableListOf<PartId>()

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { if (it is Part.TimelineSnapshot) emittedSnapshots += it.id },
        messages = emptyList(),
    )

    private suspend fun fixture(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p-1")
        store.upsert(
            "title",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(Track.Video(id = TrackId("existing-video")))),
            ),
        )
        return store to pid
    }

    @Test
    fun adds_video_track_with_generated_id() = runTest {
        val (store, pid) = fixture()
        val tool = AddTrackTool(store)

        val result = tool.execute(
            AddTrackTool.Input(projectId = pid.value, trackKind = "video"),
            ctx(),
        )

        assertEquals("video", result.data.trackKind)
        assertEquals(2, result.data.totalTrackCount)
        val after = store.get(pid)!!
        assertEquals(2, after.timeline.tracks.size)
        assertTrue(after.timeline.tracks.any { it is Track.Video && it.id.value == result.data.trackId })
    }

    @Test
    fun adds_audio_track_with_explicit_id() = runTest {
        val (store, pid) = fixture()
        val tool = AddTrackTool(store)

        val result = tool.execute(
            AddTrackTool.Input(projectId = pid.value, trackKind = "audio", trackId = "dialogue"),
            ctx(),
        )

        assertEquals("dialogue", result.data.trackId)
        val after = store.get(pid)!!
        assertTrue(after.timeline.tracks.any { it is Track.Audio && it.id.value == "dialogue" })
    }

    @Test
    fun adds_subtitle_track() = runTest {
        val (store, pid) = fixture()
        val result = AddTrackTool(store).execute(
            AddTrackTool.Input(projectId = pid.value, trackKind = "subtitle"),
            ctx(),
        )
        assertEquals("subtitle", result.data.trackKind)
        val after = store.get(pid)!!
        assertTrue(after.timeline.tracks.any { it is Track.Subtitle })
    }

    @Test
    fun adds_effect_track() = runTest {
        val (store, pid) = fixture()
        val result = AddTrackTool(store).execute(
            AddTrackTool.Input(projectId = pid.value, trackKind = "effect"),
            ctx(),
        )
        assertEquals("effect", result.data.trackKind)
        val after = store.get(pid)!!
        assertTrue(after.timeline.tracks.any { it is Track.Effect })
    }

    @Test
    fun trackKind_is_case_insensitive() = runTest {
        val (store, pid) = fixture()
        val result = AddTrackTool(store).execute(
            AddTrackTool.Input(projectId = pid.value, trackKind = " VIDEO "),
            ctx(),
        )
        assertEquals("video", result.data.trackKind)
    }

    @Test
    fun rejects_duplicate_track_id() = runTest {
        val (store, pid) = fixture()
        val tool = AddTrackTool(store)

        assertFailsWith<IllegalStateException> {
            tool.execute(
                AddTrackTool.Input(projectId = pid.value, trackKind = "video", trackId = "existing-video"),
                ctx(),
            )
        }
    }

    @Test
    fun rejects_unknown_kind() = runTest {
        val (store, pid) = fixture()
        val tool = AddTrackTool(store)

        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                AddTrackTool.Input(projectId = pid.value, trackKind = "music"),
                ctx(),
            )
        }
    }

    @Test
    fun rejects_missing_project() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val tool = AddTrackTool(store)

        assertFailsWith<IllegalStateException> {
            tool.execute(
                AddTrackTool.Input(projectId = "no-such", trackKind = "video"),
                ctx(),
            )
        }
    }

    @Test
    fun emits_timeline_snapshot() = runTest {
        val (store, pid) = fixture()

        AddTrackTool(store).execute(
            AddTrackTool.Input(projectId = pid.value, trackKind = "audio"),
            ctx(),
        )

        assertEquals(1, emittedSnapshots.size)
    }

    @Test
    fun multiple_audio_tracks_coexist() = runTest {
        val (store, pid) = fixture()
        val tool = AddTrackTool(store)

        tool.execute(AddTrackTool.Input(pid.value, "audio", "dialogue"), ctx())
        tool.execute(AddTrackTool.Input(pid.value, "audio", "music"), ctx())
        tool.execute(AddTrackTool.Input(pid.value, "audio", "ambient"), ctx())

        val after = store.get(pid)!!
        val audioIds = after.timeline.tracks.filterIsInstance<Track.Audio>().map { it.id.value }.toSet()
        assertEquals(setOf("dialogue", "music", "ambient"), audioIds)
    }
}
