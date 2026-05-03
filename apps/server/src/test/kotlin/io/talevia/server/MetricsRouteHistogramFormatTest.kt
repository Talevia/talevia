package io.talevia.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for `/metrics` histogram formatting —
 * `apps/server/.../MetricsRoute.kt:30-36`. Cycle 228 audit:
 * existing `MetricsEndpointTest` (cycle prior) covers the counter
 * branch + empty-body case; the histogram branch (`_count` / `_p50` /
 * `_p95` / `_p99` line suffixes) had no direct pin.
 *
 * Same audit-pattern fallback as cycles 207-227.
 *
 * Three correctness contracts pinned (one per shape invariant):
 *
 *  1. **Histogram observation produces 4 lines.** A single observed
 *     histogram emits exactly `<base>_count`, `<base>_p50`,
 *     `<base>_p95`, `<base>_p99` — drift to 3 lines (drop count) or
 *     5+ lines (e.g. add p50/p95/p99/max) would break Prometheus
 *     scraper expectations and downstream dashboard panels.
 *
 *  2. **Dot-to-underscore replacement.** Histogram names like
 *     `tool.export.ms` → `talevia_tool_export_ms_p50` (every dot
 *     replaced; `talevia_` prefix; suffix appended literally). Drift
 *     to "preserve dots" or "split on underscore" would break
 *     scrape-time label parsing.
 *
 *  3. **Counters and histograms coexist** — a registry with both
 *     emits the counter block first, then the histogram block;
 *     each segment uses `toSortedMap()` so the output ordering is
 *     deterministic for diff-friendly scrape comparisons.
 *
 * Plus pins:
 *   - Histogram-only registry emits NO bare counter lines (no value
 *     -less name without a `_p` / `_count` suffix).
 *   - Counter-only registry emits NO `_p50` / `_p95` / `_p99` lines.
 *   - Empty registry (counters AND histograms both empty) → empty
 *     body — already covered by sibling `MetricsEndpointTest`, NOT
 *     duplicated here.
 *
 * Isolation: each test stands up its own `ServerContainer(rawEnv =
 * emptyMap())` and writes directly to `container.metrics` — no
 * project store / file-bundle plumbing involved. The tests bypass
 * the EventBusMetricsSink by manipulating the registry directly,
 * which lets us exercise the route formatter in isolation from the
 * sink-attached path covered by `MetricsEndpointTest`.
 */
class MetricsRouteHistogramFormatTest {

    @Test fun singleHistogramEmitsCountP50P95P99Lines() = testApplication {
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        // Reset to clear any sink-attached counters from module init.
        container.metrics.reset()
        // Observe a 100ms latency on a tool dispatch histogram.
        container.metrics.observe("tool.export.ms", 100L)

        val body = client.get("/metrics").bodyAsText()
        // Marquee 4-line pin — one observation produces exactly 4 lines.
        assertContains(body, "talevia_tool_export_ms_count 1")
        assertContains(body, "talevia_tool_export_ms_p50 100")
        assertContains(body, "talevia_tool_export_ms_p95 100")
        assertContains(body, "talevia_tool_export_ms_p99 100")
    }

    @Test fun histogramNameDotReplacementUsesUnderscores() = testApplication {
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        container.metrics.reset()
        container.metrics.observe("agent.run.ms", 250L)

        val body = client.get("/metrics").bodyAsText()
        // Pin: every `.` in the histogram name → `_`.
        assertContains(body, "talevia_agent_run_ms_p50 250")
        // Drift to "preserve dots" would break Prometheus scrape parsing.
        assertFalse(
            "agent.run.ms" in body,
            "histogram name MUST have dots replaced with underscores; got: $body",
        )
    }

    @Test fun multipleObservationsBumpCountAndComputePercentiles() = testApplication {
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        container.metrics.reset()
        // 5 observations spanning a latency range so percentiles differ.
        listOf(10L, 20L, 30L, 40L, 50L).forEach {
            container.metrics.observe("tool.dispatch.ms", it)
        }

        val body = client.get("/metrics").bodyAsText()
        assertContains(body, "talevia_tool_dispatch_ms_count 5")
        // Percentiles use the registry's percentile algorithm; we don't
        // pin specific values (those are covered by MetricsRegistryTest)
        // — just that all 4 lines are present and parseable.
        val histLines = body.lines().filter { "tool_dispatch_ms" in it }
        assertEquals(
            4,
            histLines.size,
            "exactly 4 lines per histogram (count + p50 + p95 + p99); got: $histLines",
        )
    }

    @Test fun countersAndHistogramsCoexistInSameResponse() = testApplication {
        // Marquee both-segments-emit pin: a registry with both counters
        // and histograms emits both blocks. The route concats counter
        // lines first, then histogram lines.
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        container.metrics.reset()
        container.metrics.increment("session.created", by = 7L)
        container.metrics.observe("agent.run.ms", 500L)

        val resp = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()

        // Counter present.
        assertContains(body, "talevia_session_created 7")
        // Histogram present (all 4 sub-lines).
        assertContains(body, "talevia_agent_run_ms_count 1")
        assertContains(body, "talevia_agent_run_ms_p50 500")
        assertContains(body, "talevia_agent_run_ms_p95 500")
        assertContains(body, "talevia_agent_run_ms_p99 500")
    }

    @Test fun histogramOnlyRegistryHasNoBareCounterLines() = testApplication {
        // Pin: a histogram-only registry (no counters) does NOT emit
        // any non-suffixed lines that look like counters. Drift to "
        // emit a histogram's base name as a counter line too" would
        // confuse prometheus scrapers.
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        container.metrics.reset()
        container.metrics.observe("tool.foo.ms", 42L)

        val body = client.get("/metrics").bodyAsText()
        val nonEmptyLines = body.lines().filter { it.isNotBlank() }
        // Every line must be a histogram suffix variant — no bare
        // `talevia_tool_foo_ms 42` line.
        assertTrue(
            nonEmptyLines.all { line ->
                line.startsWith("talevia_tool_foo_ms_count ") ||
                    line.startsWith("talevia_tool_foo_ms_p50 ") ||
                    line.startsWith("talevia_tool_foo_ms_p95 ") ||
                    line.startsWith("talevia_tool_foo_ms_p99 ")
            },
            "histogram-only response must NOT emit bare counter-shaped lines; got: $nonEmptyLines",
        )
        assertEquals(4, nonEmptyLines.size, "histogram-only → exactly 4 lines (count + 3 percentiles)")
    }

    @Test fun counterOnlyRegistryHasNoPercentileLines() = testApplication {
        // Pin: a counter-only registry emits NO `_p50` / `_p95` /
        // `_p99` lines. Drift to "always emit synthetic histogram
        // suffixes for counters" would inflate scrape output 5x.
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        container.metrics.reset()
        container.metrics.increment("permission.granted", by = 3L)
        container.metrics.increment("session.deleted", by = 1L)

        val body = client.get("/metrics").bodyAsText()
        assertFalse(
            "_p50" in body,
            "counter-only response MUST NOT contain `_p50` lines; got: $body",
        )
        assertFalse(
            "_p95" in body,
            "counter-only response MUST NOT contain `_p95` lines; got: $body",
        )
        assertFalse(
            "_p99" in body,
            "counter-only response MUST NOT contain `_p99` lines; got: $body",
        )
        assertFalse(
            "_count" in body,
            "counter-only response MUST NOT contain `_count` lines (those are histogram-only); got: $body",
        )
    }

    @Test fun multipleHistogramsAreAlphabeticallySorted() = testApplication {
        // Pin: the route uses `toSortedMap()` on histograms so output
        // ordering is deterministic. A scraper diffing two consecutive
        // scrapes shouldn't see line reordering when the underlying
        // counts are identical. Pin via observe-in-non-alpha-order +
        // assert-alpha-output.
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        container.metrics.reset()
        // Observe in reverse-alpha order.
        container.metrics.observe("zzz.late.ms", 1L)
        container.metrics.observe("aaa.early.ms", 2L)
        container.metrics.observe("mmm.middle.ms", 3L)

        val body = client.get("/metrics").bodyAsText()
        val histBaseLines = body.lines().filter { it.contains("_count ") }
        val orderedNames = histBaseLines.map { it.substringBefore("_count ") }
        assertEquals(
            listOf("talevia_aaa_early_ms", "talevia_mmm_middle_ms", "talevia_zzz_late_ms"),
            orderedNames,
            "histogram lines must appear in alphabetic order regardless of observation order",
        )
    }

    @Test fun multipleCountersAreAlphabeticallySorted() = testApplication {
        // Same sorting pin but for the counter segment.
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        container.metrics.reset()
        container.metrics.increment("zzz.late", by = 1L)
        container.metrics.increment("aaa.early", by = 2L)
        container.metrics.increment("mmm.middle", by = 3L)

        val body = client.get("/metrics").bodyAsText()
        val counterLines = body.lines().filter { it.startsWith("talevia_") && it.split(' ').size == 2 }
        val orderedNames = counterLines.map { it.substringBefore(' ') }
        assertEquals(
            listOf("talevia_aaa_early", "talevia_mmm_middle", "talevia_zzz_late"),
            orderedNames,
            "counter lines must appear in alphabetic order regardless of increment order",
        )
    }

    @Test fun bodyEndsWithNewlineForEveryEmittedLine() = testApplication {
        // Pin: each line appended with `\n`. A line that doesn't end
        // in newline would join with the next line, breaking
        // line-oriented scrape parsing. Drift catcher.
        val container = ServerContainer(rawEnv = emptyMap())
        application { serverModule(container) }
        container.metrics.reset()
        container.metrics.increment("session.created", by = 1L)
        container.metrics.observe("agent.run.ms", 100L)

        val body = client.get("/metrics").bodyAsText()
        // 1 counter line + 4 histogram lines = 5 total newlines.
        assertEquals(
            5,
            body.count { it == '\n' },
            "every line must be newline-terminated (1 counter + 4 histogram = 5 newlines); got: $body",
        )
    }
}
