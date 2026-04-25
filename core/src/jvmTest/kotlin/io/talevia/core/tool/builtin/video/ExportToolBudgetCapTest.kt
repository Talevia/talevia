package io.talevia.core.tool.builtin.video

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRequest
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

/**
 * Coverage for [ExportTool]'s spend-cap fail-fast guard
 * (`export-tool-cost-cap-fail-fast`). The guard runs at export entry,
 * before any engine call, comparing `Σ perClipCostCents` to
 * `ctx.spendCapCents` and raising an `aigc.budget` permission ASK
 * when the timeline AIGC content meets-or-exceeds the cap.
 */
class ExportToolBudgetCapTest {

    private class TrackingEngine : VideoEngine {
        var renderCalls: Int = 0
            private set

        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

        override fun render(
            timeline: Timeline,
            output: OutputSpec,
            resolver: io.talevia.core.platform.MediaPathResolver?,
        ): Flow<RenderProgress> = flow {
            renderCalls += 1
            emit(RenderProgress.Started("job"))
            emit(RenderProgress.Completed("job", output.targetPath))
        }

        override suspend fun thumbnail(
            asset: AssetId,
            source: MediaSource,
            time: Duration,
        ): ByteArray = ByteArray(0)
    }

    private fun ctx(
        cap: Long?,
        askPermission: suspend (PermissionRequest) -> PermissionDecision = { PermissionDecision.Once },
    ): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = askPermission,
        emitPart = { },
        messages = emptyList(),
        spendCapCents = cap,
    )

    private suspend fun fixtureWithPricedTimeline(
        c1Cents: Long? = 6L,
        c2Cents: Long? = 4L,
    ): Triple<FileProjectStore, TrackingEngine, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val engine = TrackingEngine()
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
                        Clip.Video(
                            id = ClipId("c2"),
                            timeRange = TimeRange(5.seconds, 5.seconds),
                            sourceRange = TimeRange(0.seconds, 5.seconds),
                            assetId = AssetId("a2"),
                        ),
                    ),
                ),
            ),
            duration = 10.seconds,
        )
        store.upsert("demo", Project(id = projectId, timeline = timeline))
        store.mutate(projectId) { p ->
            var lf = p.lockfile
            if (c1Cents != null) {
                lf = lf.append(
                    LockfileEntry(
                        inputHash = "h1",
                        toolId = "generate_image",
                        assetId = AssetId("a1"),
                        provenance = GenerationProvenance(
                            providerId = "openai",
                            modelId = "gpt-image-1",
                            modelVersion = null,
                            seed = 1L,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0L,
                        ),
                        costCents = c1Cents,
                    ),
                )
            }
            if (c2Cents != null) {
                lf = lf.append(
                    LockfileEntry(
                        inputHash = "h2",
                        toolId = "generate_image",
                        assetId = AssetId("a2"),
                        provenance = GenerationProvenance(
                            providerId = "openai",
                            modelId = "gpt-image-1",
                            modelVersion = null,
                            seed = 2L,
                            parameters = JsonObject(emptyMap()),
                            createdAtEpochMs = 0L,
                        ),
                        costCents = c2Cents,
                    ),
                )
            }
            p.copy(lockfile = lf)
        }
        return Triple(store, engine, projectId)
    }

    @Test fun nullCapNeverAsks() = runTest {
        val (store, engine, pid) = fixtureWithPricedTimeline(c1Cents = 9999L, c2Cents = 9999L)
        var askCount = 0
        val tool = ExportTool(store, engine)
        tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/null-cap.mp4"),
            ctx(cap = null, askPermission = { askCount++; PermissionDecision.Reject }),
        )
        assertEquals(0, askCount, "null cap must never trigger a permission ask")
        assertEquals(1, engine.renderCalls, "render proceeds when cap is unset")
    }

    @Test fun timelineWithoutAigcDoesNotAsk() = runTest {
        // No lockfile entries → totalCost = 0 → no guard.
        val (store, engine, pid) = fixtureWithPricedTimeline(c1Cents = null, c2Cents = null)
        var askCount = 0
        val tool = ExportTool(store, engine)
        tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/no-aigc.mp4"),
            ctx(cap = 100L, askPermission = { askCount++; PermissionDecision.Reject }),
        )
        assertEquals(0, askCount, "timeline with no priced clips must not trigger guard")
        assertEquals(1, engine.renderCalls)
    }

    @Test fun timelineUnderCapDoesNotAsk() = runTest {
        // Total = 10¢, cap = 100¢ → guard skipped.
        val (store, engine, pid) = fixtureWithPricedTimeline(c1Cents = 6L, c2Cents = 4L)
        var askCount = 0
        val tool = ExportTool(store, engine)
        tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/under-cap.mp4"),
            ctx(cap = 100L, askPermission = { askCount++; PermissionDecision.Reject }),
        )
        assertEquals(0, askCount)
        assertEquals(1, engine.renderCalls)
    }

    @Test fun timelineAtCapAsksAndRejectThrowsBeforeRender() = runTest {
        // Boundary: total = 10¢, cap = 10¢ → guard fires.
        val (store, engine, pid) = fixtureWithPricedTimeline(c1Cents = 6L, c2Cents = 4L)
        var askedRequest: PermissionRequest? = null
        val tool = ExportTool(store, engine)
        val err = assertFailsWith<IllegalStateException> {
            tool.execute(
                ExportTool.Input(projectId = pid.value, outputPath = "/tmp/at-cap.mp4"),
                ctx(cap = 10L, askPermission = { req ->
                    askedRequest = req
                    PermissionDecision.Reject
                }),
            )
        }
        // Permission shape — reuses aigc.budget so existing rules apply,
        // but pattern distinguishes the export surface for diagnostics.
        assertEquals("aigc.budget", askedRequest?.permission)
        assertEquals("export-exceeded", askedRequest?.pattern)
        assertEquals("export", askedRequest?.metadata?.get("toolId"))
        assertEquals("10", askedRequest?.metadata?.get("capCents"))
        assertEquals("10", askedRequest?.metadata?.get("currentCents"))
        assertEquals("2", askedRequest?.metadata?.get("pricedClipCount"))
        // Render NEVER kicked off — fail-fast.
        assertEquals(0, engine.renderCalls, "render must not start when guard rejects")
        assertTrue(
            "spend cap" in err.message.orEmpty(),
            "error must mention spend cap; got: ${err.message}",
        )
    }

    @Test fun timelineOverCapWithOnceProceedsToRender() = runTest {
        // Total = 10¢, cap = 5¢ → ASK; user says Once → render proceeds.
        val (store, engine, pid) = fixtureWithPricedTimeline(c1Cents = 6L, c2Cents = 4L)
        var askCount = 0
        val tool = ExportTool(store, engine)
        tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/over-once.mp4"),
            ctx(cap = 5L, askPermission = { askCount++; PermissionDecision.Once }),
        )
        assertEquals(1, askCount)
        assertEquals(1, engine.renderCalls, "Once decision must let the render proceed")
    }

    @Test fun mixedPricedUnpricedSumsOnlyPriced() = runTest {
        // c1 priced at 8¢; c2 unpriced (null cents). Total = 8¢.
        // cap = 5¢ → ASK. pricedClipCount = 1.
        val (store, engine, pid) = fixtureWithPricedTimeline(c1Cents = 8L, c2Cents = null)
        var askedRequest: PermissionRequest? = null
        val tool = ExportTool(store, engine)
        tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/mixed.mp4"),
            ctx(cap = 5L, askPermission = { req ->
                askedRequest = req
                PermissionDecision.Once
            }),
        )
        assertEquals("8", askedRequest?.metadata?.get("currentCents"))
        assertEquals("1", askedRequest?.metadata?.get("pricedClipCount"))
        assertEquals(1, engine.renderCalls)
    }
}
