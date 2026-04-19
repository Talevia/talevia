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
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.InMemoryMediaStorage
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AddClipToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val media: InMemoryMediaStorage,
        val tool: AddClipTool,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private suspend fun newRig(project: Project): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val store = SqlDelightProjectStore(db)
        val media = InMemoryMediaStorage()
        val tool = AddClipTool(store, media)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        store.upsert("test", project)
        return Rig(store, media, tool, ctx, project.id)
    }

    private suspend fun Rig.importAsset(path: String, durationSeconds: Double): AssetId =
        media.import(MediaSource.File(path)) { MediaMetadata(duration = durationSeconds.seconds) }.id

    @Test fun returnsTheInsertedClipWhenAddingIntoMiddleOfTrack() = runTest {
        val existing = Clip.Video(
            id = ClipId("existing"),
            timeRange = TimeRange(5.seconds, 5.seconds),
            sourceRange = TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("asset-existing"),
        )
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(Track.Video(TrackId("v1"), listOf(existing)))),
            ),
        )
        val assetId = rig.importAsset("/tmp/new.mp4", durationSeconds = 3.0)

        val result = rig.tool.execute(
            AddClipTool.Input(
                projectId = rig.projectId.value,
                assetId = assetId.value,
                timelineStartSeconds = 1.0,
                durationSeconds = 1.5,
            ),
            rig.ctx,
        )

        val out = result.data
        assertEquals(1.0, out.timelineStartSeconds, 0.001)
        assertEquals(2.5, out.timelineEndSeconds, 0.001)

        val refreshed = rig.store.get(rig.projectId)!!
        val added = refreshed.timeline.tracks.flatMap { it.clips }
            .filterIsInstance<Clip.Video>()
            .first { it.id.value == out.clipId }
        assertEquals(1.seconds, added.timeRange.start)
        assertEquals(1.5.seconds, added.timeRange.duration)
        assertEquals(assetId, added.assetId)
    }

    @Test fun explicitNonVideoTrackIsRejected() = runTest {
        val audioTrack = Track.Audio(TrackId("a1"))
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline(tracks = listOf(audioTrack))))
        val assetId = rig.importAsset("/tmp/audio.mp4", durationSeconds = 3.0)

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                AddClipTool.Input(
                    projectId = rig.projectId.value,
                    assetId = assetId.value,
                    trackId = "a1",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not a video track"), ex.message)
    }

    @Test fun sourceStartPastAssetDurationIsRejected() = runTest {
        val rig = newRig(Project(id = ProjectId("p"), timeline = Timeline()))
        val assetId = rig.importAsset("/tmp/short.mp4", durationSeconds = 2.0)

        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                AddClipTool.Input(
                    projectId = rig.projectId.value,
                    assetId = assetId.value,
                    sourceStartSeconds = 3.0,
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("exceeds asset duration"), ex.message)
    }

    @Test fun preservesExistingTrackOrderWhenUpdatingVideoTrack() = runTest {
        val subtitleTrack = Track.Subtitle(TrackId("s1"))
        val videoTrack = Track.Video(TrackId("v1"))
        val audioTrack = Track.Audio(TrackId("a1"))
        val rig = newRig(
            Project(
                id = ProjectId("p"),
                timeline = Timeline(tracks = listOf(subtitleTrack, videoTrack, audioTrack)),
            ),
        )
        val assetId = rig.importAsset("/tmp/new.mp4", durationSeconds = 3.0)

        rig.tool.execute(
            AddClipTool.Input(
                projectId = rig.projectId.value,
                assetId = assetId.value,
                trackId = "v1",
            ),
            rig.ctx,
        )

        val refreshed = rig.store.get(rig.projectId)!!
        assertEquals(listOf("s1", "v1", "a1"), refreshed.timeline.tracks.map { it.id.value })
    }
}
