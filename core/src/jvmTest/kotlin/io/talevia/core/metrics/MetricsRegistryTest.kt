package io.talevia.core.metrics

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the ring-buffer invariant introduced for
 * `debt-bound-metrics-histogram-ring-buffer`. Before the change, every
 * `observe(name, ms)` call appended to an unbounded `MutableList<Long>`
 * and the only eviction path (`reset()`) was test-only. A long-lived
 * server process observing one latency per tool dispatch would leak
 * a few hundred bytes per observation indefinitely.
 *
 * Tests cover three properties: (1) the cap is respected (overflow
 * evicts oldest), (2) percentile estimates stay sane when the window
 * saturates, and (3) the pre-cap happy path still behaves exactly as
 * it did before.
 */
class MetricsRegistryTest {

    @Test fun observeUnderCapRetainsAllSamples() = runTest {
        val m = MetricsRegistry()
        val name = "tool.x.ms"
        val samples = (1..200L).toList()
        samples.forEach { m.observe(name, it) }

        val snap = m.histogramSnapshot().getValue(name)
        assertEquals(200L, snap.count, "under-cap window must retain every observation")
        // Percentile sanity: `percentile(pct)` uses idx = (pct/100 * (n-1)).toInt()
        // on the sorted window. For 1..200: P50 → idx 99 → value 100;
        // P95 → idx 189 → value 190; P99 → idx 197 → value 198.
        assertEquals(100L, snap.p50)
        assertEquals(190L, snap.p95)
        assertEquals(198L, snap.p99)
    }

    @Test fun observeAtCapEvictsOldestAndKeepsCap() = runTest {
        val m = MetricsRegistry()
        val name = "tool.y.ms"
        // Fill exactly to cap.
        repeat(MetricsRegistry.HISTOGRAM_CAP_PER_NAME) { m.observe(name, it.toLong() + 1) }
        val atCap = m.histogramSnapshot().getValue(name)
        assertEquals(
            MetricsRegistry.HISTOGRAM_CAP_PER_NAME.toLong(),
            atCap.count,
            "filling to cap must retain exactly cap samples",
        )

        // Push 500 more — the first 500 must be evicted; the retained window
        // becomes [501..1524] plus whatever's left of the original. Actually
        // after cap + 500 pushes, the window is the LAST cap observations,
        // i.e. values (501 + (cap - 1024))..1524 shifted — but since cap is
        // 1024, after 500 overflows the retained range is 501..1524.
        val overflowCount = 500
        repeat(overflowCount) { m.observe(name, (MetricsRegistry.HISTOGRAM_CAP_PER_NAME + it + 1).toLong()) }
        val afterOverflow = m.histogramSnapshot().getValue(name)
        assertEquals(
            MetricsRegistry.HISTOGRAM_CAP_PER_NAME.toLong(),
            afterOverflow.count,
            "overflow beyond cap must still report exactly cap in-window samples",
        )
        // Retained window: [501..1524]. P50 sits ~index 511 which is value 1012;
        // P95 ~ index 972 which is value 1473; P99 ~ index 1013 which is value 1514.
        // Guard with `>=` on the oldest-retained lower bound so small indexing
        // drift in the percentile helper doesn't make the test flaky across
        // rewrites — the load-bearing assertion is "percentiles have shifted
        // upward past the evicted window", not the exact arithmetic.
        assertTrue(
            afterOverflow.p50 >= 501L,
            "P50 must track the retained window (≥ 501 after first-500 eviction); got ${afterOverflow.p50}",
        )
        assertTrue(
            afterOverflow.p99 >= 1400L,
            "P99 must reflect near-newest samples in the retained window; got ${afterOverflow.p99}",
        )
    }

    @Test fun observeManyTimesCapMemoryDoesNotExplode() = runTest {
        // Stress-invariant: run way past cap (10×) and prove the window size
        // never grows beyond cap. Pre-fix this would have accumulated 10 240
        // entries; post-fix it stays at 1024.
        val m = MetricsRegistry()
        val name = "tool.z.ms"
        val times = MetricsRegistry.HISTOGRAM_CAP_PER_NAME * 10
        repeat(times) { m.observe(name, (it % 1000).toLong()) }
        val snap = m.histogramSnapshot().getValue(name)
        assertEquals(
            MetricsRegistry.HISTOGRAM_CAP_PER_NAME.toLong(),
            snap.count,
            "10× cap observations must still report exactly cap — the ring buffer is the only reason this invariant holds",
        )
    }

    @Test fun resetClearsCountersAndHistograms() = runTest {
        // Sanity: reset() must still wipe both tables. If a future refactor
        // splits the two tables' lifetimes (e.g. lazy-init only one), this
        // guards the existing contract.
        val m = MetricsRegistry()
        m.increment("a.b", by = 3)
        m.observe("c.d", 17)
        assertEquals(3L, m.get("a.b"))
        assertEquals(1L, m.histogramSnapshot().getValue("c.d").count)

        m.reset()
        assertEquals(0L, m.get("a.b"))
        assertTrue(
            m.histogramSnapshot().isEmpty(),
            "reset must clear histograms — previously-observed names drop entirely",
        )
    }

    @Test fun independentHistogramsDoNotCrossContaminate() = runTest {
        // Two named histograms must not share a ring buffer — a `tool.x.ms`
        // overflow must not evict from `tool.y.ms`. The bullet's stated
        // retention policy is per-name, not global.
        val m = MetricsRegistry()
        repeat(MetricsRegistry.HISTOGRAM_CAP_PER_NAME + 100) { m.observe("tool.x.ms", 42) }
        m.observe("tool.y.ms", 7)
        val snap = m.histogramSnapshot()
        assertEquals(MetricsRegistry.HISTOGRAM_CAP_PER_NAME.toLong(), snap.getValue("tool.x.ms").count)
        assertEquals(1L, snap.getValue("tool.y.ms").count, "independent names must not share cap")
    }
}
