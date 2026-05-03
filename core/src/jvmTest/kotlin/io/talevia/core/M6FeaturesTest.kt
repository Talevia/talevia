package io.talevia.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.OutputProfile
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.video.AddSubtitlesTool
import io.talevia.core.tool.builtin.video.ClipActionTool
import io.talevia.core.tool.builtin.video.FilterActionTool
import io.talevia.core.tool.builtin.video.ImportMediaTool
import io.talevia.core.tool.builtin.video.TimelineActionTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class M6FeaturesTest {

    @Test
    fun applyFilterAddsFilterToVideoClip() = runTest {
        val (store, projects, registry, ctx, projectId) = newWiring()
        // Register a fake asset directly so clip_action(action=add) works without ffmpeg.
        val fakeAsset = io.talevia.core.domain.MediaAsset(
            id = AssetId("fake-x"),
            source = io.talevia.core.domain.MediaSource.File("/tmp/x.mp4"),
            metadata = io.talevia.core.domain.MediaMetadata(duration = 10.seconds),
        )
        projects.mutate(projectId) { it.copy(assets = it.assets + fakeAsset) }
        val r = ToolRegistry().apply {
            register(ClipActionTool(projects))
            register(FilterActionTool(projects))
        }

        val addResp = r["clip_action"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                put("action", "add")
                putJsonArray("addItems") {
                    addJsonObject {
                        put("assetId", fakeAsset.id.value)
                    }
                }
            },
            ctx,
        )
        val clipId = (addResp.data as ClipActionTool.Output).added.single().clipId

        r["filter_action"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                put("action", "apply")
                putJsonArray("clipIds") { add(clipId) }
                put("filterName", "blur")
                put("params", buildJsonObject { put("radius", 4.0) })
            },
            ctx,
        )

        val refreshed = projects.get(projectId)!!
        val clip = refreshed.timeline.tracks.flatMap { it.clips }.first { it.id.value == clipId } as Clip.Video
        assertEquals(1, clip.filters.size)
        assertEquals("blur", clip.filters.single().name)
    }

    @Test
    fun addSubtitleCreatesSubtitleTrackAndClip() = runTest {
        val (store, projects, _, ctx, projectId) = newWiring()
        val r = ToolRegistry().apply { register(AddSubtitlesTool(projects)) }
        r["add_subtitles"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                putJsonArray("subtitles") {
                    add(
                        buildJsonObject {
                            put("text", "hello")
                            put("timelineStartSeconds", 1.0)
                            put("durationSeconds", 2.5)
                        },
                    )
                }
            },
            ctx,
        )
        val refreshed = projects.get(projectId)!!
        val subtitle = refreshed.timeline.tracks.filterIsInstance<Track.Subtitle>().single()
        val clip = subtitle.clips.single() as Clip.Text
        assertEquals("hello", clip.text)
        assertEquals(1.0, clip.timeRange.start.inWholeMilliseconds / 1000.0, 0.001)
        assertEquals(2.5, clip.timeRange.duration.inWholeMilliseconds / 1000.0, 0.001)
    }

    @Test
    fun addTransitionInsertsClipOnEffectTrack() = runTest {
        val (_, projects, _, ctx, projectId) = newWiring()
        // Two adjacent video clips on a video track.
        val v1 = Clip.Video(
            id = ClipId("v1"),
            timeRange = io.talevia.core.domain.TimeRange(Duration.ZERO, 5.seconds),
            sourceRange = io.talevia.core.domain.TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("/tmp/a.mp4"),
        )
        val v2 = Clip.Video(
            id = ClipId("v2"),
            timeRange = io.talevia.core.domain.TimeRange(5.seconds, 5.seconds),
            sourceRange = io.talevia.core.domain.TimeRange(Duration.ZERO, 5.seconds),
            assetId = AssetId("/tmp/b.mp4"),
        )
        projects.upsert("seeded", Project(
            id = projectId,
            timeline = Timeline(tracks = listOf(Track.Video(TrackId("vtrack"), listOf(v1, v2))), duration = 10.seconds),
        ))

        val r = ToolRegistry().apply { register(TimelineActionTool(projects)) }
        r["timeline_action"]!!.dispatch(
            buildJsonObject {
                put("projectId", projectId.value)
                put("action", "add_transition")
                putJsonArray("transitionItems") {
                    addJsonObject {
                        put("fromClipId", "v1")
                        put("toClipId", "v2")
                        put("transitionName", "fade")
                        put("durationSeconds", 0.5)
                    }
                }
            },
            ctx,
        )

        val refreshed = projects.get(projectId)!!
        val effect = refreshed.timeline.tracks.filterIsInstance<Track.Effect>().single()
        val transition = effect.clips.single() as Clip.Video
        assertEquals("transition:fade", transition.assetId.value)
        assertEquals("fade", transition.filters.single().name)
    }

    @Test
    fun forkDuplicatesMessagesWithFreshIds() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)

        val now = Clock.System.now()
        val parentId = SessionId("parent")
        store.createSession(Session(parentId, ProjectId("p"), title = "p", createdAt = now, updatedAt = now))
        val um = Message.User(
            id = MessageId("um"),
            sessionId = parentId,
            createdAt = now,
            agent = "default",
            model = ModelRef("anthropic", "claude-opus-4-7"),
        )
        store.appendMessage(um)
        store.upsertPart(Part.Text(PartId("up"), um.id, parentId, now, text = "hello"))

        val branchId = store.fork(parentId, newTitle = "branch-1")

        val branch = store.getSession(branchId)
        assertNotNull(branch)
        assertEquals(parentId, branch!!.parentId)

        val branchMessages = store.listMessagesWithParts(branchId)
        assertEquals(1, branchMessages.size)
        val branchMsg = branchMessages.single()
        assertTrue(branchMsg.message.id != um.id, "branch message should have a fresh id")
        assertEquals(branchId, branchMsg.message.sessionId, "branch message should belong to branch session")
        val branchPart = branchMsg.parts.single() as Part.Text
        assertEquals("hello", branchPart.text)
        assertTrue(branchPart.id != PartId("up"), "branch part should have a fresh id")

        // Parent untouched.
        val parentMessages = store.listMessagesWithParts(parentId)
        assertEquals(1, parentMessages.size)
        assertEquals("hello", (parentMessages.single().parts.single() as Part.Text).text)

        driver.close()
    }

    private data class Wiring(
        val sessions: SqlDelightSessionStore,
        val projects: FileProjectStore,
        val registry: ToolRegistry,
        val ctx: ToolContext,
        val projectId: ProjectId,
    )

    private suspend fun newWiring(): Wiring {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = ProjectStoreTestKit.create()
        val engine = ProbeOnlyEngine()
        val perms = AllowAllPermissionService()
        val projectId = ProjectId("p-${Clock.System.now().toEpochMilliseconds()}")
        projects.upsert("test", Project(id = projectId, timeline = Timeline(), outputProfile = OutputProfile.DEFAULT_1080P))
        val registry = ToolRegistry().apply {
            register(ImportMediaTool(engine, projects))
            register(ClipActionTool(projects))
            register(FilterActionTool(projects))
            register(AddSubtitlesTool(projects))
            register(TimelineActionTool(projects))
        }
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { perms.check(emptyList(), it) },
            emitPart = { /* swallow */ },
            messages = emptyList(),
        )
        return Wiring(sessions, projects, registry, ctx, projectId)
    }

    /** Engine stub for tests that need probe but no rendering. */
    private class ProbeOnlyEngine : VideoEngine {
        override suspend fun probe(source: io.talevia.core.domain.MediaSource) = io.talevia.core.domain.MediaMetadata(duration = 10.seconds)
        override fun render(timeline: Timeline, output: OutputSpec, resolver: io.talevia.core.platform.MediaPathResolver?): Flow<RenderProgress> = flowOf(
            RenderProgress.Failed("no-op", "ProbeOnlyEngine cannot render"),
        )
        override suspend fun thumbnail(asset: AssetId, source: io.talevia.core.domain.MediaSource, time: Duration): ByteArray = ByteArray(0)
    }
}
