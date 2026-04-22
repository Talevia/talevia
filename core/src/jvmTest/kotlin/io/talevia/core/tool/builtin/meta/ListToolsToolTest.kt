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

    // ── priceBasis preflight hints ─────────────────────────────────────

    @Test fun priceBasisNullForNonAigcTools() = runTest {
        val registry = registryWith() // only list_tools / todowrite / echo
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(),
            ctx(),
        ).data
        // None of the registered tools are AIGC; every `priceBasis` must be null.
        assertTrue(
            out.tools.all { it.priceBasis == null },
            "Non-AIGC tools must emit null priceBasis; got: ${out.tools.map { it.id to it.priceBasis }}",
        )
    }

    @Test fun priceBasisPopulatedForAigcToolIds() = runTest {
        // Inject a fake tool registered under each priced AIGC id. The test
        // doesn't care about the tool's execute behaviour — only that the
        // ListToolsTool path populates priceBasis for each known id.
        val registry = ToolRegistry()
        registry.register(ListToolsTool(registry))
        registry.register(FakeTool("generate_image"))
        registry.register(FakeTool("synthesize_speech"))
        registry.register(FakeTool("generate_video"))
        registry.register(FakeTool("generate_music"))
        registry.register(FakeTool("upscale_asset"))

        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(),
            ctx(),
        ).data

        val byId = out.tools.associateBy { it.id }
        val priced = listOf(
            "generate_image",
            "synthesize_speech",
            "generate_video",
            "generate_music",
            "upscale_asset",
        )
        for (id in priced) {
            val row = byId[id]
            assertTrue(row != null, "registered tool $id must surface in list_tools")
            assertTrue(
                row!!.priceBasis != null,
                "priced tool $id must carry a non-null priceBasis; got: ${row.priceBasis}",
            )
            assertTrue(row.priceBasis!!.isNotBlank(), "priceBasis for $id must be non-blank")
        }
        // list_tools itself must NOT get a priceBasis — it's the enumerator, not an AIGC tool.
        assertNull(byId["list_tools"]?.priceBasis)
    }

    @Test fun priceBasisForwardsExactAigcPricingText() = runTest {
        // Guard against the wiring accidentally translating / trimming the
        // basis string — the LLM needs the text verbatim to reason about
        // per-input scaling (e.g. "$/sq vs $/rect" tells it to pass
        // square dimensions when possible).
        val registry = ToolRegistry()
        registry.register(ListToolsTool(registry))
        registry.register(FakeTool("generate_image"))
        val out = ListToolsTool(registry).execute(
            ListToolsTool.Input(prefix = "generate_image"),
            ctx(),
        ).data
        val row = out.tools.single()
        assertEquals(
            io.talevia.core.cost.AigcPricing.priceBasisFor("generate_image"),
            row.priceBasis,
            "priceBasis must forward AigcPricing text verbatim",
        )
    }

    /** Minimal Tool stub — only its id is inspected by ListToolsTool. */
    private class FakeTool(override val id: String) :
        io.talevia.core.tool.Tool<FakeTool.In, FakeTool.Out> {
        @kotlinx.serialization.Serializable data class In(val x: Int = 0)
        @kotlinx.serialization.Serializable data class Out(val y: Int = 0)
        override val helpText: String = "fake $id"
        override val inputSerializer = kotlinx.serialization.serializer<In>()
        override val outputSerializer = kotlinx.serialization.serializer<Out>()
        override val permission =
            io.talevia.core.permission.PermissionSpec.fixed("fake.$id")
        override val inputSchema: kotlinx.serialization.json.JsonObject =
            kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            }

        override suspend fun execute(input: In, ctx: ToolContext): io.talevia.core.tool.ToolResult<Out> =
            io.talevia.core.tool.ToolResult(title = id, outputForLlm = "", data = Out())
    }
}
