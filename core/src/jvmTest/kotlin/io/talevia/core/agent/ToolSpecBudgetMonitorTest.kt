package io.talevia.core.agent

import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import io.talevia.core.tool.builtin.TodoWriteTool
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pin VISION §5.7 / §3a-10 tool-spec budget runtime warning behaviour:
 * the monitor must fire `BusEvent.ToolSpecBudgetWarning` exactly once per
 * upward threshold crossing, and stay silent while the registry remains
 * over (no spam) or stays under (no false positive). Reproducing the
 * crossing in a unit test would otherwise require either a registry of
 * ~60 real tools (slow + brittle) or fragile token-count assertions; we
 * pass a low [ToolSpecBudgetMonitor.threshold] instead and let any real
 * tool's spec push us over, then verify each edge.
 *
 * Cycle 50 (`perf-tool-spec-budget-runtime-metric`) added a metrics
 * sink: when wired, every `check` writes the absolute
 * `agent.tool_spec_budget.tokens` and `agent.tool_spec_budget.tools`
 * gauges so a Prometheus scrape captures continuous time series rather
 * than waiting for the next threshold crossing event.
 */
class ToolSpecBudgetMonitorTest {

    private fun registryWith(vararg tools: io.talevia.core.tool.Tool<*, *>): ToolRegistry {
        val r = ToolRegistry()
        tools.forEach { r.register(it) }
        return r
    }

    @Test fun firstCheckOverThresholdEmits(): Unit = runBlocking {
        // Two real tools easily exceed `1` token of spec budget.
        val monitor = ToolSpecBudgetMonitor(threshold = 1)
        val event = monitor.check(registryWith(EchoTool(), TodoWriteTool()))
        assertNotNull(event, "first over-threshold check must emit")
        assertEquals(1, event.threshold)
        assertEquals(2, event.toolCount)
        assertTrue(
            event.estimatedTokens > event.threshold,
            "event must report tokens > threshold; got ${event.estimatedTokens}",
        )
        assertEquals(event.estimatedTokens - event.threshold, event.exceededBy)
    }

    @Test fun consecutiveOverChecksDoNotSpam(): Unit = runBlocking {
        // The headline hysteresis case: a registry that stays at 19k must
        // not warn every turn; only the under→over crossing is actionable.
        val monitor = ToolSpecBudgetMonitor(threshold = 1)
        val registry = registryWith(EchoTool(), TodoWriteTool())
        val first = monitor.check(registry)
        val second = monitor.check(registry)
        val third = monitor.check(registry)
        assertNotNull(first)
        assertNull(second, "second check while still over must not emit")
        assertNull(third, "third check while still over must not emit")
    }

    @Test fun underThresholdNeverEmits(): Unit = runBlocking {
        // Registry exists but threshold is set high enough that even the
        // realistic specs are well under it. Monitor stays silent.
        val monitor = ToolSpecBudgetMonitor(threshold = 1_000_000)
        val registry = registryWith(EchoTool(), TodoWriteTool())
        repeat(5) { assertNull(monitor.check(registry)) }
    }

    @Test fun crossDownThenUpEmitsAgain(): Unit = runBlocking {
        // Build a registry that crosses both directions: start under
        // (empty registry → 0 tokens, threshold 1 means under since 0 ≤ 1
        // — actually 0 > 1 is false, so under), then add tools to cross up.
        val monitor = ToolSpecBudgetMonitor(threshold = 1)
        val emptyRegistry = ToolRegistry()
        // Under-threshold: empty registry has 0 estimated tokens → no event.
        assertNull(monitor.check(emptyRegistry))

        val full = registryWith(EchoTool(), TodoWriteTool())
        val firstUp = monitor.check(full)
        assertNotNull(firstUp, "transition under → over must emit")

        // Drop back under: empty registry resets the flag silently.
        assertNull(monitor.check(emptyRegistry))

        // Cross up again: should emit fresh — the down-then-up edge is a
        // distinct crossing, not a continuation of the prior over-state.
        val secondUp = monitor.check(full)
        assertNotNull(secondUp, "second under → over crossing must emit again")
    }

    @Test fun nullRegistryEmitsNothing(): Unit = runBlocking {
        // Composition roots that don't wire a registry get a silent
        // monitor — the audit test catches the zero-spec regression
        // separately, so the runtime path stays no-op.
        val monitor = ToolSpecBudgetMonitor(threshold = 1)
        repeat(3) { assertNull(monitor.check(null)) }
    }

    @Test fun emptyRegistryWithLowThresholdDoesNotEmit(): Unit = runBlocking {
        // Edge: zero tools → 0 tokens. 0 > 1 is false, so under-threshold
        // by definition. Monitor must not fire on a deliberately empty
        // registry (e.g. a test rig with the budget feature compiled out).
        val monitor = ToolSpecBudgetMonitor(threshold = 1)
        repeat(3) { assertNull(monitor.check(ToolRegistry())) }
    }

    @Test fun metricsSinkReceivesGaugeOnEveryCheckIncludingUnderThreshold(): Unit = runBlocking {
        // `perf-tool-spec-budget-runtime-metric` (cycle 50) contract: the
        // gauge must be written EVERY check, not just on threshold-
        // crossings. This is what makes the Prometheus scrape useful as
        // a continuous time series — a registry that stays at 14k tokens
        // for an hour should produce a flat 14k gauge for that hour, not
        // a single point at the last threshold crossing.
        val metrics = MetricsRegistry()
        val monitor = ToolSpecBudgetMonitor(threshold = 1_000_000, metrics = metrics)
        val registry = registryWith(EchoTool(), TodoWriteTool())

        // First check: gauge populated even though the monitor emits no event.
        assertNull(monitor.check(registry), "well under threshold; no event")
        val tokensGauge = metrics.get("agent.tool_spec_budget.tokens")
        val toolsGauge = metrics.get("agent.tool_spec_budget.tools")
        assertTrue(tokensGauge > 0, "tokens gauge should be set; got $tokensGauge")
        assertEquals(2L, toolsGauge, "tools gauge should equal registered tool count")

        // Second check: gauge value is overwritten (not accumulated). The
        // registry didn't change so the value is the same — but the
        // important property is the SET semantic vs INCREMENT.
        monitor.check(registry)
        assertEquals(tokensGauge, metrics.get("agent.tool_spec_budget.tokens"), "gauge must be set, not incremented")
        assertEquals(2L, metrics.get("agent.tool_spec_budget.tools"))
    }

    @Test fun metricsSinkUpdatesGaugeWhenRegistryShrinks(): Unit = runBlocking {
        // The reverse-direction case: registry can grow OR shrink. The
        // gauge must follow either direction since absolute-value
        // semantics is what makes the metric a gauge rather than a
        // counter. A `set`-based write naturally handles this; an
        // `increment`-based write would silently lose the shrink.
        val metrics = MetricsRegistry()
        val monitor = ToolSpecBudgetMonitor(threshold = 1_000_000, metrics = metrics)
        val full = registryWith(EchoTool(), TodoWriteTool())
        val empty = ToolRegistry()

        monitor.check(full)
        val tokensWithFull = metrics.get("agent.tool_spec_budget.tokens")
        assertTrue(tokensWithFull > 0)

        monitor.check(empty)
        assertEquals(0L, metrics.get("agent.tool_spec_budget.tokens"), "empty registry → gauge drops to 0")
        assertEquals(0L, metrics.get("agent.tool_spec_budget.tools"))
    }

    @Test fun nullMetricsSinkSkipsGaugeWritesGracefully(): Unit = runBlocking {
        // Default constructor (no metrics arg) keeps the existing
        // event-only behaviour for tests / minimal wirings that don't
        // care about the gauge. The check still emits the warning when
        // the threshold is crossed.
        val monitor = ToolSpecBudgetMonitor(threshold = 1) // implicit metrics = null
        val registry = registryWith(EchoTool(), TodoWriteTool())
        val event = monitor.check(registry)
        assertNotNull(event, "warning event still fires when sink is null")
        // No gauge to inspect — the test passes by the absence of NPE
        // when the sink is unset.
    }
}
