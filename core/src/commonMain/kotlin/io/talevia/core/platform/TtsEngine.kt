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
