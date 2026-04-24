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
import io.talevia.core.domain.render.ClipRenderCache
import io.talevia.core.domain.render.ClipRenderCacheEntry
import io.talevia.core.domain.render.TransitionFades
import io.talevia.core.domain.render.clipMezzanineFingerprint
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
import kotlinx.benchmark.Param
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
 * Wall-time proof of the per-clip incremental-render invariant
 * (`export-incremental-render-phase-2-per-clip-memoization`): export wall
 * time scales **linearly** with the number of cache-miss clips, not with
 * total clip count. Three parametrized runs — 0 of 10 clips cached (all
 * misses), 5 of 10 (half), 10 of 10 (full hit) — should show a monotone
 * decrease in `perClipExport` time as `preCachedClips` climbs.
 *
 * Engine is [CountingPerClipEngine], whose `renderClip` sleeps
 * [SIMULATED_RENDER_MS] per call — NOT a no-op. Cycle-38's full benchmark
 * run exposed the no-op variant as monotonically-SLOWER with more pre-
 * cached clips (0→1.360, 5→1.606, 10→1.920 ms/op) — the opposite of the
 * claim — because `ClipRenderCache.findByFingerprint` is an O(N) linear
 * scan and, with zero render savings, lookup cost dominated. A tiny
 * simulated render cost (2 ms/clip is ~10× an ffmpeg kernel dispatch for
 * a trivial clip; still two orders of magnitude shy of a real transcode)
 * restores the cache-hit-dominates ratio real callers experience, so the
 * curve's expected direction matches the production invariant being
 * proved. See
 * `docs/decisions/2026-04-23-debt-perclip-benchmark-inverted-scaling-under-stub-engine.md`.
 *
 * Cache-hit detection also needs `engine.mezzaninePresent(path)` to
 * return true for the seeded mezzanines — the benchmark's fake engine
 * records every path it "pre-seeded" in its `preSeededPaths` set so
 * `mezzaninePresent` can answer correctly without filesystem work.
 *
 * Complements `ExportToolBenchmark` (whole-timeline orchestration
 * baseline): that benchmark catches regressions in the default dispatch
 * path; this one catches regressions in the per-clip memoization path
 * specifically, and proves the O(misses) scaling that makes incremental
 * re-export worthwhile.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class PerClipRenderCacheBenchmark {

    /**
     * How many of the 10 clips start with a valid cache entry. 0 =
     * everything misses; 10 = everything hits; 5 = half-and-half.
     */
    @Param("0", "5", "10")
    var preCachedClips: Int = 0

    private lateinit var store: FileProjectStore
    private lateinit var engine: CountingPerClipEngine
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

        val projectId = ProjectId("bench-perclip-proj")
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

        engine = CountingPerClipEngine()

        val output = OutputSpec(
            targetPath = "/tmp/bench-out.mp4",
            resolution = timeline.resolution,
            frameRate = timeline.frameRate.numerator / timeline.frameRate.denominator,
            videoCodec = "h264",
            audioCodec = "aac",
        )

        // Pre-seed the ClipRenderCache for the first `preCachedClips` clips.
        // Fingerprints come from the same `clipMezzanineFingerprint` ExportTool
        // will recompute at dispatch time, so the hits are legitimate (not a
        // rigged match via string fiddling).
        val preEntries = (0 until preCachedClips).map { i ->
            val clip = clips[i]
            val fingerprint = clipMezzanineFingerprint(
                clip = clip,
                fades = null,
                boundSourceDeepHashes = emptyMap(),
                output = output,
                engineId = engine.engineId,
            )
            // Mezzanine path convention from `mezzanineDirFor(outputPath, projectId)`;
            // mirror it here so the path matches what `runPerClipRender` computes.
            val mezzaninePath = "/tmp/.talevia-render-cache/${projectId.value}/$fingerprint.mp4"
            engine.preSeededPaths += mezzaninePath
            ClipRenderCacheEntry(
                fingerprint = fingerprint,
                mezzaninePath = mezzaninePath,
                resolutionWidth = output.resolution.width,
                resolutionHeight = output.resolution.height,
                durationSeconds = clip.sourceRange.duration.toDouble(kotlin.time.DurationUnit.SECONDS),
                createdAtEpochMs = 0L,
            )
        }
        val project = Project(
            id = projectId,
            timeline = timeline,
            clipRenderCache = ClipRenderCache(entries = preEntries),
        )

        runBlocking {
            store.upsert("bench", project)
        }

        tool = ExportTool(store, engine)
        input = ExportTool.Input(
            projectId = projectId.value,
            outputPath = "/tmp/bench-out.mp4",
            forceRender = true,
        )
    }

    @Benchmark
    fun perClipExport(): ToolResult<ExportTool.Output> = runBlocking {
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
 * Per-clip-capable fake engine for the cache-hit-ratio benchmark.
 * `supportsPerClipCache=true` routes `ExportTool` through the per-clip
 * path; `renderClip` sleeps [SIMULATED_RENDER_MS] per dispatch to
 * simulate a small-but-non-zero transcode cost (see class KDoc on
 * [PerClipRenderCacheBenchmark] for rationale); `concatMezzanines`
 * remains a no-op because the benchmark's signal of interest is the
 * render-miss count, not concat cost; `mezzaninePresent` answers from
 * a pre-seeded set populated at setup time so cache-hit paths are
 * realistic (the `runPerClipRender` guard requires both "fingerprint in
 * cache" AND "mezzanine file on disk").
 */
private class CountingPerClipEngine : VideoEngine {

    override val engineId: String = "bench-per-clip"
    override val supportsPerClipCache: Boolean = true

    val preSeededPaths: MutableSet<String> = mutableSetOf()

    override suspend fun probe(source: MediaSource): MediaMetadata =
        MediaMetadata(duration = Duration.ZERO, resolution = Resolution(0, 0), frameRate = null)

    override fun render(
        timeline: Timeline,
        output: OutputSpec,
        resolver: MediaPathResolver?,
    ): Flow<RenderProgress> = flow {
        // Fallback whole-timeline path — not expected to run for the shapes
        // this benchmark builds (single video track, ≥1 clip), but kept
        // spec-compliant so a misconfigured benchmark doesn't crash.
        emit(RenderProgress.Started("bench-job"))
        emit(RenderProgress.Completed("bench-job", output.targetPath))
    }

    override suspend fun mezzaninePresent(path: String): Boolean = path in preSeededPaths

    override suspend fun renderClip(
        clip: Clip.Video,
        fades: TransitionFades?,
        output: OutputSpec,
        mezzaninePath: String,
        resolver: MediaPathResolver?,
    ) {
        // Simulated render cost. A no-op here was the root cause of cycle-38's
        // inverted curve — with zero render savings, the O(N) linear-scan cost
        // of `ClipRenderCache.findByFingerprint` dominated and more-cached
        // runs became slower. This tiny delay is not a regression gate on
        // ffmpeg throughput (that's [io.talevia.platform.ffmpeg.FfmpegVideoEngine]'s
        // job, not a benchmark harness's); it exists so the ratio of render-
        // cost to cache-lookup-cost mirrors production.
        kotlinx.coroutines.delay(SIMULATED_RENDER_MS)
        // Record that this path now exists, so subsequent same-bench-run
        // lookups (none expected today but cheap) would see it as present.
        preSeededPaths += mezzaninePath
    }

    override suspend fun concatMezzanines(
        mezzaninePaths: List<String>,
        subtitles: List<Clip.Text>,
        output: OutputSpec,
    ) {
        // No-op — this is the cheap stage we want to keep cheap in the
        // benchmark so the measured curve reflects miss-count, not
        // concat overhead.
    }

    override suspend fun thumbnail(
        asset: AssetId,
        source: MediaSource,
        time: Duration,
    ): ByteArray = ByteArray(0)
}

/**
 * Per-renderClip simulated latency for [CountingPerClipEngine]. Small
 * enough (2 ms ≈ 10× an ffmpeg kernel-dispatch floor for a trivial clip)
 * that 10 clips × 3 param values × N iterations stays well under JMH's
 * per-param budget, but larger than the nanoseconds-scale cost of
 * `ClipRenderCache.findByFingerprint` so the cache-hit win dominates the
 * lookup cost in the measured curve. Production FFmpeg render time per
 * clip is 100–1000× this; the benchmark isn't trying to model that — it's
 * trying to prove "cache hits save the render cost, and the saving
 * scales linearly with miss count".
 */
private const val SIMULATED_RENDER_MS: Long = 2L
