package io.talevia.core.bench

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.domain.lockfile.EagerLockfile
import io.talevia.core.domain.lockfile.Lockfile
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.platform.GenerationProvenance
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Cold-open latency guard for [Lockfile] JSON deserialize — the hot path a
 * mature project hits every time it's opened on a fresh process (agent
 * restart, `ProjectStore.openAt`, CLI re-launch). `Lockfile` carries the
 * full `entries: List<LockfileEntry>` in the on-disk `talevia.json`; the
 * `@Transient byInputHash` / `byAssetId` maps are rebuilt from that list
 * on every deserialize, which is O(N) in entry count + allocations.
 *
 * **What the numbers unlock.** The `debt-lockfile-entries-index` bullet
 * gates the jsonl-split / incremental-load refactor on "benchmark shows
 * > 50 ms cold open". This benchmark is that measurement. Three entry
 * counts (500 / 1000 / 2000) spot-check the growth curve; if the 2000-
 * entry cold decode stays comfortably under 50 ms, the flatter ledger
 * stays fine and the split is deferred. If it crosses, the next cycle
 * has data-in-hand to justify the refactor under the trigger gate.
 *
 * **Budget policy (v1).** Never-fail, print-and-soft-warn — same policy
 * [AgentLoopBenchmark] documented, same reasoning. The soft budget
 * (50 ms) matches the bullet's own jsonl-split trigger, so a future
 * reviewer reading `[bench]` lines out of CI can see "under/over" at a
 * glance without hunting through the bullet.
 *
 * The untimed warm-up pass amortises `kotlinx.serialization` reflection /
 * JIT costs — same idiom used in the other two benches; without it the
 * first run is 3-5× slower than steady state and the signal is useless.
 */
class LockfileEntriesBenchmark {

    @Test fun coldDeserializeScalesSublinearly() {
        val json = JsonConfig.default
        // Warm-up on a small ledger so JIT / reflection caches prime.
        val warmup = EagerLockfile(entries = (0 until 100).map(::syntheticEntry))
        val warmupText = json.encodeToString(EagerLockfile.serializer(), warmup)
        repeat(3) { json.decodeFromString(EagerLockfile.serializer(), warmupText) }

        for (size in listOf(500, 1000, 2000)) {
            val text = json.encodeToString(
                EagerLockfile.serializer(),
                EagerLockfile(entries = (0 until size).map(::syntheticEntry)),
            )
            val elapsed = measureTime {
                // Repeat 3x per size so one GC stall doesn't dominate the
                // printed number. Report the mean.
                repeat(3) { json.decodeFromString(EagerLockfile.serializer(), text) }
            } / 3
            AgentLoopBenchmark.report(
                name = "lockfile.cold-decode.${size}-entries",
                elapsed = elapsed,
                softBudget = SOFT_BUDGET,
            )
        }
    }

    private fun syntheticEntry(i: Int): LockfileEntry = LockfileEntry(
        inputHash = "h-${i.toString().padStart(6, '0')}",
        toolId = "bench_generate_image",
        assetId = AssetId("asset-$i"),
        provenance = GenerationProvenance(
            providerId = "bench-provider",
            modelId = "bench-model",
            modelVersion = "v1",
            seed = i.toLong(),
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 1_700_000_000_000L + i,
        ),
        costCents = if (i % 3 == 0) 5L else null,
        sessionId = "bench-session",
        resolvedPrompt = "synthetic prompt body item $i".repeat(4),
    )

    companion object {
        /**
         * Matches the `debt-lockfile-entries-index` trigger ("> 50 ms cold
         * open"). Crossing this on the 2000-entry probe is the signal that
         * justifies the jsonl-split refactor under the bullet's gate.
         */
        private val SOFT_BUDGET: Duration = 50.milliseconds
    }
}
