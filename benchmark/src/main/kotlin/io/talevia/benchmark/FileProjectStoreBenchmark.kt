package io.talevia.benchmark

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.RecentsRegistry
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.openjdk.jmh.annotations.Level
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Wall-time baseline for [FileProjectStore]'s hottest-path operations
 * (`debt-filestore-benchmark`). Every Core tool that mutates project state
 * pays the encode/write cost of this store; every session open pays the
 * decode/read cost. A refactor that accidentally turns these O(size²) —
 * e.g. re-reading the envelope inside a loop, or re-serialising the full
 * project on every clip add — shows up as orchestration-cost drift which
 * this benchmark catches.
 *
 * Storage is an Okio [FakeFileSystem] (no real disk I/O) — the benchmark
 * measures `talevia.json` encode/decode + registry lookup + mutex bookkeeping
 * + Okio path-handling cost, which is the load-bearing orchestration work
 * this store does. Real-filesystem numbers would be dominated by OS write
 * latency and mask the refactor signal; this lane guards the pure-store
 * cost the same way `ExportToolBenchmark` guards orchestration without
 * real ffmpeg.
 *
 * Three primitives measured via three `@Param`-swept clip counts. With
 * `clips ∈ {10, 100, 500}`:
 * - `openAtBundle` — `openAt(pathToBundle)` deserialises `talevia.json`,
 *   loads lockfile + snapshots + source DAG. O(bundle-size).
 * - `upsertProject` — idempotent overwrite of an N-clip project; measures
 *   encode + fs write + `recents.json` touch. O(bundle-size) too, mostly
 *   because the entire envelope is re-encoded per mutation today (the
 *   bullet `bundle-talevia-json-split` is the trigger-gated path that
 *   would split sub-files to make this O(delta)).
 * - `listProjects` — `list()` walks the recents registry + decodes each
 *   bundle's envelope. O(N bundles × bundle-size); today's shape means
 *   listing 100 projects costs ≈ 100× the openAt cost for each.
 *
 * `@Setup(Level.Trial)` does the expensive one-shot bundle construction
 * (outside the measured region); `@Setup(Level.Invocation)` isn't needed
 * here because the measured operations are idempotent (upsert overwrites,
 * openAt reads, list reads).
 *
 * Baseline numbers: run `./gradlew :benchmark:mainBenchmark --tests '*FileProjectStore*'`
 * for current numbers. The benchmark is wired for on-demand measurement
 * rather than CI-pinned assertions — same convention as
 * `ExportToolBenchmark` / `PerClipRenderCacheBenchmark`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class FileProjectStoreBenchmark {

    /** Number of clips per project; sweeps the per-bundle size. */
    @Param("10", "100", "500")
    var clips: Int = 10

    private lateinit var fs: FakeFileSystem
    private lateinit var store: FileProjectStore

    /**
     * ProjectId used for the openAt / upsert benchmarks. Pre-seeded in
     * `@Setup(Level.Trial)` so `openAtBundle` can read it and
     * `upsertProject` can overwrite it idempotently without the first
     * call paying a fresh-create tax the second doesn't. ProjectId is an
     * inline value class so `lateinit` isn't allowed — initialise with a
     * sentinel that `@Setup` overwrites.
     */
    private var primaryId: ProjectId = ProjectId("uninit")
    private lateinit var primaryPath: Path
    private lateinit var primaryProject: Project

    @Setup(Level.Trial)
    fun setupTrial() {
        fs = FakeFileSystem()
        val registry = RecentsRegistry("/.talevia/recents.json".toPath(), fs)
        store = FileProjectStore(
            registry = registry,
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
        )

        // Seed 10 peer projects so `listProjects` has real work. Each is a
        // smaller-but-nontrivial 10-clip project; the primary project
        // (measured for openAt / upsert) uses the sweep parameter `clips`.
        runBlocking {
            for (i in 0 until 10) {
                val peerId = ProjectId("peer-$i")
                store.upsert("peer-$i", projectOf(peerId, clipCount = 10))
            }
        }

        primaryId = ProjectId("bench-primary")
        primaryProject = projectOf(primaryId, clipCount = clips)
        runBlocking { store.upsert("bench-primary", primaryProject) }
        // Cache the path so openAt doesn't re-query the registry each iteration.
        primaryPath = runBlocking { store.pathOf(primaryId)!! }
    }

    @Benchmark
    fun openAtBundle(): Project = runBlocking { store.openAt(primaryPath) }

    @Benchmark
    fun upsertProject(): Unit = runBlocking {
        store.upsert("bench-primary", primaryProject)
    }

    @Benchmark
    fun listProjects(): List<Project> = runBlocking { store.list() }

    private fun projectOf(id: ProjectId, clipCount: Int): Project {
        val clips = (0 until clipCount).map { i ->
            Clip.Video(
                id = ClipId("c-$i"),
                timeRange = TimeRange((i * 5).seconds, ((i + 1) * 5).seconds),
                sourceRange = TimeRange(0.seconds, 5.seconds),
                assetId = AssetId("a-$i"),
            )
        }
        return Project(
            id = id,
            timeline = Timeline(
                tracks = listOf(Track.Video(id = TrackId("v0"), clips = clips)),
                duration = (clipCount * 5).seconds,
            ),
        )
    }
}
