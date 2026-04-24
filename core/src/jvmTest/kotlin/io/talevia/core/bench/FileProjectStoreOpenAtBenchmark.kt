package io.talevia.core.bench

import io.talevia.core.AssetId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaMetadata
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.RecentsRegistry
import io.talevia.core.domain.Resolution
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Cold-open latency guard for [FileProjectStore.openAt] — the hot
 * path every fresh session / CLI launch / Agent restart hits when
 * reattaching to an existing bundle. `openAt` reads + JSON-decodes
 * `talevia.json` via `decodeStored` (now decoded *once* per call
 * after cycle `735bc7a9`'s `createdAtEpochMs` cache consolidation,
 * versus the two decodes the previous `readTitle` + `readBundle`
 * combo used to do), `registry.upsert`-writes `recents.json`, and
 * emits optional validation / missing-assets warnings through the
 * EventBus.
 *
 * **What the numbers unlock.** R.6 #4 scan flagged `FileProjectStore`
 * as one of the four critical paths that needs wall-time regression
 * guards. Agent loop (`c5daba05`), lockfile cold decode (`6a47516d`),
 * and ExportTool (`936b0864`) already landed; this one closes the
 * quartet. A ledger-heavy mature project (say 1000 assets registered
 * in the bundle) should still open quickly — if it doesn't,
 * subsequent cycles have baseline data to justify a `jsonl`-style
 * streaming open.
 *
 * **Budget policy (v1).** Never-fail, print-and-soft-warn per the
 * bench harness established in [AgentLoopBenchmark]. Soft budget:
 * 100ms for the 1000-asset probe — generous but catches an
 * order-of-magnitude regression.
 */
class FileProjectStoreOpenAtBenchmark {

    @Test fun coldOpenScalesWithAssetCount() = runTest {
        // Warm-up — prime JIT / kotlinx.serialization reflection /
        // FakeFileSystem lookups. Same idiom the other bench files
        // document; without it the first measured size reports 3-5×
        // steady state and obscures the signal.
        runOpenAtScenario(assetCount = 50)

        for (size in listOf(100, 500, 1000)) {
            val elapsed = measureTime {
                // Repeat 3x per size and report the mean so one GC
                // stall doesn't dominate the printed number.
                repeat(3) { runOpenAtScenario(assetCount = size) }
            } / 3
            AgentLoopBenchmark.report(
                name = "file-project-store.openAt.${size}-assets",
                elapsed = elapsed,
                softBudget = SOFT_BUDGET,
            )
        }
    }

    /**
     * Creates a fresh bundle with [assetCount] assets, then spins up a
     * *separate* FileProjectStore over the same FakeFileSystem and
     * times `openAt` on the path. The fresh-store part is the cold-open
     * scenario; the create-then-open splits the work the same way an
     * agent restart does (createAt happens in a prior session; openAt
     * is this session's first action on the bundle).
     */
    private suspend fun runOpenAtScenario(assetCount: Int) {
        val (creator, fs) = ProjectStoreTestKit.createWithFs()
        val bundlePath = "/projects/bench-$assetCount.talevia".toPath()
        val project = creator.createAt(bundlePath, title = "bench-$assetCount")
        // Seed the bundle with N synthetic assets so the envelope JSON
        // grows with N. FakeFileSystem has no real encode cost so the
        // assets never land on disk as bytes, only as JSON in
        // talevia.json — exactly what openAt decodes on cold start.
        creator.mutate(project.id) { p ->
            p.copy(assets = (0 until assetCount).map { syntheticAsset(it) })
        }

        // Fresh-process simulation: new FileProjectStore instance over
        // the same FakeFileSystem, new RecentsRegistry. This matches
        // what happens across real CLI restarts.
        val registry = RecentsRegistry("/.talevia/recents.json".toPath(), fs)
        val cold = FileProjectStore(
            registry = registry,
            defaultProjectsHome = "/.talevia/projects".toPath(),
            fs = fs,
        )
        cold.openAt(bundlePath)
    }

    private fun syntheticAsset(i: Int): MediaAsset = MediaAsset(
        id = AssetId("asset-${i.toString().padStart(6, '0')}"),
        source = MediaSource.BundleFile("media/asset-$i.mp4"),
        metadata = MediaMetadata(duration = 5.seconds, resolution = Resolution(1920, 1080)),
    )

    companion object {
        /**
         * 100ms soft budget. Real observed numbers on CI are well below
         * this for a 1000-asset bundle; the marker fires only on a
         * clear regression. Aligns with the 50ms budget
         * `LockfileEntriesBenchmark` uses for comparable ledger decode
         * sizes, with some slack for the extra fs.read + registry.upsert
         * + bus.publish calls `openAt` does.
         */
        private val SOFT_BUDGET: Duration = 100.milliseconds
    }
}
