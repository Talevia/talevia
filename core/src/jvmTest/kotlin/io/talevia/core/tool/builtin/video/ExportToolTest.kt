package io.talevia.core.tool.builtin.video

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
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
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.render.TransitionFades
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test fun refusesToExportWhenAigcClipIsStale() = runTest {
        val (store, engine, pid) = newFixture()
        val tool = ExportTool(store, engine)

        // Bind the seed clip to a character_ref and drop a matching lockfile entry
        // snapshotting the original hash.
        store.mutate(pid) { p ->
            val track = p.timeline.tracks.first() as Track.Video
            val bound = (track.clips.first() as Clip.Video).copy(sourceBinding = setOf(SourceNodeId("mei")))
            p.copy(
                timeline = p.timeline.copy(tracks = listOf(track.copy(clips = listOf(bound)))),
            )
        }
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }
        val originalHash = store.get(pid)!!.source.byId[SourceNodeId("mei")]!!.contentHash
        store.mutate(pid) { p ->
            p.copy(
                lockfile = p.lockfile.append(
                    LockfileEntry(
                        inputHash = "h1",
                        toolId = "generate_image",
                        assetId = AssetId("a1"),
                        provenance = GenerationProvenance(
                            providerId = "fake",
                            modelId = "fake",
                            modelVersion = null,
                            seed = 1L,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0L,
                        ),
                        sourceBinding = setOf(SourceNodeId("mei")),
                        sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
                    ),
                ),
            )
        }

        // User edits the character → clip becomes stale.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red hair"),
                    ),
                )
            }
        }

        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4")
        val ex = assertFailsWith<IllegalStateException> { tool.execute(input, ctx()) }
        assertTrue(ex.message!!.contains("stale"), "error must mention stale clips: ${ex.message}")
        assertTrue(ex.message!!.contains("c1"), "error must name the stale clip id")
        assertEquals(0, engine.renderCalls, "engine must not be invoked when the stale-guard refuses")

        // allowStale=true lets the render through and surfaces the stale ids on output.
        val forced = tool.execute(input.copy(allowStale = true), ctx())
        assertEquals(1, engine.renderCalls)
        assertEquals(listOf("c1"), forced.data.staleClipsIncluded)
    }

    /**
     * Per-clip engine that counts how many clip renders / concat calls occurred.
     * Acts as a drop-in VideoEngine with `supportsPerClipCache = true` so
     * ExportTool's per-clip branch executes. Mezzanine writes are fake (empty
     * file touch + always present) — this test lives in core and mustn't depend
     * on ffmpeg binaries.
     */
    private class FakePerClipEngine : VideoEngine {
        var wholeTimelineCalls: Int = 0
            private set
        var renderClipCalls: Int = 0
            private set
        var concatCalls: Int = 0
            private set
        val rendered: MutableList<Pair<ClipId, String>> = mutableListOf()
        val presentPaths: MutableSet<String> = mutableSetOf()

        override val supportsPerClipCache: Boolean = true

        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

        override fun render(timeline: Timeline, output: OutputSpec): Flow<RenderProgress> = flow {
            wholeTimelineCalls += 1
            emit(RenderProgress.Started("job"))
            emit(RenderProgress.Completed("job", output.targetPath))
        }

        override suspend fun mezzaninePresent(path: String): Boolean = path in presentPaths

        override suspend fun renderClip(
            clip: Clip.Video,
            fades: TransitionFades?,
            output: OutputSpec,
            mezzaninePath: String,
        ) {
            renderClipCalls += 1
            rendered += (clip.id to mezzaninePath)
            presentPaths += mezzaninePath
        }

        override suspend fun concatMezzanines(
            mezzaninePaths: List<String>,
            subtitles: List<Clip.Text>,
            output: OutputSpec,
        ) {
            concatCalls += 1
        }

        override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray = ByteArray(0)
    }

    @Test fun perClipEngineRendersEveryClipOnFirstExport() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val engine = FakePerClipEngine()
        val projectId = ProjectId("p-perclip")
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 3.seconds),
                            sourceRange = TimeRange(0.seconds, 3.seconds),
                            assetId = AssetId("a1"),
                        ),
                        Clip.Video(
                            id = ClipId("c2"),
                            timeRange = TimeRange(3.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a2"),
                        ),
                    ),
                ),
            ),
            duration = 5.seconds,
        )
        store.upsert("perclip", Project(id = projectId, timeline = timeline))

        val tool = ExportTool(store, engine)
        val first = tool.execute(
            ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        )
        assertEquals(0, engine.wholeTimelineCalls, "per-clip path must not call whole-timeline render")
        assertEquals(2, engine.renderClipCalls, "every clip should render on first export")
        assertEquals(1, engine.concatCalls, "concat runs once per export")
        assertEquals(0, first.data.perClipCacheHits)
        assertEquals(2, first.data.perClipCacheMisses)

        // Cache entries were persisted.
        val clipCache = store.get(projectId)!!.clipRenderCache
        assertEquals(2, clipCache.entries.size)
    }

    @Test fun perClipEngineReusesCachedMezzanineOnIdenticalRerun() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val engine = FakePerClipEngine()
        val projectId = ProjectId("p-perclip-rerun")
        val timeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 3.seconds),
                            sourceRange = TimeRange(0.seconds, 3.seconds),
                            assetId = AssetId("a1"),
                        ),
                        Clip.Video(
                            id = ClipId("c2"),
                            timeRange = TimeRange(3.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a2"),
                        ),
                    ),
                ),
            ),
            duration = 5.seconds,
        )
        store.upsert("perclip", Project(id = projectId, timeline = timeline))
        val tool = ExportTool(store, engine)

        tool.execute(ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4"), ctx())
        assertEquals(2, engine.renderClipCalls)

        // Second export with forceRender (full-timeline cache bypass) but identical
        // clip shape → all mezzanines reused from ClipRenderCache.
        val second = tool.execute(
            ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4", forceRender = true),
            ctx(),
        )
        assertEquals(2, engine.renderClipCalls, "per-clip cache hits — no new renderClip calls")
        assertEquals(2, engine.concatCalls, "concat still runs once per export")
        assertEquals(2, second.data.perClipCacheHits)
        assertEquals(0, second.data.perClipCacheMisses)
    }

    @Test fun perClipEngineReRendersOnlyTheStalyClip() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val engine = FakePerClipEngine()
        val projectId = ProjectId("p-perclip-partial")
        val baseTimeline = Timeline(
            tracks = listOf(
                Track.Video(
                    id = TrackId("v"),
                    clips = listOf(
                        Clip.Video(
                            id = ClipId("c1"),
                            timeRange = TimeRange(0.seconds, 3.seconds),
                            sourceRange = TimeRange(0.seconds, 3.seconds),
                            assetId = AssetId("a1"),
                        ),
                        Clip.Video(
                            id = ClipId("c2"),
                            timeRange = TimeRange(3.seconds, 2.seconds),
                            sourceRange = TimeRange(0.seconds, 2.seconds),
                            assetId = AssetId("a2"),
                        ),
                    ),
                ),
            ),
            duration = 5.seconds,
        )
        store.upsert("perclip", Project(id = projectId, timeline = baseTimeline))
        val tool = ExportTool(store, engine)

        tool.execute(ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4"), ctx())
        assertEquals(2, engine.renderClipCalls)

        // Edit clip c2 only (different assetId → fingerprint shifts).
        store.mutate(projectId) { p ->
            val track = p.timeline.tracks.first() as Track.Video
            val newClips = track.clips.map { c ->
                if ((c as Clip.Video).id == ClipId("c2")) c.copy(assetId = AssetId("a2-new")) else c
            }
            p.copy(
                timeline = p.timeline.copy(
                    tracks = listOf(track.copy(clips = newClips)),
                ),
            )
        }

        val after = tool.execute(
            ExportTool.Input(projectId = projectId.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        )
        assertEquals(3, engine.renderClipCalls, "only c2 should re-render; c1 hits cache")
        assertEquals(1, after.data.perClipCacheHits)
        assertEquals(1, after.data.perClipCacheMisses)
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
