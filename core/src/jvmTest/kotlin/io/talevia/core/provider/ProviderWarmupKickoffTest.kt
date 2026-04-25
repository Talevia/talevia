package io.talevia.core.provider

import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for [kickoffEagerProviderWarmup] — the container-init
 * "pre-warm every LLM provider" helper. Asserts:
 *
 * - Successful provider triggers Starting → Ready event pair on the
 *   bus, both tagged with [EAGER_WARMUP_SESSION_ID].
 * - Provider whose `listModels()` throws does NOT crash the helper
 *   (best-effort) and does NOT publish Ready.
 * - Multi-provider registry kicks off all in parallel.
 */
class ProviderWarmupKickoffTest {

    private class FakeProvider(
        override val id: String,
        private val onListModels: suspend () -> List<ModelInfo>,
    ) : LlmProvider {
        var listModelsCallCount: Int = 0
            private set

        override suspend fun listModels(): List<ModelInfo> {
            listModelsCallCount += 1
            return onListModels()
        }

        override fun stream(request: LlmRequest): Flow<LlmEvent> = emptyFlow()
    }

    @Test fun successfulProviderEmitsStartingThenReady() = runTest {
        withContext(Dispatchers.Default) {
            val provider = FakeProvider("anthropic-fake") { emptyList() }
            val registry = ProviderRegistry(byId = mapOf("anthropic-fake" to provider), default = provider)
            val bus = EventBus()

            // Barrier-based: subscribe with `take(2).toList()` so the
            // collector completes deterministically once 2 events arrive
            // — no busy-yield against `Dispatchers.Default` queue depth.
            // Replaces the cycle-115 yield-loop that flaked at 5s on
            // loaded CI runners (`debt-flaky-provider-warmup-test`).
            val collected = collectWarmupEvents(bus, expected = 2)

            kickoffEagerProviderWarmup(
                providers = registry,
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

            val captured = withTimeout(5.seconds) { collected.await() }

            // Both events fired in order, scoped to the eager session.
            assertEquals(2, captured.size, "expected Starting + Ready")
            assertEquals(BusEvent.ProviderWarmup.Phase.Starting, captured[0].phase)
            assertEquals(BusEvent.ProviderWarmup.Phase.Ready, captured[1].phase)
            assertTrue(captured.all { it.sessionId == EAGER_WARMUP_SESSION_ID })
            assertTrue(captured.all { it.providerId == "anthropic-fake" })
            assertEquals(1, provider.listModelsCallCount)
        }
    }

    @Test fun listModelsFailureSwallowedNoReadyEmitted() = runTest {
        withContext(Dispatchers.Default) {
            val provider = FakeProvider("openai-fake") { error("auth failed") }
            val registry = ProviderRegistry(byId = mapOf("openai-fake" to provider), default = provider)
            val bus = EventBus()

            // Barrier 1: take(1) blocks until Starting arrives.
            val starting = collectWarmupEvents(bus, expected = 1)
            // Barrier 2: try to collect 2 events with a short window —
            // expected to time out (no Ready ever arrives). Bounded
            // negative-evidence wait replaces the yield-10-times probe.
            val phantomReady = collectWarmupEvents(bus, expected = 2)

            kickoffEagerProviderWarmup(
                providers = registry,
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

            val firstPair = withTimeout(5.seconds) { starting.await() }
            // Negative-evidence: 200ms is plenty for a phantom Ready —
            // Starting fires synchronously inside the launched coroutine,
            // then listModels throws immediately on the next dispatch.
            // If Ready was going to fire it would have within 200ms.
            val excessEvents = withTimeoutOrNull(200.milliseconds) { phantomReady.await() }
            assertNull(excessEvents, "Ready must not arrive when listModels throws")
            // Cancel the still-running phantom collector to avoid a
            // background coroutine that outlives this test.
            phantomReady.cancel()

            assertEquals(1, firstPair.size, "only Starting; failure suppresses Ready")
            assertEquals(BusEvent.ProviderWarmup.Phase.Starting, firstPair.single().phase)
            assertEquals("openai-fake", firstPair.single().providerId)
            // The agent loop's normal retry path covers a real first-call;
            // we only verify the kickoff itself didn't crash.
            assertEquals(1, provider.listModelsCallCount)
        }
    }

    @Test fun emptyRegistryEmitsNothing() = runTest {
        val registry = ProviderRegistry(byId = emptyMap(), default = null)
        val bus = EventBus()

        // Negative-evidence test — expect zero events. take(1).toList()
        // would never complete on its own, so wrap in withTimeoutOrNull
        // to get a bounded null on the empty-registry happy path.
        val collected = collectWarmupEvents(bus, expected = 1)

        kickoffEagerProviderWarmup(
            providers = registry,
            bus = bus,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        // 200ms is plenty: kickoff returns synchronously (it just
        // launches per-provider coroutines), and an empty registry
        // launches none. If we don't see an event by then, we won't.
        val phantom = withTimeoutOrNull(200.milliseconds) { collected.await() }
        collected.cancel()
        assertNull(phantom, "empty registry should fire no events")
    }

    @Test fun twoProvidersWarmInParallelBothEmitReady() = runTest {
        withContext(Dispatchers.Default) {
            val a = FakeProvider("a") { emptyList() }
            val b = FakeProvider("b") { emptyList() }
            val registry = ProviderRegistry(byId = mapOf("a" to a, "b" to b), default = a)
            val bus = EventBus()

            // 4 events total: each provider emits Starting + Ready.
            val collected = collectWarmupEvents(bus, expected = 4)

            kickoffEagerProviderWarmup(
                providers = registry,
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

            val captured = withTimeout(5.seconds) { collected.await() }

            // Both providers got a Starting + Ready pair (4 total events).
            val byProvider = captured.groupBy { it.providerId }
            assertEquals(setOf("a", "b"), byProvider.keys)
            for (events in byProvider.values) {
                assertEquals(2, events.size, "each provider should emit exactly 2 events")
                assertTrue(events.any { it.phase == BusEvent.ProviderWarmup.Phase.Starting })
                assertTrue(events.any { it.phase == BusEvent.ProviderWarmup.Phase.Ready })
            }
        }
    }

    /**
     * Subscribe to the bus on a fresh `Dispatchers.Default` scope and
     * collect the first [expected] `ProviderWarmup` events into a list.
     * The returned Deferred completes deterministically once that many
     * events have been seen (or with the partial list if cancelled).
     *
     * Replaces the cycle-115 yield-loop pattern (`while (captured.size
     * < N) yield()`) which raced against `Dispatchers.Default` queue
     * depth on loaded CI runners — see `debt-flaky-provider-warmup-test`.
     * `Flow.take(N).toList()` completes structurally on the Nth emit;
     * no busy polling, no dispatcher dependency.
     *
     * Caller MUST `withTimeout(...)` the await so the test fails loud
     * if the expected events never arrive.
     */
    private fun CoroutineScope.collectWarmupEvents(
        bus: EventBus,
        expected: Int,
    ): Deferred<List<BusEvent.ProviderWarmup>> = async(Dispatchers.Default) {
        bus.events.filterIsInstance<BusEvent.ProviderWarmup>().take(expected).toList()
    }
}
