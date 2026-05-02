package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.render.ClipRenderCache
import io.talevia.core.domain.render.ClipRenderCacheEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.project.query.IncrementalPlanRow
import io.talevia.core.tool.builtin.project.query.RenderStaleClipReportRow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cycle 17 exposed `select=render_stale` and `select=incremental_plan`
 * via [ProjectQueryTool], folding M5 #1 ([io.talevia.core.domain.incrementalPlan])
 * + M5 #2 ([io.talevia.core.domain.renderStaleClips]) — both deferred
 * from cycle 8 f8031a70 / cycle 7 commit bodies — into the unified
 * dispatcher. This suite pins the dispatch contract: row shape, total/
 * returned semantics, default engineId, filter-guard rejections.
 *
 * The underlying domain primitives have their own dedicated suites
 * (`RenderStalenessTest`, `IncrementalPlanTest`); this suite only
 * verifies the query layer faithfully wraps them and rejects misuse.
 */
class ProjectQueryRenderStaleAndIncrementalPlanTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun newStore(): FileProjectStore = ProjectStoreTestKit.create()

    private fun videoClip(id: String, asset: String = "a-$id", start: Long = 0): Clip.Video = Clip.Video(
        id = ClipId(id),
        timeRange = TimeRange(start.seconds, 3.seconds),
        sourceRange = TimeRange(0.seconds, 3.seconds),
        assetId = AssetId(asset),
    )

    private suspend fun seedProjectWithClips(
        store: FileProjectStore,
        projectId: ProjectId,
        clips: List<Clip.Video>,
        cache: ClipRenderCache = ClipRenderCache.EMPTY,
    ) {
        val track = Track.Video(id = TrackId("v0"), clips = clips)
        store.upsert(
            "demo",
            Project(
                id = projectId,
                timeline = Timeline(
                    tracks = listOf(track),
                    duration = clips.maxOfOrNull { it.timeRange.start + it.timeRange.duration } ?: 0.seconds,
                ),
                clipRenderCache = cache,
            ),
        )
    }

    private fun decodeRenderStaleRows(out: ProjectQueryTool.Output): List<RenderStaleClipReportRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(RenderStaleClipReportRow.serializer()),
            out.rows,
        )

    private fun decodePlanRows(out: ProjectQueryTool.Output): List<IncrementalPlanRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(IncrementalPlanRow.serializer()),
            out.rows,
        )

    // ---------- select=render_stale ----------

    @Test fun renderStaleColdProjectAllClipsReportedAsCacheMiss() = runTest {
        val store = newStore()
        val pid = ProjectId("p-rs-cold")
        seedProjectWithClips(store, pid, listOf(videoClip("c1"), videoClip("c2", start = 3)))

        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = ProjectQueryTool.SELECT_RENDER_STALE),
            ctx(),
        ).data

        assertEquals(ProjectQueryTool.SELECT_RENDER_STALE, out.select)
        assertEquals(2, out.total, "cold project (empty cache) → every clip render-stale")
        assertEquals(2, out.returned)
        val rows = decodeRenderStaleRows(out)
        assertEquals(setOf("c1", "c2"), rows.map { it.clipId }.toSet())
        // Fingerprints are non-empty (16-char fnv1a64 hex per clipMezzanineFingerprint contract).
        assertTrue(rows.all { it.fingerprint.isNotEmpty() })
    }

    @Test fun renderStaleSeededCacheReportsZero() = runTest {
        // Bootstrap: compute the fingerprints by running the same query against
        // a cache-empty project, then seed those exact fingerprints back into
        // the cache and verify the second query reports zero. This avoids
        // re-deriving the fingerprint formula in the test (and any drift
        // between the test's OutputSpec construction and runRenderStaleQuery's).
        val store = newStore()
        val pid = ProjectId("p-rs-warm")
        seedProjectWithClips(store, pid, listOf(videoClip("c1"), videoClip("c2", start = 3)))

        val firstOut = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = ProjectQueryTool.SELECT_RENDER_STALE),
            ctx(),
        ).data
        assertEquals(2, firstOut.total, "cold project sanity check")
        val fingerprintsByClip = decodeRenderStaleRows(firstOut).associateBy({ it.clipId }, { it.fingerprint })

        // Pre-seed the cache with exactly those fingerprints, then re-query.
        store.mutate(pid) { p ->
            p.copy(
                clipRenderCache = ClipRenderCache(
                    entries = fingerprintsByClip.map { (clipId, fp) ->
                        ClipRenderCacheEntry(
                            fingerprint = fp,
                            mezzaninePath = "/tmp/$clipId.mp4",
                            resolutionWidth = 1920,
                            resolutionHeight = 1080,
                            durationSeconds = 3.0,
                            createdAtEpochMs = 0L,
                        )
                    },
                ),
            )
        }

        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = ProjectQueryTool.SELECT_RENDER_STALE),
            ctx(),
        ).data
        assertEquals(0, out.total, "all fingerprints cached → render-stale report is empty")
        assertTrue(decodeRenderStaleRows(out).isEmpty())
    }

    @Test fun renderStaleEngineIdOverrideShiftsFingerprintToAlwaysMiss() = runTest {
        // engineId is part of the fingerprint, so swapping engines makes
        // every cached fingerprint mis-match → every clip stale on the
        // new engine even when the default-engine cache is full.
        val store = newStore()
        val pid = ProjectId("p-rs-engine")
        seedProjectWithClips(store, pid, listOf(videoClip("c1")))

        // Bootstrap: run query once on the empty cache to harvest the
        // ffmpeg-jvm fingerprint, then pre-seed exactly that fingerprint
        // and assert default-engine query goes to zero.
        val firstOut = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = ProjectQueryTool.SELECT_RENDER_STALE),
            ctx(),
        ).data
        val ffFp = decodeRenderStaleRows(firstOut).single().fingerprint
        store.mutate(pid) { p ->
            p.copy(
                clipRenderCache = ClipRenderCache(
                    entries = listOf(
                        ClipRenderCacheEntry(
                            fingerprint = ffFp,
                            mezzaninePath = "/tmp/m1.mp4",
                            resolutionWidth = 1920,
                            resolutionHeight = 1080,
                            durationSeconds = 3.0,
                            createdAtEpochMs = 0L,
                        ),
                    ),
                ),
            )
        }

        val outDefault = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(projectId = pid.value, select = ProjectQueryTool.SELECT_RENDER_STALE),
            ctx(),
        ).data
        assertEquals(0, outDefault.total, "default engineId=ffmpeg-jvm hits the seeded cache")

        val outOverride = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = ProjectQueryTool.SELECT_RENDER_STALE,
                engineId = "media3-android",
            ),
            ctx(),
        ).data
        assertEquals(1, outOverride.total, "engineId override shifts fingerprint → cache miss")
    }

    // ---------- select=incremental_plan ----------

    @Test fun incrementalPlanEmptyChangedSetReturnsAllZero() = runTest {
        val store = newStore()
        val pid = ProjectId("p-ip-empty")
        seedProjectWithClips(store, pid, listOf(videoClip("c1")))

        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = ProjectQueryTool.SELECT_INCREMENTAL_PLAN,
                sourceNodeIds = emptyList(),
            ),
            ctx(),
        ).data

        assertEquals(ProjectQueryTool.SELECT_INCREMENTAL_PLAN, out.select)
        assertEquals(1, out.total, "incremental_plan always returns a single-row plan")
        assertEquals(1, out.returned)
        val rows = decodePlanRows(out)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertTrue(row.reAigc.isEmpty(), "empty changedSources → reAigc empty")
        assertTrue(row.onlyRender.isEmpty(), "empty changedSources → onlyRender empty")
        assertTrue(row.unchanged.isEmpty(), "empty changedSources → unchanged empty")
        assertEquals(0, row.workCount)
    }

    @Test fun incrementalPlanUnreachedSourceReturnsAllZero() = runTest {
        val store = newStore()
        val pid = ProjectId("p-ip-unreached")
        seedProjectWithClips(store, pid, listOf(videoClip("c1")))

        // Source node id that doesn't exist in the project's source DAG;
        // clipsBoundTo returns empty; plan is all-empty buckets.
        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = ProjectQueryTool.SELECT_INCREMENTAL_PLAN,
                sourceNodeIds = listOf("phantom"),
            ),
            ctx(),
        ).data

        val row = decodePlanRows(out).single()
        assertEquals(0, row.workCount)
    }

    @Test fun incrementalPlanRowShapeIsSingleObject() = runTest {
        // Defensive contract pin: the rows array always has exactly one
        // element for select=incremental_plan, regardless of how many
        // source ids the caller passed. limit/offset don't apply.
        val store = newStore()
        val pid = ProjectId("p-ip-shape")
        seedProjectWithClips(store, pid, listOf(videoClip("c1"), videoClip("c2", start = 3)))

        val out = ProjectQueryTool(store).execute(
            ProjectQueryTool.Input(
                projectId = pid.value,
                select = ProjectQueryTool.SELECT_INCREMENTAL_PLAN,
                sourceNodeIds = listOf("a", "b", "c"),
                limit = 1,
                offset = 99,
            ),
            ctx(),
        ).data

        assertEquals(1, out.total)
        assertEquals(1, out.returned)
        assertEquals(1, decodePlanRows(out).size)
    }

    // ---------- filter-guard rejections ----------

    @Test fun sourceNodeIdsRejectedOutsideIncrementalPlan() = runTest {
        val store = newStore()
        val pid = ProjectId("p-guard-snids")
        seedProjectWithClips(store, pid, listOf(videoClip("c1")))

        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = ProjectQueryTool.SELECT_TRACKS,
                    sourceNodeIds = listOf("mei"),
                ),
                ctx(),
            )
        }
        assertTrue(
            ex.message!!.contains("sourceNodeIds (select=incremental_plan only)"),
            "filter guard names the offending field + valid select; got: ${ex.message}",
        )
    }

    @Test fun engineIdRejectedOutsideRenderStaleAndIncrementalPlan() = runTest {
        val store = newStore()
        val pid = ProjectId("p-guard-eng")
        seedProjectWithClips(store, pid, listOf(videoClip("c1")))

        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = ProjectQueryTool.SELECT_TRACKS,
                    engineId = "ffmpeg-jvm",
                ),
                ctx(),
            )
        }
        assertTrue(
            ex.message!!.contains("engineId (select=render_stale or incremental_plan only)"),
            "filter guard names engineId + valid selects; got: ${ex.message}",
        )
    }

    @Test fun renderStaleAcceptsEngineIdAndRejectsSourceNodeIds() = runTest {
        val store = newStore()
        val pid = ProjectId("p-guard-rs-mix")
        seedProjectWithClips(store, pid, listOf(videoClip("c1")))

        val ex = assertFailsWith<IllegalStateException> {
            ProjectQueryTool(store).execute(
                ProjectQueryTool.Input(
                    projectId = pid.value,
                    select = ProjectQueryTool.SELECT_RENDER_STALE,
                    sourceNodeIds = listOf("mei"),
                ),
                ctx(),
            )
        }
        assertTrue(
            ex.message!!.contains("sourceNodeIds (select=incremental_plan only)"),
            "render_stale accepts engineId but not sourceNodeIds; got: ${ex.message}",
        )
    }
}
