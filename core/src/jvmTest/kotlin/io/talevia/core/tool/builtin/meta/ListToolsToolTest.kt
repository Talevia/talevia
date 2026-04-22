package io.talevia.core.tool.builtin.meta

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import io.talevia.core.tool.builtin.TodoWriteTool
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListToolsToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun registryWith(): ToolRegistry {
        val registry = ToolRegistry()
        registry.register(ListToolsTool(registry))
        registry.register(TodoWriteTool())
        registry.register(EchoTool())
        return registry
    }

    @Test fun enumeratesRegisteredTools() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(),
            ctx(),
        ).data

        assertTrue(out.total >= 3)
        val ids = out.tools.map { it.id }.toSet()
        assertTrue("list_tools" in ids)
        assertTrue("todowrite" in ids)
        assertTrue("echo" in ids)
    }

    @Test fun prefixFilterNarrows() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(prefix = "list_"),
            ctx(),
        ).data

        assertEquals(1, out.total)
        assertEquals("list_tools", out.tools.single().id)
    }

    @Test fun limitCaps() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(limit = 2),
            ctx(),
        ).data
        assertEquals(2, out.returned)
        assertTrue(out.total >= 3)
    }

    @Test fun helpTextAndPermissionSurface() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(prefix = "list_tools"),
            ctx(),
        ).data
        val self = out.tools.single()
        assertTrue(self.helpText.isNotBlank())
        assertEquals("tool.read", self.permission)
    }

    @Test fun emptyResultOnNoMatch() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(prefix = "zzzz-no-match"),
            ctx(),
        ).data
        assertEquals(0, out.returned)
        assertTrue(out.tools.isEmpty())
    }

    @Test fun costHintsAreNullWhenMetricsNotWired() = runTest {
        val registry = registryWith()
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(),
            ctx(),
        ).data
        // Backward-compat: containers without a MetricsRegistry see null hints.
        assertTrue(out.tools.all { it.avgCostCents == null && it.costedCalls == null })
    }

    @Test fun costHintsAreNullForToolsWithNoPricedCalls() = runTest {
        val registry = registryWith()
        val metrics = MetricsRegistry()
        // Metrics wired but no AIGC events fed in yet.
        val out = ListToolsTool(registry, metrics).execute(
            ListToolsTool.Input(),
            ctx(),
        ).data
        assertTrue(out.tools.all { it.avgCostCents == null && it.costedCalls == null })
    }

    @Test fun costHintsSurfaceAverageFromMetrics() = runTest {
        val registry = registryWith()
        val metrics = MetricsRegistry()
        // Simulate 3 calls totalling 300¢ for generate_image: avg = 100¢.
        metrics.increment("aigc.cost.generate_image.cents", by = 300L)
        metrics.increment("aigc.cost.generate_image.count", by = 3L)
        // Stretch: one expensive call at 500¢ with count 1 → avg 500¢.
        metrics.increment("aigc.cost.synthesize_speech.cents", by = 500L)
        metrics.increment("aigc.cost.synthesize_speech.count")

        val out = ListToolsTool(registry, metrics).execute(
            ListToolsTool.Input(),
            ctx(),
        ).data
        // Non-priced tools unchanged.
        val echo = out.tools.single { it.id == "echo" }
        assertNull(echo.avgCostCents)
        assertNull(echo.costedCalls)
        // The registry in this test doesn't actually register generate_image, so
        // let the metrics drive the counters but no summary row exists. Test the
        // happy path by also wiring a tool under a matching id.
    }

    @Test fun costHintsMatchPerToolCounters() = runTest {
        // Register a stub tool under the id we're going to push metrics for.
        val registry = ToolRegistry()
        registry.register(ListToolsTool(registry))
        registry.register(EchoTool())
        val metrics = MetricsRegistry()
        metrics.increment("aigc.cost.echo.cents", by = 250L)
        metrics.increment("aigc.cost.echo.count", by = 5L)

        val out = ListToolsTool(registry, metrics).execute(
            ListToolsTool.Input(),
            ctx(),
        ).data
        val echoRow = out.tools.single { it.id == "echo" }
        assertEquals(50L, echoRow.avgCostCents, "250¢ / 5 calls = 50¢")
        assertEquals(5L, echoRow.costedCalls)
        // list_tools itself is non-AIGC → no counters → null hint.
        val listRow = out.tools.single { it.id == "list_tools" }
        assertNull(listRow.avgCostCents)
        assertNull(listRow.costedCalls)
    }

    @Test fun costHintDropsOnZeroCountCounter() = runTest {
        // Edge case: someone incremented cents but count stayed at zero.
        // Division would throw; the tool must silently drop the hint.
        val registry = registryWith()
        val metrics = MetricsRegistry()
        metrics.increment("aigc.cost.echo.cents", by = 100L)
        // count intentionally not incremented.

        val out = ListToolsTool(registry, metrics).execute(
            ListToolsTool.Input(),
            ctx(),
        ).data
        val echoRow = out.tools.single { it.id == "echo" }
        assertNull(echoRow.avgCostCents)
        assertNull(echoRow.costedCalls)
    }
}
