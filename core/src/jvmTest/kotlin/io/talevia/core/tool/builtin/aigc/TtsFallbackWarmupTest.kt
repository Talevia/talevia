package io.talevia.core.tool.builtin.aigc

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.SynthesizedAudio
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.TtsRequest
import io.talevia.core.platform.TtsResult
import io.talevia.core.provider.ProviderWarmupStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Locks in the provider-chain warmup-parity contract for TTS:
 *  - On a fallback chain `[A (fails), B (ok)]`, each engine receives its
 *    own `(Starting, Ready?)` onWarmup sequence.
 *  - A's orphan `Starting` does NOT produce a sample in
 *    [ProviderWarmupStats] (pending entry without matching Ready is
 *    silently dropped).
 *  - B's matched pair contributes one sample.
 *  - All-engines-fail surfaces through the aggregate error — no warmup
 *    sample is recorded for either engine.
 *
 * Covers `provider-chain-warmup-parity` from the backlog; pairs with the
 * KDoc contract on [synthesizeWithFallback].
 */
class TtsFallbackWarmupTest {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)

    @AfterTest fun teardown() {
        job.cancel()
    }

    private fun request(): TtsRequest = TtsRequest(
        text = "hello",
        modelId = "tts-1",
        voice = "alloy",
    )

    private fun provenance(providerId: String): GenerationProvenance = GenerationProvenance(
        providerId = providerId,
        modelId = "tts-1",
        modelVersion = null,
        seed = 0,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 0,
    )

    /**
     * TtsEngine that fires `Starting` via [onWarmup] then throws before
     * reaching `Ready`. Captures the received phases for assertions.
     */
    private class FailingEngine(override val providerId: String) : TtsEngine {
        val phases: MutableList<BusEvent.ProviderWarmup.Phase> = mutableListOf()
        override suspend fun synthesize(request: TtsRequest): TtsResult =
            error("unused — the fallback path always goes through the onWarmup-taking overload")
        override suspend fun synthesize(
            request: TtsRequest,
            onWarmup: suspend (BusEvent.ProviderWarmup.Phase) -> Unit,
        ): TtsResult {
            onWarmup(BusEvent.ProviderWarmup.Phase.Starting)
            phases += BusEvent.ProviderWarmup.Phase.Starting
            error("simulated provider outage from $providerId")
        }
    }

    /**
     * TtsEngine that fires Starting + Ready normally and returns a canned
     * [TtsResult].
     */
    private class SucceedingEngine(override val providerId: String) : TtsEngine {
        val phases: MutableList<BusEvent.ProviderWarmup.Phase> = mutableListOf()
        override suspend fun synthesize(request: TtsRequest): TtsResult =
            error("unused — test always uses the onWarmup overload")
        override suspend fun synthesize(
            request: TtsRequest,
            onWarmup: suspend (BusEvent.ProviderWarmup.Phase) -> Unit,
        ): TtsResult {
            onWarmup(BusEvent.ProviderWarmup.Phase.Starting)
            phases += BusEvent.ProviderWarmup.Phase.Starting
            onWarmup(BusEvent.ProviderWarmup.Phase.Ready)
            phases += BusEvent.ProviderWarmup.Phase.Ready
            return TtsResult(
                audio = SynthesizedAudio(audioBytes = byteArrayOf(1, 2, 3), format = "mp3"),
                provenance = GenerationProvenance(
                    providerId = providerId,
                    modelId = "tts-1",
                    modelVersion = null,
                    seed = 0,
                    parameters = JsonObject(emptyMap()),
                    createdAtEpochMs = 0,
                ),
            )
        }
    }

    /**
     * Publish [phase] as a ProviderWarmup event on [bus] for [providerId].
     * Matches the exact shape AigcSpeechGenerator emits — same
     * (sessionId, providerId, phase, epochMs) tuple — so
     * ProviderWarmupStats observes what the production path would see.
     */
    private suspend fun publish(
        bus: EventBus,
        sessionId: SessionId,
        providerId: String,
        phase: BusEvent.ProviderWarmup.Phase,
        epochMs: Long,
    ) {
        bus.publish(
            BusEvent.ProviderWarmup(
                sessionId = sessionId,
                providerId = providerId,
                phase = phase,
                epochMs = epochMs,
            ),
        )
    }

    @Test fun fallbackChainFiresEachEnginesOwnStartingThenOnlySuccessorReady() = runTest {
        val a = FailingEngine("anthropic")
        val b = SucceedingEngine("openai")
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        val sid = SessionId("s")
        var epoch = 1_000L

        val result = synthesizeWithFallback(listOf(a, b), request()) { phase, providerId ->
            publish(bus, sid, providerId, phase, epoch)
            epoch += 500
            yield()
        }

        // A received Starting (and threw); B received Starting + Ready.
        assertEquals(listOf(BusEvent.ProviderWarmup.Phase.Starting), a.phases, "A should emit only Starting before throwing")
        assertEquals(
            listOf(BusEvent.ProviderWarmup.Phase.Starting, BusEvent.ProviderWarmup.Phase.Ready),
            b.phases,
            "B should emit the full Starting→Ready pair",
        )

        // Successful result came from B.
        assertEquals("openai", result.provenance.providerId)

        // Drain any trailing event deliveries.
        yield()

        // ProviderWarmupStats must show ONLY B — A's orphan Starting is
        // pending-forever but never a sample. snapshot() excludes pending.
        val snapshot = stats.snapshot()
        assertEquals(1, snapshot.size, "exactly one provider has a matched pair: ${snapshot.keys}")
        assertTrue("openai" in snapshot, "B must be the sampled provider")
        assertEquals(1L, snapshot.getValue("openai").count)
    }

    @Test fun allEnginesFailSurfacesEveryProviderInErrorMessage() = runTest {
        val a = FailingEngine("anthropic")
        val b = FailingEngine("openai")
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        val sid = SessionId("s")

        val ex = assertFailsWith<IllegalStateException> {
            synthesizeWithFallback(listOf(a, b), request()) { phase, providerId ->
                publish(bus, sid, providerId, phase, 0)
            }
        }
        assertTrue("anthropic" in ex.message!!, "error must name A: ${ex.message}")
        assertTrue("openai" in ex.message!!, "error must name B: ${ex.message}")

        yield()
        assertEquals(
            emptyMap(),
            stats.snapshot(),
            "both engines are pending-Starting only; no Ready → no sample.",
        )
    }

    @Test fun singleEngineSuccessYieldsOneSample() = runTest {
        val a = SucceedingEngine("openai")
        val bus = EventBus()
        val stats = ProviderWarmupStats(bus, scope)
        val sid = SessionId("s")

        val result = synthesizeWithFallback(listOf(a), request()) { phase, providerId ->
            publish(
                bus = bus,
                sessionId = sid,
                providerId = providerId,
                phase = phase,
                epochMs = if (phase == BusEvent.ProviderWarmup.Phase.Starting) 1_000 else 1_800,
            )
            yield()
        }
        assertEquals("openai", result.provenance.providerId)
        val snapshot = stats.snapshot()
        assertEquals(mapOf("openai" to 800L), snapshot.mapValues { it.value.latestMs })
    }
}
