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
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ExportToolTest {

    private class CountingEngine : VideoEngine {
        var renderCalls: Int = 0
            private set

        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

        override fun render(timeline: Timeline, output: OutputSpec): Flow<RenderProgress> = flow {
            renderCalls += 1
            emit(RenderProgress.Started("job"))
            emit(RenderProgress.Completed("job", output.targetPath))
        }

        override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray = ByteArray(0)
    }

    private fun ctx() = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun newFixture(): Triple<SqlDelightProjectStore, CountingEngine, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val engine = CountingEngine()
        val projectId = ProjectId("p")
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 5.seconds),
                            sourceRange = TimeRange(0.seconds, 5.seconds),
                            assetId = AssetId("a1"),
                        ),
                    ),
                ),
            ),
            duration = 5.seconds,
        )
        store.upsert("demo", Project(id = projectId, timeline = timeline))
        return Triple(store, engine, projectId)
    }

    @Test fun secondExportWithIdenticalInputsIsCacheHit() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4")

        val first = tool.execute(input, ctx())
        assertEquals(false, first.data.cacheHit)
        assertEquals(1, engine.renderCalls)

        val second = tool.execute(input, ctx())
        assertEquals(true, second.data.cacheHit, "second call with identical inputs must hit the render cache")
        assertEquals(1, engine.renderCalls, "engine must not be re-invoked on cache hit")
        assertEquals(first.data.outputPath, second.data.outputPath)
    }

    @Test fun changingTimelineInvalidatesCache() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4")

        tool.execute(input, ctx())
        assertEquals(1, engine.renderCalls)

        // Mutate the project — add a second clip.
        store.mutate(pid) { p ->
            val track = p.timeline.tracks.first() as Track.Video
            val extra = Clip.Video(
                id = ClipId("c2"),
                timeRange = TimeRange(5.seconds, 3.seconds),
                sourceRange = TimeRange(0.seconds, 3.seconds),
                assetId = AssetId("a2"),
            )
            p.copy(
                timeline = p.timeline.copy(
                    tracks = listOf(track.copy(clips = track.clips + extra)),
                    duration = 8.seconds,
                ),
            )
        }

        val after = tool.execute(input, ctx())
        assertEquals(false, after.data.cacheHit, "timeline mutation must invalidate the cache")
        assertEquals(2, engine.renderCalls)
    }

    @Test fun forceRenderBypassesCache() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4")
        tool.execute(input, ctx())
        assertEquals(1, engine.renderCalls)

        val forced = tool.execute(input.copy(forceRender = true), ctx())
        assertEquals(false, forced.data.cacheHit)
        assertEquals(2, engine.renderCalls)
    }

    @Test fun changingOutputPathMissesCache() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)
        tool.execute(ExportTool.Input(projectId = pid.value, outputPath = "/tmp/a.mp4"), ctx())
        val second = tool.execute(ExportTool.Input(projectId = pid.value, outputPath = "/tmp/b.mp4"), ctx())
        assertEquals(false, second.data.cacheHit)
        assertEquals(2, engine.renderCalls)

        // After both exports, the cache has both entries.
        val cache = store.get(pid)!!.renderCache
        assertEquals(2, cache.entries.size)
        assertTrue(cache.entries.any { it.outputPath == "/tmp/a.mp4" })
        assertTrue(cache.entries.any { it.outputPath == "/tmp/b.mp4" })
    }
}
