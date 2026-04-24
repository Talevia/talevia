package io.talevia.cli.repl

import io.talevia.core.metrics.HistogramStats
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-function coverage for [formatMetricsSummary]. ANSI escapes are
 * disabled via [Styles.setEnabled] so assertions read the plain-text
 * payload without regex-stripping.
 */
class MetricsSummaryFormatterTest {

    @BeforeTest fun disableAnsi() {
        Styles.setEnabled(false)
    }

    @Test fun emptySnapshotEmitsSentinel() {
        val out = formatMetricsSummary(counters = emptyMap(), histograms = emptyMap())
        assertEquals("no metrics recorded yet (counters + histograms both empty)", out)
    }

    @Test fun countersGroupedByFirstDottedSegment() {
        val counters = mapOf(
            "agent.run.failed" to 2L,
            "agent.retry.overload" to 4L,
            "aigc.cost.recorded" to 7L,
            "session.created" to 1L,
        )
        val out = formatMetricsSummary(counters = counters, histograms = emptyMap())
        val lines = out.lines()
        // Header.
        assertTrue(lines[0].startsWith("counters (4 total)"), "header count: <${lines[0]}>")
        // Groups alphabetical by prefix.
        val agentIdx = lines.indexOfFirst { it.trim() == "agent" }
        val aigcIdx = lines.indexOfFirst { it.trim() == "aigc" }
        val sessionIdx = lines.indexOfFirst { it.trim() == "session" }
        assertTrue(agentIdx in lines.indices && aigcIdx > agentIdx && sessionIdx > aigcIdx, out)
        // Each counter renders its name + value.
        assertTrue("agent.run.failed" in out && "  2" in out, out)
        assertTrue("agent.retry.overload" in out && "  4" in out, out)
        assertTrue("aigc.cost.recorded" in out && "  7" in out, out)
    }

    @Test fun histogramsReportP50P95P99AndCount() {
        val hist = mapOf(
            "tool.generate_image.ms" to HistogramStats(count = 42, p50 = 120, p95 = 380, p99 = 610),
            "agent.run.ms" to HistogramStats(count = 5, p50 = 45, p95 = 60, p99 = 72),
        )
        val out = formatMetricsSummary(counters = emptyMap(), histograms = hist)
        assertTrue("histograms (2 total" in out, out)
        assertTrue("tool.generate_image.ms" in out && "n=42" in out && "p50=120" in out && "p99=610" in out, out)
        assertTrue("agent.run.ms" in out && "n=5" in out && "p50=45" in out && "p99=72" in out, out)
    }

    @Test fun countersAndHistogramsBothRenderedWithBlankSeparator() {
        val counters = mapOf("session.created" to 3L)
        val hist = mapOf("agent.run.ms" to HistogramStats(count = 1, p50 = 10, p95 = 10, p99 = 10))
        val out = formatMetricsSummary(counters, hist)
        val lines = out.lines()
        val countersHeaderIdx = lines.indexOfFirst { it.startsWith("counters") }
        val histHeaderIdx = lines.indexOfFirst { it.startsWith("histograms") }
        assertTrue(countersHeaderIdx >= 0 && histHeaderIdx > countersHeaderIdx, out)
        assertTrue(
            lines.subList(countersHeaderIdx, histHeaderIdx).any { it.isBlank() },
            "a blank line must separate the two sections: <$out>",
        )
    }

    @Test fun counterWithoutDottedPrefixGroupsUnderItself() {
        val counters = mapOf("lonely_counter" to 99L)
        val out = formatMetricsSummary(counters, emptyMap())
        assertTrue("lonely_counter" in out && "  99" in out, out)
    }
}
