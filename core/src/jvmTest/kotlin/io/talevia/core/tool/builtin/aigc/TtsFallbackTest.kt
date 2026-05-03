package io.talevia.core.tool.builtin.aigc

import io.talevia.core.bus.BusEvent
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.platform.SynthesizedAudio
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.TtsRequest
import io.talevia.core.platform.TtsResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Direct tests for [synthesizeWithFallback] + [synthesizeStreamingWithFallback]
 * — the priority-ordered TTS engine fallback chain. Cycle 102 audit:
 * 108 LOC, **zero** transitive test references; the helpers were extracted
 * from `AigcSpeechGenerator` specifically for test reach (per kdoc) but
 * the testing never landed.
 *
 * Three correctness contracts a regression could silently break:
 *
 * 1. **CancellationException MUST propagate, not be folded into failures.**
 *    The catch block tests for `CancellationException` and rethrows. A
 *    regression that catches all Throwables uniformly would swallow the
 *    cancellation signal and cause the supervising coroutine to keep
 *    running engines past the cancel point. This is the most subtle
 *    failure mode: tests pass with non-cancel exceptions, the regression
 *    hides until a real cancel arrives.
 *
 * 2. **Failed engine providerIds appear in the final error.** Without
 *    this, a "ElevenLabs failed" surface message could hide that the
 *    primary OpenAI engine also failed — operators chase the wrong lead.
 *    Pinned by `allFailErrorEnumeratesEveryAttemptByProviderId`.
 *
 * 3. **Warmup-event ordering on a fallback chain** — Engine A fires
 *    `Starting` then throws (Ready never fires); Engine B fires
 *    `Starting` then `Ready`. The kdoc explicitly commits to this
 *    sequence; ProviderWarmupStats matches Starting↔Ready by
 *    (providerId, sessionId), so A's orphan Starting silently drops
 *    while B's pair contributes a sample. A regression that suppressed
 *    Starting on the failing engine OR fired Ready on a failed engine
 *    would corrupt the latency dashboard.
 */
class TtsFallbackTest {

    private val request = TtsRequest(text = "hello", modelId = "tts-1", voice = "alloy")

    private fun resultFor(providerId: String): TtsResult = TtsResult(
        audio = SynthesizedAudio(audioBytes = "audio-$providerId".toByteArray(), format = "mp3"),
        provenance = GenerationProvenance(
            providerId = providerId,
            modelId = "tts-1",
            modelVersion = null,
            seed = 0,
            parameters = JsonObject(emptyMap()),
            createdAtEpochMs = 0L,
        ),
    )

    /**
     * Test fake — engine that fires Starting before its body, then either
     * succeeds (also firing Ready) or throws via [bodyFn]. Captures the
     * onChunk callback for streaming variant tests.
     */
    private class FakeEngine(
        override val providerId: String,
        private val streamingChunks: List<ByteArray> = emptyList(),
        private val bodyFn: suspend () -> TtsResult,
    ) : TtsEngine {
        override suspend fun synthesize(request: TtsRequest): TtsResult = bodyFn()

        override suspend fun synthesize(
            request: TtsRequest,
            onWarmup: suspend (BusEvent.ProviderWarmup.Phase) -> Unit,
        ): TtsResult {
            onWarmup(BusEvent.ProviderWarmup.Phase.Starting)
            val result = bodyFn()
            onWarmup(BusEvent.ProviderWarmup.Phase.Ready)
            return result
        }

        override suspend fun synthesizeStreaming(
            request: TtsRequest,
            onChunk: suspend (ByteArray) -> Unit,
            onWarmup: suspend (BusEvent.ProviderWarmup.Phase) -> Unit,
        ): TtsResult {
            onWarmup(BusEvent.ProviderWarmup.Phase.Starting)
            val result = bodyFn()
            // Successful path streams chunks before declaring Ready.
            for (chunk in streamingChunks) onChunk(chunk)
            onWarmup(BusEvent.ProviderWarmup.Phase.Ready)
            return result
        }
    }

    // ── synthesizeWithFallback ──────────────────────────────────

    @Test fun firstEngineSucceedsReturnsItsResultWithoutFallback() = runTest {
        val a = FakeEngine("openai") { resultFor("openai") }
        val b = FakeEngine("eleven") { fail("must not reach engine B when A succeeds") }

        val warmups = mutableListOf<Pair<BusEvent.ProviderWarmup.Phase, String>>()
        val result = synthesizeWithFallback(
            engines = listOf(a, b),
            request = request,
            onWarmup = { phase, id -> warmups += phase to id },
        )

        assertEquals("openai", result.provenance.providerId)
        // A fired Starting + Ready; B never invoked.
        assertEquals(
            listOf(
                BusEvent.ProviderWarmup.Phase.Starting to "openai",
                BusEvent.ProviderWarmup.Phase.Ready to "openai",
            ),
            warmups,
        )
    }

    @Test fun firstFailsSecondSucceedsFiresStartingForBothReadyForSecondOnly() = runTest {
        val a = FakeEngine("openai") { error("openai overloaded") }
        val b = FakeEngine("eleven") { resultFor("eleven") }

        val warmups = mutableListOf<Pair<BusEvent.ProviderWarmup.Phase, String>>()
        val result = synthesizeWithFallback(
            engines = listOf(a, b),
            request = request,
            onWarmup = { phase, id -> warmups += phase to id },
        )

        assertEquals("eleven", result.provenance.providerId)
        // Pin the kdoc-promised sequence: A_Starting, B_Starting, B_Ready.
        // A's orphan Starting is the deliberate signal that
        // ProviderWarmupStats's pairing-by-(providerId, sessionId)
        // silently drops — no Ready means no sample contributed.
        assertEquals(
            listOf(
                BusEvent.ProviderWarmup.Phase.Starting to "openai",
                BusEvent.ProviderWarmup.Phase.Starting to "eleven",
                BusEvent.ProviderWarmup.Phase.Ready to "eleven",
            ),
            warmups,
        )
    }

    @Test fun allFailErrorEnumeratesEveryAttemptByProviderId() = runTest {
        val a = FakeEngine("openai") { error("openai HTTP 503") }
        val b = FakeEngine("eleven") { error("eleven rate limited") }
        val c = FakeEngine("custom") { error("custom timeout") }

        val ex = assertFailsWith<IllegalStateException> {
            synthesizeWithFallback(engines = listOf(a, b, c), request = request)
        }
        val msg = ex.message ?: ""
        // Pin: every provider id appears so an operator chasing
        // the failure sees the full chain, not just the last one.
        assertTrue("openai" in msg, "openai must appear in error; got: $msg")
        assertTrue("eleven" in msg, "eleven must appear; got: $msg")
        assertTrue("custom" in msg, "custom must appear; got: $msg")
        // Each engine's underlying message is included after its provider id.
        assertTrue("HTTP 503" in msg, "openai's exception detail must appear; got: $msg")
        assertTrue("rate limited" in msg, "eleven's detail must appear; got: $msg")
        assertTrue("timeout" in msg, "custom's detail must appear; got: $msg")
        // Header includes the count + request shape so the message
        // is greppable in production logs.
        assertTrue("3 TTS engine(s) failed" in msg, "header must include count; got: $msg")
        assertTrue("model='tts-1'" in msg, "header must include modelId; got: $msg")
        assertTrue("voice='alloy'" in msg, "header must include voice; got: $msg")
    }

    @Test fun cancellationExceptionPropagatesNotSwallowed() = runTest {
        // Pin: CancellationException must NOT be folded into failures.
        // It's how supervisor-cancel propagates; swallowing it would
        // keep running engines past the cancel point, defeating
        // structured concurrency.
        val a = FakeEngine("openai") {
            throw CancellationException("user pressed Ctrl-C")
        }
        val b = FakeEngine("eleven") { fail("must not reach engine B after cancel") }

        assertFailsWith<CancellationException> {
            synthesizeWithFallback(engines = listOf(a, b), request = request)
        }
    }

    @Test fun cancellationExceptionPropagatesEvenFromFinalEngine() = runTest {
        // A regression that catches the cancel only from the FIRST
        // engine but folds it from later engines would also be silent.
        // Test the second engine's cancel separately.
        val a = FakeEngine("openai") { error("openai down") }
        val b = FakeEngine("eleven") {
            throw CancellationException("cancelled mid-fallback")
        }

        assertFailsWith<CancellationException> {
            synthesizeWithFallback(engines = listOf(a, b), request = request)
        }
    }

    @Test fun emptyEngineListErrorsImmediately() = runTest {
        // Pin observed behaviour: empty list → "All 0 TTS engine(s)
        // failed" with empty attempts. Prevents silent success on
        // misconfiguration (no engines wired = should fail loud, not
        // hang or return empty result).
        val ex = assertFailsWith<IllegalStateException> {
            synthesizeWithFallback(engines = emptyList(), request = request)
        }
        val msg = ex.message ?: ""
        assertTrue("0 TTS engine(s) failed" in msg, "got: $msg")
    }

    @Test fun singleEngineSuccessIsSimplePassThrough() = runTest {
        // Single-engine list degenerates cleanly per the kdoc.
        val a = FakeEngine("openai") { resultFor("openai") }
        val result = synthesizeWithFallback(engines = listOf(a), request = request)
        assertEquals("openai", result.provenance.providerId)
    }

    @Test fun singleEngineFailureSurfacesCleanly() = runTest {
        val a = FakeEngine("openai") { error("openai is down") }
        val ex = assertFailsWith<IllegalStateException> {
            synthesizeWithFallback(engines = listOf(a), request = request)
        }
        val msg = ex.message ?: ""
        assertTrue("openai" in msg, "got: $msg")
        assertTrue("openai is down" in msg, "got: $msg")
        assertTrue("1 TTS engine(s) failed" in msg, "got: $msg")
    }

    @Test fun engineExceptionWithoutMessageFallsBackToClassName() = runTest {
        // Pin: `t.message ?: t::class.simpleName` — if the throwable
        // has no message, the simple class name appears in the
        // attempts list. Prevents an empty " " gap in error output.
        val a = FakeEngine("openai") { throw RuntimeException() }
        val ex = assertFailsWith<IllegalStateException> {
            synthesizeWithFallback(engines = listOf(a), request = request)
        }
        val msg = ex.message ?: ""
        assertTrue("RuntimeException" in msg, "fallback class name must appear; got: $msg")
    }

    // ── synthesizeStreamingWithFallback ──────────────────────────

    @Test fun streamingFirstEngineSucceedsForwardsChunks() = runTest {
        val chunks = listOf("c1".toByteArray(), "c2".toByteArray(), "c3".toByteArray())
        val a = FakeEngine("openai", streamingChunks = chunks) { resultFor("openai") }
        val b = FakeEngine("eleven") { fail("must not reach") }

        val received = mutableListOf<String>()
        val result = synthesizeStreamingWithFallback(
            engines = listOf(a, b),
            request = request,
            onChunk = { bytes -> received += bytes.decodeToString() },
        )

        assertEquals("openai", result.provenance.providerId)
        assertEquals(listOf("c1", "c2", "c3"), received, "all chunks from successful engine forwarded")
    }

    @Test fun streamingFirstFailsSecondSucceedsForwardsOnlySecondsChunks() = runTest {
        // Per the kdoc fallback semantics: a failed engine's chunks
        // CANNOT be rolled back (already left the function). FakeEngine
        // here throws BEFORE emitting chunks (the bodyFn() is called
        // before the chunk loop), so onChunk only receives B's chunks.
        // This matches what real non-streaming engines do: synthesize
        // returns a single blob via the default impl AFTER the
        // underlying call, so failure → zero chunks.
        val a = FakeEngine("openai", streamingChunks = listOf("a-chunk".toByteArray())) { error("openai overloaded") }
        val b = FakeEngine("eleven", streamingChunks = listOf("b1".toByteArray(), "b2".toByteArray())) { resultFor("eleven") }

        val received = mutableListOf<String>()
        val result = synthesizeStreamingWithFallback(
            engines = listOf(a, b),
            request = request,
            onChunk = { bytes -> received += bytes.decodeToString() },
        )

        assertEquals("eleven", result.provenance.providerId)
        assertEquals(listOf("b1", "b2"), received, "only successful engine's chunks should arrive")
    }

    @Test fun streamingAllFailErrorMentionsStreamingPath() = runTest {
        // Pin: streaming variant has its own error message
        // ("streaming text-to-speech request") so logs can
        // distinguish streaming vs non-streaming chain failures.
        val a = FakeEngine("openai") { error("a down") }
        val b = FakeEngine("eleven") { error("b down") }
        val ex = assertFailsWith<IllegalStateException> {
            synthesizeStreamingWithFallback(
                engines = listOf(a, b),
                request = request,
                onChunk = { },
            )
        }
        val msg = ex.message ?: ""
        assertTrue("streaming text-to-speech" in msg, "must indicate streaming; got: $msg")
        assertTrue("openai" in msg && "eleven" in msg, "both providers must appear; got: $msg")
    }

    @Test fun streamingCancellationPropagatesNotSwallowed() = runTest {
        val a = FakeEngine("openai") { throw CancellationException("cancel during streaming") }
        val b = FakeEngine("eleven") { fail("must not reach") }
        assertFailsWith<CancellationException> {
            synthesizeStreamingWithFallback(
                engines = listOf(a, b),
                request = request,
                onChunk = { },
            )
        }
    }

    @Test fun streamingWarmupSequenceMatchesNonStreaming() = runTest {
        val a = FakeEngine("openai") { error("a down") }
        val b = FakeEngine("eleven", streamingChunks = listOf("c1".toByteArray())) { resultFor("eleven") }

        val warmups = mutableListOf<Pair<BusEvent.ProviderWarmup.Phase, String>>()
        synthesizeStreamingWithFallback(
            engines = listOf(a, b),
            request = request,
            onChunk = { },
            onWarmup = { phase, id -> warmups += phase to id },
        )
        // Same A-Starting, B-Starting, B-Ready sequence (no Ready for A).
        assertEquals(
            listOf(
                BusEvent.ProviderWarmup.Phase.Starting to "openai",
                BusEvent.ProviderWarmup.Phase.Starting to "eleven",
                BusEvent.ProviderWarmup.Phase.Ready to "eleven",
            ),
            warmups,
        )
    }
}
