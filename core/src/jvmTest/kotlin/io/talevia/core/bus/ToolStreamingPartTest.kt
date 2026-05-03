package io.talevia.core.bus

import app.cash.turbine.test
import io.talevia.core.CallId
import io.talevia.core.SessionId
import io.talevia.core.metrics.EventBusMetricsSink
import io.talevia.core.metrics.MetricsRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `agent-tool-streaming-text-delta` (cycle 35). Pins the new
 * `BusEvent.ToolStreamingPart` event surface + `EventBusMetricsSink`
 * counters. No production emitter ships with this cycle — the bullet
 * deferred the first emitter to a follow-up — so this suite covers
 * the wiring contract:
 *
 *   - the event is constructible with the documented shape,
 *   - publishing through [EventBus] reaches a subscriber unchanged,
 *   - [EventBusMetricsSink] increments both the base counter and
 *     the per-tool tagged counter when the event flows past.
 *
 * The Server SSE arm (`ServerDtos.kt`) is exercised by
 * `apps:server:test` indirectly via the `ServerSseTest` suite as soon
 * as the first emitter lands; for cycle 35 the structural guarantee
 * is "DTO compiles + `from(BusEvent.ToolStreamingPart)` is exhaustive".
 * Compile alone covers that — no separate assertion needed.
 */
class ToolStreamingPartTest {

    @Test fun eventRoundTripsThroughBusUnchanged(): Unit = runBlocking {
        // Use [publishAndAwait] (Turbine pattern from BusEventTestKit) so
        // the subscriber is registered before the publish lands —
        // SharedFlow's no-replay default drops events that fire before
        // the collector starts, which is the historical flake mode for
        // these structural tests.
        val bus = EventBus()
        val event = BusEvent.ToolStreamingPart(
            sessionId = SessionId("s-stream"),
            callId = CallId("c-1"),
            toolId = "draft_script",
            chunk = "Once upon a time",
            doneTokens = 4,
        )
        bus.publishAndAwait<BusEvent.ToolStreamingPart>(
            trigger = { bus.publish(event) },
            assert = { assertEquals(event, it) },
        )
    }

    @Test fun metricsSinkIncrementsBaseCounterAndPerToolCounter(): Unit = runBlocking {
        val bus = EventBus()
        val registry = MetricsRegistry()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        EventBusMetricsSink(bus, registry).attach(scope)

        // Use turbine to await each event landing on the sink before
        // publishing the next one — guarantees deterministic counter
        // state at the assertions below regardless of dispatcher
        // scheduling.
        bus.events.test {
            bus.publish(
                BusEvent.ToolStreamingPart(
                    sessionId = SessionId("s-mx"),
                    callId = CallId("c-1"),
                    toolId = "draft_script",
                    chunk = "alpha",
                ),
            )
            awaitItem()
            bus.publish(
                BusEvent.ToolStreamingPart(
                    sessionId = SessionId("s-mx"),
                    callId = CallId("c-1"),
                    toolId = "draft_script",
                    chunk = "beta",
                ),
            )
            awaitItem()
            bus.publish(
                BusEvent.ToolStreamingPart(
                    sessionId = SessionId("s-mx"),
                    callId = CallId("c-2"),
                    toolId = "synthesize_speech",
                    chunk = "narration",
                ),
            )
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        // The sink runs on its own coroutine (Dispatchers.Default); a
        // small grace delay covers the gap between awaitItem() (turbine
        // saw the event) and the sink's increment running. Without it
        // the counter assertions race the sink coroutine.
        delay(50)

        // Base counter — every streaming event regardless of tool.
        assertEquals(3, registry.get("tool.streaming.part"))
        // Per-tool counter — separated by toolId so dashboards can
        // answer "which tools stream vs. don't" without re-scraping
        // the base counter by label.
        assertEquals(2, registry.get("tool.streaming.draft_script.parts"))
        assertEquals(1, registry.get("tool.streaming.synthesize_speech.parts"))
        // Cross-tool isolation — draft_script's count must not leak
        // into synthesize_speech's bucket.
        assertEquals(0, registry.get("tool.streaming.unrelated_tool.parts"))

        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test fun doneTokensIsOptionalSoAdHocToolsCanEmitWithoutCount() {
        // Compile-time + runtime evidence that the field is nullable
        // — ad-hoc tools (no provider-side token count, no estimate
        // available) MUST be able to publish without a placeholder.
        // If a refactor accidentally tightens the contract to
        // non-null, this test breaks and forces a discussion.
        val event = BusEvent.ToolStreamingPart(
            sessionId = SessionId("s"),
            callId = CallId("c"),
            toolId = "ad_hoc",
            chunk = "x",
            // doneTokens omitted intentionally
        )
        assertEquals(null, event.doneTokens)
    }
}
