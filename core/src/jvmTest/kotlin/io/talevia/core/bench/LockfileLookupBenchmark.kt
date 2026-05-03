package io.talevia.core.bench

import io.talevia.core.AssetId
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
 * Wall-time regression guard for [Lockfile.findByInputHash] on a
 * mature-project-sized lockfile (500 entries + 10 000 lookups).
 *
 * Current impl backs the lookup with a [Lockfile.byInputHash] map rebuilt
 * via `entries.associateBy` at `@Transient` init — O(1) per call and
 * O(N) per Lockfile construction. This benchmark anchors that cost so a
 * future rewrite (e.g. jsonl-per-entry streaming, covered by the
 * `debt-lockfile-entries-index` P1 bullet) has a baseline to compare
 * against. If the map ever gets replaced by a list-scan the printed
 * number jumps > 100× and a reviewer notices.
 *
 * **Budget policy (v1).** Never-fail, print-and-soft-warn. Real observed
 * numbers on CI are in the low single-digit milliseconds for the full
 * 10 000-lookup loop; the soft budget of 250 ms leaves headroom for a
 * noisy CI while still catching any order-of-magnitude regression.
 */
class LockfileLookupBenchmark {

    @Test fun lookupOn500EntryLockfile() {
        val entries = (0 until 500).map { i -> syntheticEntry(i) }
        val lockfile = EagerLockfile(entries = entries)

        // Hash to hit on every lookup — choose the median so we're not
        // biased toward insertion-order cache effects (there are none today
        // but the anchor matters if the impl changes).
        val targetHash = entries[250].inputHash
        val missingHash = "hash-not-present"

        // Untimed warm-up — same rationale as AgentLoopBenchmark.
        repeat(1000) {
            lockfile.findByInputHash(targetHash)
            lockfile.findByInputHash(missingHash)
        }

        val elapsed = measureTime {
            repeat(LOOKUP_COUNT) {
                lockfile.findByInputHash(targetHash)
                lockfile.findByInputHash(missingHash)
            }
        }
        AgentLoopBenchmark.report(
            name = "lockfile.findByInputHash.500entries.${LOOKUP_COUNT}x2lookups",
            elapsed = elapsed,
            softBudget = SOFT_BUDGET,
        )
    }

    private fun syntheticEntry(i: Int): LockfileEntry = LockfileEntry(
        inputHash = "hash-$i",
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
    )

    companion object {
        private const val LOOKUP_COUNT = 10_000
        private val SOFT_BUDGET: Duration = 250.milliseconds
    }
}
