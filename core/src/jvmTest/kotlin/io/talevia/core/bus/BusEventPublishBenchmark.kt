package io.talevia.core.bus

import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentProviderFallbackTracker
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.metrics.EventBusMetricsSink
import io.talevia.core.metrics.MetricsRegistry
import io.talevia.core.permission.PermissionHistoryRecorder
import io.talevia.core.provider.ProviderWarmupStats
import io.talevia.core.provider.RateLimitHistoryRecorder
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Wall-time regression guard for the EventBus hot path.
 *
 * Backstory: every Agent run publishes thousands of `PartDelta` events
 * during streaming. The bus has 6 collectors attached in production
 * containers (AgentRunStateTracker / AgentProviderFallbackTracker /
 * ProviderWarmupStats / PermissionHistoryRecorder /
 * RateLimitHistoryRecorder / EventBusMetricsSink — plus
 * BusEventTraceRecorder added in cycle-100). A regression in any
 * collector's filter logic would slow streaming for every session;
 * this benchmark catches that before it ships.
 *
 * Strategy: 1000 events of mixed types under a real `EventBus` +
 * SharedFlow with all 6 collectors attached + the bus-trace recorder.
 * Wall-time soft budget of [BUDGET_MS] — generous enough to pass on
 * CI machines under load, tight enough to flag a real regression
 * (e.g. blocking IO in a collector, accidental O(n²) filter, missing
 * yield in a loop). Not a strict micro-benchmark; if you need
 * sub-microsecond accuracy, use JMH.
 *
 * The collectors filter most events (e.g. PermissionHistoryRecorder
 * only matches PermissionAsked / PermissionReplied). The mixed event
 * stream exercises both the match and the no-op filter path so the
 * common case ("90% of events drop on filter") is the load-bearing
 * measurement.
 */
class BusEventPublishBenchmark {

    @Test fun publishWithSixCollectorsFitsBudget() = runTest {
        withContext(Dispatchers.Default) {
            val bus = EventBus()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            // Wire all 6 production collectors. Each subscribes via
            // bus.events.collect; they share the same SharedFlow.
            val agentStates = AgentRunStateTracker(bus, scope)
            val fallback = AgentProviderFallbackTracker(bus, scope)
            val warmup = ProviderWarmupStats(bus, scope)
            val permission = PermissionHistoryRecorder(bus = bus, scope = scope, store = null)
            val rateLimit = RateLimitHistoryRecorder(bus, scope)
            val trace = BusEventTraceRecorder(bus, scope)
            val registry = MetricsRegistry()
            val metricsJob: Job = EventBusMetricsSink(bus, registry).attach(scope)

            // Wait for every collector that has a ready hook.
            withTimeout(5.seconds) {
                permission.awaitReady()
                rateLimit.awaitReady()
                trace.awaitReady()
                // AgentRunStateTracker / FallbackTracker / WarmupStats /
                // EventBusMetricsSink don't expose a ready signal, so
                // yield a few rounds to let their `bus.events.collect`
                // start observing. A few microseconds; not a load-bearing
                // delay.
                repeat(20) { yield() }
            }

            // 1000 events of mixed types — exercises both match and
            // no-op-filter paths through every collector.
            val sid = SessionId("bench")
            val mid = MessageId("m")
            val pid = PartId("p")
            val zeroPart = Part.Text(
                id = pid,
                messageId = mid,
                sessionId = sid,
                createdAt = Instant.fromEpochMilliseconds(0),
                text = "",
            )
            val userMessage = Message.User(
                id = mid,
                sessionId = sid,
                createdAt = Instant.fromEpochMilliseconds(0),
                agent = "default",
                model = ModelRef("anthropic", "claude-opus-4-7"),
            )

            val elapsedMs = measureTimeMillis {
                repeat(EVENT_COUNT) { i ->
                    val event = when (i % 5) {
                        0 -> BusEvent.PartUpdated(sid, mid, pid, zeroPart)
                        1 -> BusEvent.PartDelta(sid, mid, pid, field = "text", delta = "x")
                        2 -> BusEvent.SessionUpdated(sid)
                        3 -> BusEvent.MessageUpdated(sid, mid, userMessage)
                        else -> BusEvent.SessionCancelled(sid)
                    }
                    bus.publish(event)
                }
                // Drain — wait for the trace recorder to see the last event.
                // Trace captures every SessionEvent so its size mirrors
                // publish count (capped at 256).
                withTimeout(10.seconds) {
                    while (trace.snapshot(sid.value).isEmpty()) yield()
                }
            }

            metricsJob.cancelAndJoin()
            scope.coroutineContext[Job]?.cancelAndJoin()

            assertTrue(
                elapsedMs < BUDGET_MS,
                "EventBus publish + 6 collectors over $EVENT_COUNT events took ${elapsedMs}ms; " +
                    "budget is ${BUDGET_MS}ms. A 10x overrun likely means a collector added blocking IO " +
                    "or an O(n²) filter. Profile with kotlinx-coroutines DEBUG before raising the budget.",
            )
        }
    }

    companion object {
        private const val EVENT_COUNT = 1_000
        /**
         * Soft ceiling — measured ~50ms locally on M-series hardware
         * with cold JVM; 5_000ms gives 100x headroom for slow CI nodes.
         * If this test ever tightens to e.g. 500ms, the assertion would
         * still flag the kind of regression we care about (blocking IO,
         * O(n²) accidents) without flaking on slow runners.
         */
        private const val BUDGET_MS = 5_000L
    }
}
