package io.talevia.core.platform

import kotlinx.serialization.Serializable

/**
 * SDK-agnostic contract for a text-to-music generative provider — the AIGC
 * music lane (VISION §2 "AIGC: 音乐生成"). Sibling of [ImageGenEngine],
 * [VideoGenEngine], and [TtsEngine]: one interface per modality so that
 * modality-specific concepts (duration, genre prompts, instrument stems)
 * don't leak into the others.
 *
 * Music providers today (Suno, Udio, MusicGen, Stable Audio) differ wildly
 * in what they accept — some take a single text prompt, some expect
 * `{title, lyrics, style}` separately, some gate access behind no-public-API
 * products. The contract here keeps the common shape narrow: prompt, model,
 * seed, duration, format. Provider extras ride through [MusicGenRequest.parameters]
 * and engines MUST echo them into [GenerationProvenance.parameters] verbatim
 * so the lockfile hash is faithful and replays are byte-identical to the
 * intent.
 *
 * Per CLAUDE.md §5, provider-native types MUST NOT leak past [generate].
 */
interface MusicGenEngine {
    /** Stable provider id — recorded in [GenerationProvenance.providerId]. */
    val providerId: String

    suspend fun generate(request: MusicGenRequest): MusicGenResult
}

/**
 * A request for a single piece of generated music. [seed] is non-null — callers
 * (the Tool layer) mint one client-side when the user omits one, so provenance
 * is always complete (VISION §3.1).
 *
 * @param prompt text describing what to generate (mood, genre, instruments).
 * @param modelId provider-scoped model id (e.g. "musicgen-melody", "suno-v4").
 * @param seed RNG seed recorded in provenance and consumed by the engine when
 *   supported. Providers that ignore seeds still hash it into the cache key, so
 *   the caller's intent ("I want determinism") is preserved in the lockfile
 *   even when the upstream can't honour it.
 * @param durationSeconds target length. Providers differ in the max they accept
 *   (Suno: ~4 min; MusicGen: 30s default). Engines clamp/round to the nearest
 *   supported value and record the actual duration in [GeneratedMusic.durationSeconds].
 * @param format output container. Common values: "mp3", "wav", "ogg", "flac".
 *   Default mp3 because it imports into every editor.
 * @param parameters provider-specific extras the common fields don't cover
 *   (lyrics, instrumental flag, guidance scale, …). Engines merge them into
 *   the request verbatim and record them in provenance.
 */
@Serializable
data class MusicGenRequest(
    val prompt: String,
    val modelId: String,
    val seed: Long,
    val durationSeconds: Double,
    val format: String = "mp3",
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class MusicGenResult(
    val music: GeneratedMusic,
    val provenance: GenerationProvenance,
)

/**
 * One music blob returned by the provider. [format] echoes [MusicGenRequest.format]
 * so the caller doesn't have to remember it; [durationSeconds] is the actual
 * length the provider produced (which may differ from the request when the
 * provider clamps / rounds). Width / height don't apply — music has no visual
 * dimensions — so they don't appear on the struct.
 */
@Serializable
data class GeneratedMusic(
    val audioBytes: ByteArray,
    val format: String,
    val durationSeconds: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GeneratedMusic) return false
        if (format != other.format) return false
        if (durationSeconds != other.durationSeconds) return false
        if (!audioBytes.contentEquals(other.audioBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = audioBytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + durationSeconds.hashCode()
        return result
    }
}
