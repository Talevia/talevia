package io.talevia.core.platform

import io.talevia.core.bus.BusEvent
import kotlinx.serialization.Serializable

/**
 * SDK-agnostic contract for a text-to-speech generative provider — the AIGC
 * audio lane (VISION §2 "AIGC: TTS / 声音克隆").
 *
 * Modeled like [ImageGenEngine] (one interface per modality) rather than rolled
 * into a generic generative umbrella: TTS inputs (text + voice + speed +
 * format) and outputs (a single audio blob in a chosen container) don't
 * usefully share fields with image / video gen, and a premature umbrella would
 * either be too vague to type-check or leak modality-specific concepts.
 *
 * Implementations (e.g. `OpenAiTtsEngine`) translate native HTTP payloads into
 * these common types at the boundary. Per CLAUDE.md §5, provider-native types
 * MUST NOT leak past [synthesize].
 */
interface TtsEngine {
    /** Stable provider id — recorded in [GenerationProvenance.providerId]. */
    val providerId: String

    suspend fun synthesize(request: TtsRequest): TtsResult

    /**
     * Warmup-aware variant mirroring [MusicGenEngine.generate]'s
     * [onWarmup]. Engines invoke [onWarmup] with `Starting` right
     * before the provider HTTP call and `Ready` after the first
     * successful response byte. Default delegates to [synthesize] so
     * existing impls + tests keep working unchanged.
     */
    suspend fun synthesize(
        request: TtsRequest,
        onWarmup: suspend (BusEvent.ProviderWarmup.Phase) -> Unit,
    ): TtsResult = synthesize(request)

    /**
     * Streaming-aware variant — `streaming-engine-api-tts-overload`
     * (cycle 42). Prerequisite for `aigc-tool-streaming-first-emitter`:
     * the AIGC tool wires `onChunk` to `ctx.publishEvent(BusEvent.
     * ToolStreamingPart(...))` so UI subscribers see audio bytes
     * arriving in chunks before the final `ToolResult`.
     *
     * Streaming-capable engines (eventually OpenAI TTS via chunked
     * transfer; provider-side wire change tracked as a follow-up bullet)
     * invoke [onChunk] one or more times during the response, then
     * return [TtsResult] with the full assembled audio. Non-streaming
     * engines fall back to a single synthetic [onChunk] call carrying
     * the assembled blob — that path keeps the contract honest (≥ 1
     * chunk) without forcing every impl to learn HTTP streaming.
     *
     * The default impl preserves backward-compat: existing engines
     * keep working unchanged; the tool layer can opt into streaming
     * by switching from [synthesize] to [synthesizeStreaming], and
     * legacy fakes will emit exactly one chunk. Test fakes can override
     * to simulate multi-chunk providers.
     *
     * **Why not generalize across modalities?** Image / video / music
     * generation produces a single completed asset (no useful
     * intermediate state to emit during the call). Speech synthesis
     * is uniquely chunkable — each phoneme group is playable as it
     * arrives. The streaming overload lives only on [TtsEngine] for
     * that reason; introducing it on the others would cargo-cult the
     * shape into engines that don't actually stream.
     */
    suspend fun synthesizeStreaming(
        request: TtsRequest,
        onChunk: suspend (ByteArray) -> Unit,
        onWarmup: suspend (BusEvent.ProviderWarmup.Phase) -> Unit = {},
    ): TtsResult {
        val result = synthesize(request, onWarmup)
        onChunk(result.audio.audioBytes)
        return result
    }
}

/**
 * @param text the script to speak. Provider-side limits apply (OpenAI: 4096
 *   chars per call); chunking longer scripts is the tool layer's job, not the
 *   engine's.
 * @param modelId provider-scoped model id (e.g. "tts-1", "tts-1-hd").
 * @param voice provider-scoped voice id (OpenAI: alloy/echo/fable/onyx/nova/
 *   shimmer). Future provider-side voice cloning gets routed through
 *   [parameters] / a new field — the common shape stays the same.
 * @param format output container. Common values across providers: "mp3",
 *   "opus", "aac", "flac", "wav", "pcm". Default mp3 because it imports into
 *   every editor.
 * @param speed playback speed multiplier (1.0 = normal). Providers vary in the
 *   range they accept; default 1.0 stays inside everyone's bounds.
 * @param parameters provider-specific extras the common fields don't cover —
 *   merged into the request body verbatim and recorded in provenance so a
 *   replay is byte-identical to what the user asked for.
 */
@Serializable
data class TtsRequest(
    val text: String,
    val modelId: String,
    val voice: String,
    val format: String = "mp3",
    val speed: Double = 1.0,
    val parameters: Map<String, String> = emptyMap(),
    /**
     * Optional ISO-639-1 language hint for the voiceover (e.g. `"en"`, `"es"`,
     * `"zh"`). OpenAI TTS auto-detects language from [text] and ignores the
     * hint at the wire level; the field is still recorded in the engine's
     * provenance + the tool's lockfile inputHash so `(same text, different
     * language)` yields a distinct cache entry — driver of the
     * `fork_project(variantSpec.language=…)` "same vlog in Spanish" flow.
     * Providers that do accept a language param (future additions) may route
     * this directly into their request body.
     */
    val language: String? = null,
)

@Serializable
data class TtsResult(
    val audio: SynthesizedAudio,
    val provenance: GenerationProvenance,
)

/**
 * One audio blob returned by the provider. [format] echoes [TtsRequest.format]
 * so the caller doesn't have to remember it (and so a future
 * provider-overrides-format edge case has somewhere to land).
 */
@Serializable
data class SynthesizedAudio(
    val audioBytes: ByteArray,
    val format: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesizedAudio) return false
        if (format != other.format) return false
        if (!audioBytes.contentEquals(other.audioBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = audioBytes.contentHashCode()
        result = 31 * result + format.hashCode()
        return result
    }
}
