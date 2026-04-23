package io.talevia.benchmark

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
import io.talevia.core.domain.RecentsRegistry
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
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.video.ExportTool
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.openjdk.jmh.annotations.Level
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Wall-time baseline for [ExportTool.execute] driving a 10-clip single-track
 * timeline through the full orchestration path — project decode, timeline
 * hash compute, stale-guard, render-cache lookup, engine dispatch, cache
 * store, result assembly.
 *
 * Engine is stubbed with [InstantVideoEngine] (returns `Started`/`Completed`
 * immediately) on purpose — the bullet's concern is "a refactor that makes
 * whole-timeline render O(N²) in clip count sneaks in unnoticed", which
 * shows up as orchestration-cost drift, not ffmpeg-kernel drift. Real
 * ffmpeg numbers would dominate and mask the refactor signal. Downstream
 * bullets (e.g. `export-incremental-render`'s lock-in) can add an
 * FFmpeg-backed variant if they need end-to-end timing; the stub variant
 * is the guard today.
 *
 * Storage is [FileProjectStore] over an Okio [FakeFileSystem] so the
 * benchmark catches regressions in the bundle I/O path (`talevia.json`
 * encode/decode, lockfile / ClipRenderCache write, render-cache write)
 * without requiring real filesystem access.
 *
 * `@Setup(Level.Invocation)` rebuilds storage + project + tool per
 * measured call. The setup cost (10-clip Timeline construction +
 * Project upsert + empty session creation) sits outside the timed
 * region so the baseline captures orchestration work.
 *
 * Baseline numbers at infra-landing time: see
 * `docs/decisions/2026-04-23-debt-add-benchmark-export-tool.md`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class ExportToolBenchmark {

    private lateinit var store: FileProjectStore
    private lateinit var tool: ExportTool
    private lateinit var input: ExportTool.Input

    @Setup(Level.Invocation)
    fun setup() {
        val fs = FakeFileSystem()
        val registry = RecentsRegistry("/.talevia/recents.json".toPath(), fs)
        store = FileProjectStore(
            registry = registry,
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
        )

        val projectId = ProjectId("bench-proj")
        val clips = (0 until 10).map { i ->
            Clip.Video(
                id = ClipId("c-$i"),
                timeRange = TimeRange((i * 5).seconds, ((i + 1) * 5).seconds),
                sourceRange = TimeRange(0.seconds, 5.seconds),
                assetId = AssetId("a-$i"),
            )
        }
        val timeline = Timeline(
            tracks = listOf(Track.Video(id = TrackId("v0"), clips = clips)),
            duration = 50.seconds,
        )

        runBlocking {
            store.upsert("bench", Project(id = projectId, timeline = timeline))
        }

        val engine: VideoEngine = InstantVideoEngine()
        tool = ExportTool(store, engine)
        input = ExportTool.Input(
            projectId = projectId.value,
            outputPath = "/tmp/bench-out.mp4",
            // Force re-render on every call so the benchmark exercises the
            // full dispatch path. Without this the render-cache would turn
            // the second measurement iteration into a no-op hit.
            forceRender = true,
        )
    }

    @Benchmark
    fun tenClipExport(): ToolResult<ExportTool.Output> = runBlocking {
        tool.execute(input, benchCtx())
    }

    private fun benchCtx(): ToolContext = ToolContext(
        sessionId = SessionId("bench-s"),
        messageId = MessageId("bench-m"),
        callId = CallId("bench-c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )
}

/**
 * Zero-cost fake [VideoEngine] for benchmarking — emits the minimum
 * event sequence `ExportTool` requires (`Started` + `Completed`) so the
 * tool reaches its result-assembly path. No filesystem or ffmpeg work.
 */
private class InstantVideoEngine : VideoEngine {
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

    override suspend fun thumbnail(
        asset: AssetId,
        source: MediaSource,
        time: Duration,
    ): ByteArray = ByteArray(0)
}
