package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.render.RenderCache
import io.talevia.core.domain.render.RenderCacheEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.project.query.LockfileCacheStatsRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Covers `select=lockfile_cache_stats` on [ProjectQueryTool] — the M2
 * criterion 3 "pin 命中率可见" surface. Asserts that the aggregate behaves
 * correctly across the four canonical shapes (empty, all-hits, all-misses,
 * mixed) and that the full `RegisteredTool.dispatch` JSON path exercises the
 * same code with no surprises at the cast boundary.
 */
class ProjectQueryLockfileCacheStatsTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun lockfileEntry(
        hash: String,
        assetId: String,
        providerId: String = "openai",
        modelId: String = "gpt-image-1",
    ): LockfileEntry = LockfileEntry(
        inputHash = hash,
        toolId = "generate_image",
        assetId = AssetId(assetId),
        provenance = GenerationProvenance(
            providerId = providerId,
            modelId = modelId,
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0,
        ),
    )

    private fun videoClip(
        id: String,
        assetId: String,
        bound: Boolean,
        start: Int = 0,
    ): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, (start + 5).seconds),
        sourceRange = TimeRange(0.seconds, 5.seconds),
        assetId = AssetId(assetId),
        sourceBinding = if (bound) setOf(SourceNodeId("char-1")) else emptySet(),
    )

    private suspend fun fixture(
        clips: List<Clip>,
        lockfileEntries: List<LockfileEntry> = emptyList(),
        exportCount: Int = 0,
    ): Pair<FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        val renderEntries = (1..exportCount).map { i ->
            RenderCacheEntry(
                fingerprint = "fp-$i",
                outputPath = "/tmp/out-$i.mp4",
                resolutionWidth = 1920,
                resolutionHeight = 1080,
                durationSeconds = 5.0,
                createdAtEpochMs = i.toLong(),
            )
        }
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(
                    tracks = listOf(Track.Video(id = TrackId("v"), clips = clips)),
                ),
                lockfile = EagerLockfile(entries = lockfileEntries),
                renderCache = RenderCache(entries = renderEntries),
            ),
        )
        return store to pid
    }

    @Test fun emptyCacheAndEmptyTimelineYieldsZeros() = runTest {
        val (store, pid) = fixture(clips = emptyList())
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "lockfile_cache_stats"),
            ctx(),
        ).data

        assertEquals("lockfile_cache_stats", out.select)
        assertEquals(1, out.total)
        val row = out.rows.decodeRowsAs(LockfileCacheStatsRow.serializer()).single()
        assertEquals(0, row.totalExports)
        assertEquals(0, row.cacheHits)
        assertEquals(0, row.cacheMisses)
        // No clips means denom clamped to 1 — ratio is 0.0/1.0 = 0.0, not NaN.
        assertEquals(0.0, row.hitRatio)
        assertTrue(row.perModelBreakdown.isEmpty())
    }

    @Test fun allHitsProducePerfectRatioAndModelBreakdown() = runTest {
        val (store, pid) = fixture(
            clips = listOf(
                videoClip("c1", assetId = "a1", bound = true, start = 0),
                videoClip("c2", assetId = "a2", bound = true, start = 5),
                videoClip("c3", assetId = "a3", bound = true, start = 10),
            ),
            lockfileEntries = listOf(
                lockfileEntry("h1", "a1", providerId = "openai", modelId = "gpt-image-1"),
                lockfileEntry("h2", "a2", providerId = "openai", modelId = "gpt-image-1"),
                lockfileEntry("h3", "a3", providerId = "replicate", modelId = "sdxl"),
            ),
            exportCount = 2,
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "lockfile_cache_stats"),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(LockfileCacheStatsRow.serializer()).single()
        assertEquals(2, row.totalExports)
        assertEquals(3, row.cacheHits)
        assertEquals(0, row.cacheMisses)
        assertEquals(1.0, row.hitRatio)
        // Sorted (providerId, modelId): openai/gpt-image-1 (2 hits), replicate/sdxl (1 hit).
        assertEquals(2, row.perModelBreakdown.size)
        assertEquals("openai", row.perModelBreakdown[0].providerId)
        assertEquals("gpt-image-1", row.perModelBreakdown[0].modelId)
        assertEquals(2, row.perModelBreakdown[0].hits)
        assertEquals("replicate", row.perModelBreakdown[1].providerId)
        assertEquals("sdxl", row.perModelBreakdown[1].modelId)
        assertEquals(1, row.perModelBreakdown[1].hits)
    }

    @Test fun allMissesProduceZeroRatioAndUnknownBucket() = runTest {
        val (store, pid) = fixture(
            clips = listOf(
                // Bound but NO lockfile entry — genuine miss.
                videoClip("c1", assetId = "a1", bound = true, start = 0),
                videoClip("c2", assetId = "a2", bound = true, start = 5),
            ),
            lockfileEntries = emptyList(),
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "lockfile_cache_stats"),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(LockfileCacheStatsRow.serializer()).single()
        assertEquals(0, row.cacheHits)
        assertEquals(2, row.cacheMisses)
        assertEquals(0.0, row.hitRatio)
        assertEquals(1, row.perModelBreakdown.size)
        val unknown = row.perModelBreakdown.single()
        assertEquals("unknown", unknown.providerId)
        assertEquals("unknown", unknown.modelId)
        assertEquals(0, unknown.hits)
        assertEquals(2, unknown.misses)
    }

    @Test fun mixedHitsMissesAndUnboundClipsProduceExactCounts() = runTest {
        val (store, pid) = fixture(
            clips = listOf(
                videoClip("c1", assetId = "a1", bound = true, start = 0), // hit
                videoClip("c2", assetId = "a2", bound = true, start = 5), // miss (no lockfile entry)
                videoClip("c3", assetId = "a3", bound = false, start = 10), // neither — imported
                videoClip("c4", assetId = "a4", bound = true, start = 15), // hit
            ),
            lockfileEntries = listOf(
                lockfileEntry("h1", "a1", providerId = "openai", modelId = "gpt-image-1"),
                lockfileEntry("h4", "a4", providerId = "openai", modelId = "gpt-image-1"),
            ),
            exportCount = 1,
        )
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = "lockfile_cache_stats"),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(LockfileCacheStatsRow.serializer()).single()
        assertEquals(1, row.totalExports)
        assertEquals(2, row.cacheHits)
        assertEquals(1, row.cacheMisses)
        // 2 / (2+1) = 0.6666...
        assertTrue(kotlin.math.abs(row.hitRatio - (2.0 / 3.0)) < 1e-9)
        assertEquals(2, row.perModelBreakdown.size)
        val hitRow = row.perModelBreakdown.first { it.providerId != "unknown" }
        assertEquals("openai", hitRow.providerId)
        assertEquals("gpt-image-1", hitRow.modelId)
        assertEquals(2, hitRow.hits)
        assertEquals(0, hitRow.misses)
        val missRow = row.perModelBreakdown.first { it.providerId == "unknown" }
        assertEquals(1, missRow.misses)
    }

    @Test fun dispatchViaJsonEndToEnd() = runTest {
        val (store, pid) = fixture(
            clips = listOf(videoClip("c1", assetId = "a1", bound = true)),
            lockfileEntries = listOf(lockfileEntry("h1", "a1")),
            exportCount = 0,
        )
        val registry = io.talevia.core.tool.ToolRegistry().apply {
            register(ProjectQueryTool(store))
        }
        val result = registry["project_query"]!!.dispatch(
            buildJsonObject {
                put("projectId", pid.value)
                put("select", "lockfile_cache_stats")
            },
            ctx(),
        )
        // The registered dispatch surfaces the data as the typed Output.
        val data = result.data as ProjectQueryTool.Output
        assertEquals("lockfile_cache_stats", data.select)
        val row = data.rows.decodeRowsAs(LockfileCacheStatsRow.serializer()).single()
        assertEquals(1, row.cacheHits)
        assertEquals(0, row.cacheMisses)
        assertEquals(1.0, row.hitRatio)
    }

    @Test fun rejectsFiltersThatDontApply() = runTest {
        val (store, pid) = fixture(clips = emptyList())
        // toolId is a lockfile_entries-only filter — setting it on
        // lockfile_cache_stats must fail loud, not silently ignore.
        assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = "lockfile_cache_stats",
                    toolId = "generate_image",
                ),
                ctx(),
            )
        }
    }

    @Test fun canonicalSerialisationRoundTrip() {
        // Guard against accidental field reorder / renames by round-tripping
        // a fully-populated row through the canonical Json config.
        val row = LockfileCacheStatsRow(
            projectId = "p",
            totalExports = 2,
            cacheHits = 3,
            cacheMisses = 1,
            hitRatio = 0.75,
            perModelBreakdown = listOf(
                io.talevia.core.tool.builtin.project.query.PerModelBreakdownRow(
                    providerId = "openai",
                    modelId = "gpt-image-1",
                    hits = 3,
                    misses = 0,
                ),
            ),
        )
        val encoded = JsonConfig.default.encodeToString(LockfileCacheStatsRow.serializer(), row)
        val decoded = JsonConfig.default.decodeFromString(LockfileCacheStatsRow.serializer(), encoded)
        assertEquals(row, decoded)
    }
}
