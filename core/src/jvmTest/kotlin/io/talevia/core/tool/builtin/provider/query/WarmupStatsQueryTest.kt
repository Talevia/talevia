package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.provider.ProviderWarmupStats
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [runWarmupStatsQuery] —
 * `core/tool/builtin/provider/query/WarmupStatsQuery.kt`.
 * The ProviderQueryTool's `select=warmup_stats` handler.
 * Cycle 202 audit: 91 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Empty-snapshot fallback body cites "No provider
 *    warmup samples yet" + the synchronous-endpoint
 *    explanation.** Per kdoc: "Providers that have never
 *    emitted a matched pair (e.g. OpenAI's synchronous
 *    `/v1/images/generations` — no separate warmup/
 *    streaming split) are absent from the result." Drift
 *    in the body would lose the diagnostic for "why is
 *    my OpenAI provider missing?"
 *
 * 2. **Rows sorted by providerId alphabetically.** Drift
 *    to "insertion order" or "by latency" would shuffle
 *    deterministic output across re-queries.
 *
 * 3. **Title + body pluralization conditional on count.**
 *    "(1 provider)" vs "(2 providers)" in title; "(1
 *    sample)" vs "(N samples)" in per-row body. Drift
 *    to "always plural" would surface ungrammatical
 *    "1 providers" / "1 samples" strings.
 */
class WarmupStatsQueryTest {

    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest fun cleanup() {
        scope.cancel()
    }

    private suspend fun pumpWarmupPair(
        bus: EventBus,
        stats: ProviderWarmupStats,
        providerId: String,
        sessionId: String = "s",
        latencyMs: Long,
    ) {
        // Wait for the bus subscriber to register —
        // ProviderWarmupStats's launch{}.collect{} attaches
        // asynchronously; events fired before subscription
        // are lost (MutableSharedFlow has no replay).
        // Test reaches in via a sentinel-event publish loop
        // that yields between attempts; once the subscriber
        // consumes one Starting/Ready pair, future events
        // are guaranteed to land.
        repeat(64) { yield() }
        val before = stats.snapshot()[providerId]?.count ?: 0L
        bus.publish(
            BusEvent.ProviderWarmup(
                sessionId = SessionId(sessionId),
                providerId = providerId,
                phase = BusEvent.ProviderWarmup.Phase.Starting,
                epochMs = 0L,
            ),
        )
        bus.publish(
            BusEvent.ProviderWarmup(
                sessionId = SessionId(sessionId),
                providerId = providerId,
                phase = BusEvent.ProviderWarmup.Phase.Ready,
                epochMs = latencyMs,
            ),
        )
        // Wait for the ready event to be paired into the
        // snapshot (count incremented past `before`).
        withTimeout(2_000) {
            while (true) {
                val current = stats.snapshot()[providerId]?.count ?: 0L
                if (current > before) break
                yield()
            }
        }
    }

    private fun rowFields(
        rows: kotlinx.serialization.json.JsonArray,
        key: String,
    ): List<String> = rows.map { row ->
        row.toString().substringAfter("\"$key\":\"").substringBefore("\"")
    }

    // ── Empty snapshot ──────────────────────────────────────

    @Test fun emptyStatsReturnsZeroRowsWithDocumentedFallback() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)

        val result = runWarmupStatsQuery(stats)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertTrue(
            "No provider warmup samples yet" in result.outputForLlm,
            "fallback phrase; got: ${result.outputForLlm}",
        )
        assertTrue(
            "OpenAI image/video" in result.outputForLlm ||
                "synchronous endpoints" in result.outputForLlm,
            "synchronous-endpoint explanation cited; got: ${result.outputForLlm}",
        )
    }

    @Test fun emptyStatsTitlePluralizesAsZeroProviders() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)

        val result = runWarmupStatsQuery(stats)
        // 0 → plural ("0 providers"); per kdoc: zero is
        // plural in English.
        assertTrue(
            "(0 providers)" in result.title!!,
            "0 providers; got: ${result.title}",
        )
    }

    // ── Single provider ─────────────────────────────────────

    @Test fun singleProviderEmitsOneRow() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 100L)

        val result = runWarmupStatsQuery(stats)
        assertEquals(1, result.data.total)
        assertEquals("anthropic", rowFields(result.data.rows, "providerId").single())
    }

    @Test fun singleProviderTitlePluralizesAsOneProvider() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 100L)

        val result = runWarmupStatsQuery(stats)
        // Singular form for 1.
        assertTrue(
            "(1 provider)" in result.title!! && "providers)" !in result.title!!,
            "singular form for 1; got: ${result.title}",
        )
    }

    @Test fun singleSamplePluralizesAsOneSample() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 100L)

        val result = runWarmupStatsQuery(stats)
        // Body cites "(1 sample)" without 's'.
        assertTrue(
            "(1 sample)" in result.outputForLlm,
            "singular sample form; got: ${result.outputForLlm}",
        )
    }

    @Test fun multipleSamplesPluralizesAsNSamples() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 100L)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 200L)

        val result = runWarmupStatsQuery(stats)
        assertTrue(
            "(2 samples)" in result.outputForLlm,
            "plural samples form; got: ${result.outputForLlm}",
        )
    }

    // ── Multi-provider sort ────────────────────────────────

    @Test fun multipleProvidersSortedAlphabetically() = runBlocking {
        // Marquee sort pin: per impl `sortedBy { it.providerId }`.
        // Drift to "insertion order" would shuffle output
        // across re-queries.
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        // Pump in non-alphabetical order.
        pumpWarmupPair(bus, stats, providerId = "openai", latencyMs = 50L)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 100L)
        pumpWarmupPair(bus, stats, providerId = "google", latencyMs = 75L)

        val result = runWarmupStatsQuery(stats)
        assertEquals(3, result.data.total)
        // Alphabetical order: anthropic, google, openai.
        assertContentEquals(
            listOf("anthropic", "google", "openai"),
            rowFields(result.data.rows, "providerId"),
            "rows sorted alphabetically by providerId",
        )
    }

    @Test fun multipleProvidersTitlePluralizesAsNProviders() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 100L)
        pumpWarmupPair(bus, stats, providerId = "openai", latencyMs = 50L)

        val result = runWarmupStatsQuery(stats)
        assertTrue(
            "(2 providers)" in result.title!!,
            "plural form for 2; got: ${result.title}",
        )
    }

    // ── Body format ────────────────────────────────────────

    @Test fun bodyCitesPerProviderP50AndP99AndSampleCount() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 100L)

        val result = runWarmupStatsQuery(stats)
        // Format: "providerId p50=Xms p99=Yms (Z sample(s))"
        assertTrue(
            "anthropic p50=" in result.outputForLlm,
            "p50 cited; got: ${result.outputForLlm}",
        )
        assertTrue(
            "p99=" in result.outputForLlm,
            "p99 cited; got: ${result.outputForLlm}",
        )
        // ms suffix.
        assertTrue(
            "ms" in result.outputForLlm,
            "ms unit cited; got: ${result.outputForLlm}",
        )
    }

    @Test fun bodyCitesProviderCountPrefix() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 100L)

        val result = runWarmupStatsQuery(stats)
        assertTrue(
            "Warmup latency over 1 provider" in result.outputForLlm,
            "provider count prefix; got: ${result.outputForLlm}",
        )
    }

    // ── WarmupStatsRow shape ───────────────────────────────

    @Test fun rowExposesAllSnapshotFields() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 100L)

        val result = runWarmupStatsQuery(stats)
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"providerId\":\"anthropic\"" in rowJson)
        // count, p50, p95, p99, min, max, latest all surface.
        for (field in listOf("count", "p50Ms", "p95Ms", "p99Ms", "minMs", "maxMs", "latestMs")) {
            assertTrue(
                "\"$field\":" in rowJson,
                "$field surfaces; got: $rowJson",
            )
        }
    }

    @Test fun singleSampleP50EqualsP95EqualsP99EqualsLatency() = runBlocking {
        // Pin: when only one sample, all percentiles equal
        // that one value. Drift to "interpolate even with
        // 1 sample" would produce surprising NaN/0 values.
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 250L)

        val result = runWarmupStatsQuery(stats)
        val rowJson = result.data.rows[0].toString()
        // All percentiles == 250.
        assertTrue("\"p50Ms\":250" in rowJson, "p50; got: $rowJson")
        assertTrue("\"p95Ms\":250" in rowJson, "p95; got: $rowJson")
        assertTrue("\"p99Ms\":250" in rowJson, "p99; got: $rowJson")
        assertTrue("\"minMs\":250" in rowJson, "min; got: $rowJson")
        assertTrue("\"maxMs\":250" in rowJson, "max; got: $rowJson")
        assertTrue("\"latestMs\":250" in rowJson, "latest; got: $rowJson")
        assertTrue("\"count\":1" in rowJson, "count=1; got: $rowJson")
    }

    @Test fun multipleSamplesProduceDistinctMinMaxLatest() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 100L)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 300L)
        pumpWarmupPair(bus, stats, providerId = "anthropic", latencyMs = 200L)

        val result = runWarmupStatsQuery(stats)
        val rowJson = result.data.rows[0].toString()
        // count=3, min=100, max=300, latest=200 (the most-
        // recent value, NOT max).
        assertTrue("\"count\":3" in rowJson)
        assertTrue("\"minMs\":100" in rowJson, "min is smallest; got: $rowJson")
        assertTrue("\"maxMs\":300" in rowJson, "max is largest; got: $rowJson")
        assertTrue(
            "\"latestMs\":200" in rowJson,
            "latest is the LAST sample (200), NOT max (300); got: $rowJson",
        )
    }

    // ── Output.select echoes + total/returned shape ────────

    @Test fun outputSelectIsWarmupStats() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        val result = runWarmupStatsQuery(stats)
        assertEquals(ProviderQueryTool.SELECT_WARMUP_STATS, result.data.select)
    }

    @Test fun totalEqualsReturnedNoPagination() = runBlocking {
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        pumpWarmupPair(bus, stats, providerId = "a", latencyMs = 1L)
        pumpWarmupPair(bus, stats, providerId = "b", latencyMs = 2L)

        val result = runWarmupStatsQuery(stats)
        assertEquals(2, result.data.total)
        assertEquals(2, result.data.returned)
        assertEquals(2, result.data.rows.size)
    }
}
