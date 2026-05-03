package io.talevia.core.provider

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [ProviderWarmupStats] —
 * `core/provider/ProviderWarmupStats.kt`. Bus-event
 * aggregator that pairs `BusEvent.ProviderWarmup` Starting
 * + Ready phases (keyed by providerId + sessionId), records
 * elapsed milliseconds into a per-provider ring buffer, and
 * exposes P50 / P95 / P99 snapshots. Cycle 158 audit: 142
 * LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Starting + Ready pair by (providerId, sessionId)
 *    key.** A Starting event without its matching Ready
 *    stays in pending; a Ready without matching Starting
 *    is silently dropped (returning early without sample).
 *    Drift to "pair by providerId only" would conflate
 *    multi-session warmups; drift to throwing on unpaired
 *    Ready would crash the bus collector on any race.
 *
 * 2. **Ring buffer caps at `capacity`, oldest drops first.**
 *    Default capacity = 256 — beyond that, oldest sample
 *    drops to make room for the new one. Drift to unbounded
 *    growth would leak across long-lived sessions; drift to
 *    "newest drops" would surface stale latencies in P*.
 *
 * 3. **Negative elapsed coerced to ≥ 0.** Clock skew
 *    protection — if the Ready epoch is somehow before the
 *    Starting epoch (NTP drift, host clock reset), the
 *    sample lands as 0 ms instead of negative. Drift would
 *    let a single bad clock event poison the ring buffer
 *    with a min/max outlier.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProviderWarmupStatsTest {

    private fun starting(provider: String, session: String, atMs: Long) =
        BusEvent.ProviderWarmup(
            sessionId = SessionId(session),
            providerId = provider,
            phase = BusEvent.ProviderWarmup.Phase.Starting,
            epochMs = atMs,
        )

    private fun ready(provider: String, session: String, atMs: Long) =
        BusEvent.ProviderWarmup(
            sessionId = SessionId(session),
            providerId = provider,
            phase = BusEvent.ProviderWarmup.Phase.Ready,
            epochMs = atMs,
        )

    // ── pair-by-key ──────────────────────────────────────────────

    @Test fun matchedStartingReadyPairProducesElapsedSample() = runTest {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, backgroundScope)
        advanceUntilIdle()
        yield()

        bus.publish(starting("anthropic", "s1", 1_000L))
        bus.publish(ready("anthropic", "s1", 1_500L))
        advanceUntilIdle()
        yield()

        val snap = stats.snapshot()
        assertEquals(1, snap.size)
        val p = snap.getValue("anthropic")
        assertEquals(1L, p.count)
        assertEquals(500L, p.latestMs, "elapsed = 1500 - 1000 = 500 ms")
        assertEquals(500L, p.minMs)
        assertEquals(500L, p.maxMs)
    }

    @Test fun unmatchedStartingStaysPendingNoSampleEmitted() = runTest {
        // Pin: Starting without Ready → stays pending. No
        // sample emitted, no snapshot entry. The pending map
        // accumulates but doesn't surface as a metric.
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, backgroundScope)
        advanceUntilIdle()
        yield()

        bus.publish(starting("anthropic", "s1", 1_000L))
        advanceUntilIdle()
        yield()

        assertEquals(emptyMap(), stats.snapshot(), "no sample without matching Ready")
    }

    @Test fun unmatchedReadyDropsSilently() = runTest {
        // Pin: Ready without prior Starting → silently
        // dropped (`pending.remove(key) ?: return@collect`).
        // The bus collector doesn't crash; no sample lands.
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, backgroundScope)
        advanceUntilIdle()
        yield()

        bus.publish(ready("anthropic", "s1", 1_500L))
        advanceUntilIdle()
        yield()

        assertEquals(emptyMap(), stats.snapshot())
    }

    @Test fun differentSessionsArePairedSeparately() {
        // The marquee key-pairing pin: two providers' worth
        // of overlapping sessions don't cross-contaminate.
        // Without (providerId, sessionId) pairing, overlapping
        // Starting/Ready of different sessions could pair
        // incorrectly.
        runTest {
            val bus = EventBus()
            val stats = ProviderWarmupStats(bus, backgroundScope)
            advanceUntilIdle()
            yield()

            // Interleaved: s1 Starting, s2 Starting, s1 Ready, s2 Ready.
            bus.publish(starting("anthropic", "s1", 1_000L))
            bus.publish(starting("anthropic", "s2", 1_100L))
            bus.publish(ready("anthropic", "s1", 1_300L))
            bus.publish(ready("anthropic", "s2", 1_500L))
            advanceUntilIdle()
            yield()

            val p = stats.snapshot().getValue("anthropic")
            assertEquals(2L, p.count, "both sessions pair correctly")
            // s1: 300ms, s2: 400ms.
            assertEquals(400L, p.latestMs)
            assertEquals(300L, p.minMs)
            assertEquals(400L, p.maxMs)
        }
    }

    @Test fun differentProvidersAreSeparateEntriesInSnapshot() = runTest {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, backgroundScope)
        advanceUntilIdle()
        yield()

        bus.publish(starting("anthropic", "s1", 1_000L))
        bus.publish(ready("anthropic", "s1", 1_500L))
        bus.publish(starting("openai", "s1", 2_000L))
        bus.publish(ready("openai", "s1", 2_100L))
        advanceUntilIdle()
        yield()

        val snap = stats.snapshot()
        assertEquals(2, snap.size, "anthropic + openai both surface")
        assertEquals(500L, snap.getValue("anthropic").latestMs)
        assertEquals(100L, snap.getValue("openai").latestMs)
    }

    // ── negative elapsed coerced to 0 ───────────────────────────

    @Test fun negativeElapsedCoercedToZero() = runTest {
        // Marquee clock-skew pin: Ready epoch < Starting
        // epoch shouldn't poison the buffer with a negative
        // sample. Coerced to 0.
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, backgroundScope)
        advanceUntilIdle()
        yield()

        // Starting at 5000, Ready at 1000 → would be -4000.
        bus.publish(starting("anthropic", "s1", 5_000L))
        bus.publish(ready("anthropic", "s1", 1_000L))
        advanceUntilIdle()
        yield()

        val p = stats.snapshot().getValue("anthropic")
        assertEquals(0L, p.latestMs, "negative coerced to 0")
        assertEquals(0L, p.minMs)
        assertEquals(0L, p.maxMs)
    }

    // ── ring buffer cap ─────────────────────────────────────────

    @Test fun ringBufferCapsAtCapacityWithOldestDroppingFirst() = runTest {
        val bus = EventBus()
        // Use small capacity for fast test.
        val stats = ProviderWarmupStats(bus, backgroundScope, capacity = 3)
        advanceUntilIdle()
        yield()

        // Publish 5 pairs with elapsed 100/200/300/400/500.
        for (i in 1..5) {
            val sid = "s$i"
            bus.publish(starting("anthropic", sid, 0L))
            bus.publish(ready("anthropic", sid, i * 100L))
        }
        advanceUntilIdle()
        yield()

        val p = stats.snapshot().getValue("anthropic")
        // Pin: only the LAST 3 fit (capacity = 3). Oldest
        // (100, 200) dropped. Remaining: 300, 400, 500.
        assertEquals(3L, p.count, "ring buffer caps at capacity")
        assertEquals(300L, p.minMs, "oldest survivor")
        assertEquals(500L, p.maxMs, "newest")
        assertEquals(500L, p.latestMs)
    }

    @Test fun defaultCapacityIs256() {
        // Pin: documented default. Drift to a different
        // value (e.g. 128) would silently halve the
        // observation window every container uses.
        assertEquals(256, ProviderWarmupStats.DEFAULT_CAPACITY)
    }

    // ── snapshot percentile math ────────────────────────────────

    @Test fun snapshotPercentilesComputeFromSortedSamples() = runTest {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, backgroundScope, capacity = 100)
        advanceUntilIdle()
        yield()

        // Publish 10 pairs with elapsed 100..1000 (deterministic).
        for (i in 1..10) {
            val sid = "s$i"
            bus.publish(starting("anthropic", sid, 0L))
            bus.publish(ready("anthropic", sid, i * 100L))
        }
        advanceUntilIdle()
        yield()

        val p = stats.snapshot().getValue("anthropic")
        assertEquals(10L, p.count)
        assertEquals(100L, p.minMs)
        assertEquals(1_000L, p.maxMs)
        // Percentile math: idx = (pct/100.0 * (size-1)).toInt().
        // P50 of 10 elements: idx = 0.5 * 9 = 4.5 → 4 → 5th
        // sorted = 500.
        assertEquals(500L, p.p50Ms, "P50 = sorted[idx=4] = 500")
        // P95: idx = 0.95 * 9 = 8.55 → 8 → 9th sorted = 900.
        assertEquals(900L, p.p95Ms)
        // P99: idx = 0.99 * 9 = 8.91 → 8 → 9th sorted = 900.
        assertEquals(900L, p.p99Ms)
    }

    @Test fun singleSamplePercentilesAllReturnSameValue() = runTest {
        // Pin: with size=1, percentile idx = (pct/100.0 * 0)
        // .toInt() = 0 for ALL percentiles. P50 == P95 ==
        // P99 == min == max == latest.
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, backgroundScope)
        advanceUntilIdle()
        yield()

        bus.publish(starting("anthropic", "s1", 0L))
        bus.publish(ready("anthropic", "s1", 250L))
        advanceUntilIdle()
        yield()

        val p = stats.snapshot().getValue("anthropic")
        assertEquals(1L, p.count)
        assertEquals(250L, p.p50Ms)
        assertEquals(250L, p.p95Ms)
        assertEquals(250L, p.p99Ms)
        assertEquals(250L, p.minMs)
        assertEquals(250L, p.maxMs)
        assertEquals(250L, p.latestMs)
    }

    @Test fun emptyStatsReturnsEmptyMap() = runTest {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        // No events published.
        assertEquals(emptyMap(), stats.snapshot())
    }

    // ── samples StateFlow ───────────────────────────────────────

    @Test fun samplesStateFlowExposesUnderlyingMap() = runTest {
        // Pin: `samples` is a StateFlow exposing the same map
        // the snapshot() reads from. Useful for UI binding
        // that wants live updates without polling snapshot().
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, backgroundScope)
        advanceUntilIdle()
        yield()

        bus.publish(starting("anthropic", "s1", 0L))
        bus.publish(ready("anthropic", "s1", 100L))
        advanceUntilIdle()
        yield()

        val state = stats.samples.value
        assertTrue("anthropic" in state)
        assertEquals(listOf(100L), state["anthropic"])
    }

    // ── withSupervisor companion ────────────────────────────────

    @Test fun withSupervisorConvenienceConstructsValidInstance() {
        // Pin: the kdoc-documented convenience for callers
        // without a dedicated CoroutineScope. Default scope
        // is `SupervisorJob() + Dispatchers.Default`.
        val bus = EventBus()
        val stats = ProviderWarmupStats.withSupervisor(bus)
        // Sanity: snapshot is callable, returns empty.
        assertEquals(emptyMap(), stats.snapshot())
    }
}
