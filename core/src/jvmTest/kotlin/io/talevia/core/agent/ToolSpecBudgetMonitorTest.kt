package io.talevia.core.agent

import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import io.talevia.core.tool.builtin.TodoWriteTool
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
 */
class ToolSpecBudgetMonitorTest {

    private fun registryWith(vararg tools: io.talevia.core.tool.Tool<*, *>): ToolRegistry {
        val r = ToolRegistry()
        tools.forEach { r.register(it) }
        return r
    }

    @Test fun firstCheckOverThresholdEmits() {
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

    @Test fun consecutiveOverChecksDoNotSpam() {
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

    @Test fun underThresholdNeverEmits() {
        // Registry exists but threshold is set high enough that even the
        // realistic specs are well under it. Monitor stays silent.
        val monitor = ToolSpecBudgetMonitor(threshold = 1_000_000)
        val registry = registryWith(EchoTool(), TodoWriteTool())
        repeat(5) { assertNull(monitor.check(registry)) }
    }

    @Test fun crossDownThenUpEmitsAgain() {
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

    @Test fun nullRegistryEmitsNothing() {
        // Composition roots that don't wire a registry get a silent
        // monitor — the audit test catches the zero-spec regression
        // separately, so the runtime path stays no-op.
        val monitor = ToolSpecBudgetMonitor(threshold = 1)
        repeat(3) { assertNull(monitor.check(null)) }
    }

    @Test fun emptyRegistryWithLowThresholdDoesNotEmit() {
        // Edge: zero tools → 0 tokens. 0 > 1 is false, so under-threshold
        // by definition. Monitor must not fire on a deliberately empty
        // registry (e.g. a test rig with the budget feature compiled out).
        val monitor = ToolSpecBudgetMonitor(threshold = 1)
        repeat(3) { assertNull(monitor.check(ToolRegistry())) }
    }
}
