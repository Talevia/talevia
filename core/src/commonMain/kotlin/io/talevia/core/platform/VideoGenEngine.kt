package io.talevia.core.platform

import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.source.consistency.LoraPin
import kotlinx.serialization.Serializable

/**
 * SDK-agnostic contract for a text-to-video generative provider — the AIGC
 * video lane (VISION §2 "AIGC: 文生图 / 文生视频").
 *
 * Modelled like [ImageGenEngine] / [TtsEngine] (one interface per modality) so
 * that modality-specific concepts (duration, video codec) don't leak into the
 * others, and translation at the boundary stays provider-local. Per
 * CLAUDE.md §5, provider-native types MUST NOT leak past [generate].
 *
 * Text-to-video providers are typically async (job submit → poll → download).
 * The interface hides that: callers see a single suspendable call that returns
 * the finished bytes. Implementations are responsible for polling and timing
 * out internally.
 */
interface VideoGenEngine {
    /** Stable provider id — recorded in [GenerationProvenance.providerId]. */
    val providerId: String

    suspend fun generate(request: VideoGenRequest): VideoGenResult

    /**
     * Warmup-aware variant mirroring [MusicGenEngine.generate]'s
     * [onWarmup]. Emits `Starting` right before the submit call and
     * `Ready` after the first poll response with status `queued` /
     * `processing` so the LLM's user sees a "warming up sora…" hint
     * during the 5-30s job-submit latency. Default delegates to
     * [generate] so existing impls + tests keep working unchanged.
     */
    suspend fun generate(
        request: VideoGenRequest,
        onWarmup: suspend (BusEvent.ProviderWarmup.Phase) -> Unit,
    ): VideoGenResult = generate(request)
}

/**
 * A request for a single video of a fixed size and duration. [seed] is
 * non-null — callers (the Tool layer) generate one client-side when the user
 * omits one so provenance is always complete. See VISION §3.1.
 *
 * [parameters] is reserved for provider-specific extras not covered by the
 * common fields (guidance scale, style presets, motion intensity, etc.).
 * Engines must merge it into the final request body verbatim.
 *
 * [negativePrompt], [referenceAssetPaths], and [loraPins] are populated by the
 * Tool layer from a folded consistency binding (VISION §3.3). Engines that
 * don't natively support a hook MUST still record the incoming value in
 * [GenerationProvenance.parameters] — dropping it on the floor would break
 * replay / audit and let lockfile cache keys collide across semantically
 * distinct generations.
 */
@Serializable
data class VideoGenRequest(
    val prompt: String,
    val modelId: String,
    val width: Int,
    val height: Int,
    /**
     * Target duration in seconds. Providers vary in the exact values they
     * accept (Sora 2: 4, 8, 12); the engine is expected to clamp / round to
     * the nearest supported value and record the actual duration in
     * [GeneratedVideo.durationSeconds].
     */
    val durationSeconds: Double,
    val seed: Long,
    val parameters: Map<String, String> = emptyMap(),
    val negativePrompt: String? = null,
    val referenceAssetPaths: List<String> = emptyList(),
    val loraPins: List<LoraPin> = emptyList(),
)

@Serializable
data class VideoGenResult(
    val videos: List<GeneratedVideo>,
    val provenance: GenerationProvenance,
)

/**
 * A single video the provider produced. [mp4Bytes] is a standalone mp4
 * container; callers persist it into the project bundle via a
 * [BundleBlobWriter] and append the resulting [io.talevia.core.domain.MediaAsset]
 * to `Project.assets`. Width / height / duration are echoed on the struct
 * because per-provider APIs vary in what they return, and the engine is
 * expected to backfill from the request when the response is sparse.
 */
@Serializable
data class GeneratedVideo(
    val mp4Bytes: ByteArray,
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GeneratedVideo) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (durationSeconds != other.durationSeconds) return false
        if (!mp4Bytes.contentEquals(other.mp4Bytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = mp4Bytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + durationSeconds.hashCode()
        return result
    }
}
