package io.talevia.core.provider

import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch as kxLaunch

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
            val captured = java.util.concurrent.CopyOnWriteArrayList<BusEvent.ProviderWarmup>()

            // Subscribe before kickoff so we don't miss events.
            val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val collectJob = collectorScope.collectorLaunch(captured, bus)

            kickoffEagerProviderWarmup(
                providers = registry,
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

            withTimeout(5.seconds) {
                while (captured.size < 2) yield()
            }
            collectJob.cancel()

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
            val captured = java.util.concurrent.CopyOnWriteArrayList<BusEvent.ProviderWarmup>()

            val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val collectJob = collectorScope.collectorLaunch(captured, bus)

            kickoffEagerProviderWarmup(
                providers = registry,
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

            // Wait long enough for the listModels failure to settle.
            // Starting still fires before listModels; Ready does NOT.
            withTimeout(5.seconds) {
                while (captured.firstOrNull()?.phase != BusEvent.ProviderWarmup.Phase.Starting) yield()
            }
            // Yield more rounds to give a (non-existent) Ready a chance.
            repeat(10) { yield() }
            collectJob.cancel()

            assertEquals(1, captured.size, "only Starting; failure suppresses Ready")
            assertEquals(BusEvent.ProviderWarmup.Phase.Starting, captured.single().phase)
            assertEquals("openai-fake", captured.single().providerId)
            // The agent loop's normal retry path covers a real first-call;
            // we only verify the kickoff itself didn't crash.
            assertEquals(1, provider.listModelsCallCount)
        }
    }

    @Test fun emptyRegistryEmitsNothing() = runTest {
        val registry = ProviderRegistry(byId = emptyMap(), default = null)
        val bus = EventBus()
        val captured = java.util.concurrent.CopyOnWriteArrayList<BusEvent.ProviderWarmup>()

        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val collectJob = collectorScope.collectorLaunch(captured, bus)

        kickoffEagerProviderWarmup(
            providers = registry,
            bus = bus,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        repeat(20) { yield() }
        collectJob.cancel()

        assertEquals(0, captured.size, "empty registry should fire no events")
    }

    @Test fun twoProvidersWarmInParallelBothEmitReady() = runTest {
        withContext(Dispatchers.Default) {
            val a = FakeProvider("a") { emptyList() }
            val b = FakeProvider("b") { emptyList() }
            val registry = ProviderRegistry(byId = mapOf("a" to a, "b" to b), default = a)
            val bus = EventBus()
            val captured = java.util.concurrent.CopyOnWriteArrayList<BusEvent.ProviderWarmup>()

            val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val collectJob = collectorScope.collectorLaunch(captured, bus)

            kickoffEagerProviderWarmup(
                providers = registry,
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

            withTimeout(5.seconds) {
                while (captured.count { it.phase == BusEvent.ProviderWarmup.Phase.Ready } < 2) yield()
            }
            collectJob.cancel()

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
}

private fun CoroutineScope.collectorLaunch(
    captured: MutableList<BusEvent.ProviderWarmup>,
    bus: EventBus,
): Job = kxLaunch {
    bus.events.filterIsInstance<BusEvent.ProviderWarmup>().collect { captured += it }
}
