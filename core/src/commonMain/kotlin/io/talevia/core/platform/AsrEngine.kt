package io.talevia.core.platform

import kotlinx.serialization.Serializable

/**
 * SDK-agnostic contract for an automatic-speech-recognition provider — the ML
 * "transcribe an audio (or video-with-audio) file into timestamped text" lane.
 *
 * Modeled separately from the generative `*GenEngine` family on purpose: ASR is
 * an *enhancement* (input asset → derived data), not a generation, so its result
 * shape is timestamped text + language detection rather than bytes + provenance
 * for a new artifact. The output still carries [GenerationProvenance] so the
 * lockfile pattern (input hash → cached result) works the same way.
 *
 * Implementations (e.g. `OpenAiWhisperEngine`) translate native HTTP payloads
 * into these common types at the boundary. Per CLAUDE.md §5, provider-native
 * types MUST NOT leak past the engine surface.
 */
interface AsrEngine {
    /** Stable provider id — recorded in [GenerationProvenance.providerId]. */
    val providerId: String

    suspend fun transcribe(request: AsrRequest): AsrResult
}

/**
 * @param audioPath absolute filesystem path the engine can open. The Tool layer
 *   resolves an `AssetId` through `MediaPathResolver` before calling.
 * @param modelId provider-scoped model identifier (e.g. "whisper-1").
 * @param languageHint ISO-639-1 hint (e.g. "en", "zh"). When null the provider
 *   auto-detects; the detected language comes back on [AsrResult.language].
 * @param parameters provider-specific extras the common fields don't cover —
 *   merged into the request body verbatim and recorded in provenance so a
 *   replay is byte-identical to what the user asked for.
 */
@Serializable
data class AsrRequest(
    val audioPath: String,
    val modelId: String,
    val languageHint: String? = null,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class AsrResult(
    /** Concatenated transcript text — convenience for callers that don't care about timing. */
    val text: String,
    /** ISO-639-1 the provider detected (or echoed back the hint if it was supplied). */
    val language: String?,
    /**
     * Time-aligned segments. Empty when the provider doesn't return timestamps;
     * callers that need subtitles should treat empty as "transcribe-only result".
     */
    val segments: List<TranscriptSegment>,
    val provenance: GenerationProvenance,
)

/**
 * One time-aligned chunk of the transcript. Times are in milliseconds since the
 * start of the audio so they line up directly with `Clip.timeRange` units.
 */
@Serializable
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)
