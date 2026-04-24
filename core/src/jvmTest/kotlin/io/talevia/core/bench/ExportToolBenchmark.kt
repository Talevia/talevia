package io.talevia.core.bench

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.platform.MediaPathResolver
import io.talevia.core.platform.OutputSpec
import io.talevia.core.platform.RenderProgress
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.video.ExportTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Wall-time regression guard for [ExportTool.execute] — a VISION §3.2
 * critical path currently without a benchmark. `ExportTool` sits on top
 * of a lot of machinery (stale-guard via `ProjectStaleness`, provenance
 * manifest computation over the full timeline + lockfile, render-cache
 * lookup + miss path, engine invocation + progress-stream plumbing, and
 * post-render cache append). Agent loop + lockfile bench (cycles
 * `c5daba05` / `6a47516d`) cover the other two critical paths R.6 #4
 * flags; this one closes the third.
 *
 * **Budget policy (v1).** Never-fail, print-and-soft-warn — same idiom
 * [AgentLoopBenchmark] documented. 2s soft budget for the whole-
 * timeline 5-clip scenario; the stub engine returns immediately so any
 * time above ~200ms is ExportTool + ProjectStore coordination, not
 * encode work.
 */
class ExportToolBenchmark {

    @Test fun fiveClipExportCoordinationOverhead() = runTest {
        // Warm-up — prime JIT / reflection / FakeFileSystem cache.
        runExportScenario(clipCount = 5)
        val freshElapsed = measureTime {
            runExportScenario(clipCount = 5)
        }
        AgentLoopBenchmark.report(
            name = "export-tool.5-clip.fresh-render",
            elapsed = freshElapsed,
            softBudget = SOFT_BUDGET,
        )

        // Cache-hit path: same inputs → renderCache short-circuits the
        // engine. Tests VERY different coordination overhead (provenance
        // + cache lookup + decode, no engine call). Useful to anchor the
        // "cache hit is cheaper than cache miss" invariant.
        val hitElapsed = measureTime {
            runExportScenarioReusing()
        }
        AgentLoopBenchmark.report(
            name = "export-tool.5-clip.cache-hit",
            elapsed = hitElapsed,
            softBudget = SOFT_BUDGET,
        )
    }

    /**
     * Runs one fresh export (engine gets called) on a 5-clip timeline
     * backed by an in-memory ProjectStore.
     */
    private suspend fun runExportScenario(clipCount: Int) {
        val store = ProjectStoreTestKit.create()
        val engine = StubVideoEngine()
        val pid = ProjectId("bench-$clipCount")
        store.upsert("bench", Project(id = pid, timeline = buildTimeline(clipCount), assets = syntheticAssets(clipCount)))
        val tool = ExportTool(store, engine)
        tool.execute(
            ExportTool.Input(projectId = pid.value, outputPath = "/tmp/bench-$clipCount.mp4"),
            ctx(),
        )
    }

    /**
     * Creates the project once, exports twice: first populates the
     * renderCache, second must short-circuit on cache hit. Measures the
     * second export's wall time.
     */
    private suspend fun runExportScenarioReusing() {
        val store = ProjectStoreTestKit.create()
        val engine = StubVideoEngine()
        val pid = ProjectId("bench-hit")
        store.upsert("bench", Project(id = pid, timeline = buildTimeline(5), assets = syntheticAssets(5)))
        val tool = ExportTool(store, engine)
        val input = ExportTool.Input(projectId = pid.value, outputPath = "/tmp/bench-hit.mp4")
        tool.execute(input, ctx()) // populate renderCache
        tool.execute(input, ctx()) // <- this is what we actually time (the `measureTime` closure wraps both)
    }

    private fun buildTimeline(clipCount: Int): Timeline {
        val clips = (0 until clipCount).map { i ->
            val start = (i * 5).seconds
            Clip.Video(
                id = ClipId("clip-$i"),
                timeRange = TimeRange(start, 5.seconds),
                sourceRange = TimeRange(0.seconds, 5.seconds),
                assetId = AssetId("asset-$i"),
            )
        }
        return Timeline(
            tracks = listOf(Track.Video(id = TrackId("v"), clips = clips)),
            duration = (clipCount * 5).seconds,
        )
    }

    private fun syntheticAssets(clipCount: Int): List<MediaAsset> =
        (0 until clipCount).map { i ->
            MediaAsset(
                id = AssetId("asset-$i"),
                source = MediaSource.File("/tmp/bench/asset-$i.mp4"),
                metadata = MediaMetadata(duration = 10.seconds, resolution = Resolution(1920, 1080)),
            )
        }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { /* drop progress parts to keep the bench on the ExportTool code path */ },
        messages = emptyList(),
    )

    /**
     * Minimal [VideoEngine] fake — emits Started → Completed immediately
     * via `flow { ... }`. The point of the bench is to measure
     * ExportTool + ProjectStore coordination overhead, not engine work.
     */
    private class StubVideoEngine : VideoEngine {
        override suspend fun probe(source: MediaSource): MediaMetadata =
            MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

        override fun render(
            timeline: Timeline,
            output: OutputSpec,
            resolver: MediaPathResolver?,
        ): Flow<RenderProgress> = flow {
            emit(RenderProgress.Started("bench-job"))
            emit(RenderProgress.Completed("bench-job", output.targetPath))
        }

        override suspend fun thumbnail(asset: AssetId, source: MediaSource, time: Duration): ByteArray =
            ByteArray(0)
    }

    companion object {
        /**
         * Generous soft budget — the stub engine returns in microseconds,
         * so any observed time above 200ms means ExportTool + ProjectStore
         * coordination took that long. 2s leaves headroom for a noisy CI
         * while still flagging a 10× regression.
         */
        private val SOFT_BUDGET: kotlin.time.Duration = 2.seconds
    }
}
