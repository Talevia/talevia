package io.talevia.core.tool.builtin.video

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AddSubtitleToolTest {

    @Test fun preservesExistingTrackOrderWhenUpdatingSubtitleTrack() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val store = SqlDelightProjectStore(db)
        val tool = AddSubtitleTool(store)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )

        val projectId = ProjectId("p")
        store.upsert(
            "demo",
            Project(
                id = projectId,
                timeline = Timeline(
                    tracks = listOf(
                        Track.Video(TrackId("v1")),
                        Track.Subtitle(TrackId("sub1")),
                        Track.Audio(TrackId("a1")),
                    ),
                ),
            ),
        )

        tool.execute(
            AddSubtitleTool.Input(
                projectId = projectId.value,
                text = "hello",
                timelineStartSeconds = 1.0,
                durationSeconds = 2.0,
            ),
            ctx,
        )

        val refreshed = store.get(projectId)!!
        assertEquals(listOf("v1", "sub1", "a1"), refreshed.timeline.tracks.map { it.id.value })
    }
}
