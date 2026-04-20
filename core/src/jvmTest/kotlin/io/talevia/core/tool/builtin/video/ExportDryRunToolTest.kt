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
import io.talevia.core.domain.FrameRate
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.render.RenderCacheEntry
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ExportDryRunToolTest {

    private fun ctx() = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun newFixture(): Pair<SqlDelightProjectStore, ProjectId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
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
        return store to projectId
    }

    private fun fakeProvenance(): GenerationProvenance = GenerationProvenance(
        providerId = "fake",
        modelId = "fake",
        modelVersion = null,
        seed = 1L,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 0L,
    )

    @Test fun happyPathNoStaleNoCache() = runTest {
        val (store, pid) = newFixture()
        val tool = ExportDryRunTool(store)

        val out = tool.execute(
            ExportDryRunTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        ).data

        assertEquals(false, out.wouldCacheHit)
        assertEquals(false, out.wouldBlockOnStale)
        assertNull(out.cachedOutputPath)
        assertEquals(0, out.staleClipCount)
        assertTrue(out.staleClipIds.isEmpty())
        assertEquals(1920, out.resolutionWidth)
        assertEquals(1080, out.resolutionHeight)
        assertEquals(30, out.frameRate)
        assertEquals(5.0, out.durationSeconds)
        assertEquals(1, out.clipCount)
        assertEquals(1, out.trackCount)
        assertEquals("/tmp/out.mp4", out.outputPath)
        assertTrue(out.fingerprint.isNotEmpty())
    }

    @Test fun staleClipPresentBlocks() = runTest {
        val (store, pid) = newFixture()

        // Bind the clip to a character_ref and snapshot the original hash into a lockfile.
        store.mutate(pid) { p ->
            val track = p.timeline.tracks.first() as Track.Video
            val bound = (track.clips.first() as Clip.Video).copy(sourceBinding = setOf(SourceNodeId("mei")))
            p.copy(timeline = p.timeline.copy(tracks = listOf(track.copy(clips = listOf(bound)))))
        }
        store.mutateSource(pid) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }
        val originalHash = store.get(pid)!!.source.byId[SourceNodeId("mei")]!!.contentHash
        store.mutate(pid) { p ->
            p.copy(
                lockfile = p.lockfile.append(
                    LockfileEntry(
                        inputHash = "h1",
                        toolId = "generate_image",
                        assetId = AssetId("a1"),
                        provenance = fakeProvenance(),
                        sourceBinding = setOf(SourceNodeId("mei")),
                        sourceContentHashes = mapOf(SourceNodeId("mei") to originalHash),
                    ),
                ),
            )
        }
        // Drift the character → clip becomes stale.
        store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) { node ->
                node.copy(
                    body = JsonConfig.default.encodeToJsonElement(
                        CharacterRefBody.serializer(),
                        CharacterRefBody(name = "Mei", visualDescription = "red"),
                    ),
                )
            }
        }

        val tool = ExportDryRunTool(store)
        val out = tool.execute(
            ExportDryRunTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        ).data

        assertEquals(1, out.staleClipCount)
        assertEquals(listOf("c1"), out.staleClipIds)
        assertEquals(true, out.wouldBlockOnStale)
        // Cache still empty — stale detection is orthogonal to cache-hit reporting.
        assertEquals(false, out.wouldCacheHit)
    }

    @Test fun prePopulatedRenderCacheHit() = runTest {
        val (store, pid) = newFixture()

        // First do a dry-run to discover the fingerprint ExportTool would compute
        // for /tmp/out.mp4 under the default spec.
        val tool = ExportDryRunTool(store)
        val preview = tool.execute(
            ExportDryRunTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        ).data
        val fingerprint = preview.fingerprint

        // Prime the render cache with an entry matching that fingerprint + path.
        store.mutate(pid) { p ->
            p.copy(
                renderCache = p.renderCache.append(
                    RenderCacheEntry(
                        fingerprint = fingerprint,
                        outputPath = "/tmp/out.mp4",
                        resolutionWidth = 1920,
                        resolutionHeight = 1080,
                        durationSeconds = 5.0,
                        createdAtEpochMs = 0L,
                    ),
                ),
            )
        }

        val withPath = tool.execute(
            ExportDryRunTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        ).data
        assertEquals(true, withPath.wouldCacheHit, "same path + fingerprint must predict a cache hit")
        assertEquals("/tmp/out.mp4", withPath.cachedOutputPath)

        // Omitting outputPath still finds the cached entry by fingerprint search.
        val noPath = tool.execute(ExportDryRunTool.Input(projectId = pid.value), ctx()).data
        assertEquals(true, noPath.wouldCacheHit, "fingerprint-only lookup must still hit")
        assertEquals("/tmp/out.mp4", noPath.cachedOutputPath)

        // Asking for a different path misses — ExportTool requires path equality on hit.
        val otherPath = tool.execute(
            ExportDryRunTool.Input(projectId = pid.value, outputPath = "/tmp/other.mp4"),
            ctx(),
        ).data
        assertEquals(false, otherPath.wouldCacheHit)
        assertNull(otherPath.cachedOutputPath)
    }

    @Test fun explicitWidthHeightOverrideApplied() = runTest {
        val (store, pid) = newFixture()
        val tool = ExportDryRunTool(store)
        val out = tool.execute(
            ExportDryRunTool.Input(
                projectId = pid.value,
                outputPath = "/tmp/out.mp4",
                width = 1280,
                height = 720,
                frameRate = 24,
            ),
            ctx(),
        ).data
        assertEquals(1280, out.resolutionWidth)
        assertEquals(720, out.resolutionHeight)
        assertEquals(24, out.frameRate)
    }

    @Test fun timelineProfileAppliedWhenNoExplicitOverride() = runTest {
        // When the project's timeline is configured with a non-default output profile
        // (resolution + frame rate), dry-run surfaces that spec without any overrides.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p-profile")
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
                    ),
                ),
            ),
            duration = 3.seconds,
            frameRate = FrameRate.FPS_60,
            resolution = Resolution(3840, 2160),
        )
        store.upsert("profile", Project(id = pid, timeline = timeline))

        val tool = ExportDryRunTool(store)
        val out = tool.execute(
            ExportDryRunTool.Input(projectId = pid.value, outputPath = "/tmp/out.mp4"),
            ctx(),
        ).data
        assertEquals(3840, out.resolutionWidth)
        assertEquals(2160, out.resolutionHeight)
        assertEquals(60, out.frameRate)
    }

    @Test fun dryRunDoesNotMutateProjectState() = runTest {
        val (store, pid) = newFixture()
        val json = JsonConfig.default
        val before = json.encodeToString(Project.serializer(), store.get(pid)!!)

        val tool = ExportDryRunTool(store)
        // Run twice under a mix of inputs to stress any latent write path.
        tool.execute(ExportDryRunTool.Input(projectId = pid.value, outputPath = "/tmp/a.mp4"), ctx())
        tool.execute(ExportDryRunTool.Input(projectId = pid.value, width = 640, height = 360), ctx())
        tool.execute(ExportDryRunTool.Input(projectId = pid.value), ctx())

        val after = json.encodeToString(Project.serializer(), store.get(pid)!!)
        assertEquals(before, after, "dry-run must not mutate the project")
    }

    @Test fun missingProjectThrows() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val tool = ExportDryRunTool(store)

        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(ExportDryRunTool.Input(projectId = "nope", outputPath = "/tmp/x.mp4"), ctx())
        }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("nope"), "error must name the missing project id: ${ex.message}")
    }
}
