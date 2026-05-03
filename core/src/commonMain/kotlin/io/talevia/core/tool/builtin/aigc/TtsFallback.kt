package io.talevia.core.tool.builtin.aigc

import io.talevia.core.bus.BusEvent
import io.talevia.core.platform.TtsEngine
import io.talevia.core.platform.TtsRequest
import io.talevia.core.platform.TtsResult

/**
 * Try [engines] in priority order, returning the first successful
 * [TtsResult]. Each engine's failure is remembered so the final exception
 * enumerates every attempt — a mis-wired "OpenAI then ElevenLabs" chain
 * shouldn't surface as "ElevenLabs failed" while hiding that OpenAI
 * failed too. Single-engine lists degenerate cleanly: one try, one
 * failure propagated verbatim.
 *
 * ## Warmup-event contract
 *
 * Each engine independently fires its own `ProviderWarmup(Starting|Ready)`
 * pair through the [onWarmup] callback — the caller supplies a closure
 * that translates `(phase, providerId)` into a
 * [BusEvent.ProviderWarmup] publish. On a fallback chain:
 *  - Engine A fires `Starting`, then throws (`Ready` never fires).
 *  - Engine B fires `Starting`, then `Ready` on success.
 *
 * The resulting bus sequence is `(A, Starting), (B, Starting), (B, Ready)`.
 * `ProviderWarmupStats` matches Starting↔Ready by `(providerId, sessionId)`:
 * A's orphan `Starting` is silently dropped (no sample pushed), B's
 * matched pair contributes one sample. Observers see only the
 * **successful** provider's cold-start latency — the fallback chain's
 * failed attempts don't pollute the provider's P50/P99.
 *
 * Extracted from `AigcSpeechGenerator` for test reach (the old private
 * method couldn't be exercised without constructing the full tool plus
 * a fake session / project / bundle writer). The fallback pattern is
 * behaviour a future image / video engine chain may want to reuse; the
 * top-level helper is a deliberate affordance.
 */
internal suspend fun synthesizeWithFallback(
    engines: List<TtsEngine>,
    request: TtsRequest,
    onWarmup: suspend (BusEvent.ProviderWarmup.Phase, String) -> Unit = { _, _ -> },
): TtsResult {
    val failures = mutableListOf<Pair<String, Throwable>>()
    for (engine in engines) {
        try {
            return engine.synthesize(request) { phase -> onWarmup(phase, engine.providerId) }
        } catch (t: Throwable) {
            // CancellationException should not be swallowed — it's how a
            // supervising coroutine signals "stop this work". Propagate
            // immediately so the progress watcher's cancel path fires.
            if (t is kotlinx.coroutines.CancellationException) throw t
            failures += engine.providerId to t
        }
    }
    val attempted = failures.joinToString("; ") { (id, t) -> "$id: ${t.message ?: t::class.simpleName}" }
    error(
        "All ${engines.size} TTS engine(s) failed for text-to-speech request (model='${request.modelId}', " +
            "voice='${request.voice}'). Attempts: $attempted",
    )
}

/**
 * Streaming variant of [synthesizeWithFallback] for
 * `aigc-tool-streaming-first-emitter` (cycle 43). Each engine's
 * `synthesizeStreaming` invocation forwards its [onChunk] callback to
 * the caller — for the AIGC tool, that's where each chunk turns into a
 * `BusEvent.ToolStreamingPart` publish before the final ToolResult.
 *
 * **Fallback semantics** mirror the non-streaming variant exactly:
 * each engine's failure is remembered, the chain proceeds, and only
 * the successful engine's chunks reach [onChunk]. A failed engine that
 * emitted partial chunks before throwing CANNOT have those chunks
 * "rolled back" — they already left the function — but the design is
 * sound because non-streaming engines emit exactly one synthetic chunk
 * via the default impl (the synthetic chunk fires AFTER the underlying
 * `synthesize` call returns successfully, so a failure means zero
 * chunks were emitted from that engine). For genuinely streaming
 * engines (future OpenAI HTTP streaming wire-up), callers must tolerate
 * "engine A emitted 2 chunks, then crashed; engine B emitted 5 chunks"
 * — UI subscribers see 7 chunks total, and the result reflects only
 * engine B's bytes. That's an honest representation of what happened
 * over the wire.
 */
internal suspend fun synthesizeStreamingWithFallback(
    engines: List<TtsEngine>,
    request: TtsRequest,
    onChunk: suspend (ByteArray) -> Unit,
    onWarmup: suspend (BusEvent.ProviderWarmup.Phase, String) -> Unit = { _, _ -> },
): TtsResult {
    val failures = mutableListOf<Pair<String, Throwable>>()
    for (engine in engines) {
        try {
            return engine.synthesizeStreaming(
                request = request,
                onChunk = onChunk,
                onWarmup = { phase -> onWarmup(phase, engine.providerId) },
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            failures += engine.providerId to t
        }
    }
    val attempted = failures.joinToString("; ") { (id, t) -> "$id: ${t.message ?: t::class.simpleName}" }
    error(
        "All ${engines.size} TTS engine(s) failed for streaming text-to-speech request (model='${request.modelId}', " +
            "voice='${request.voice}'). Attempts: $attempted",
    )
}
