package io.talevia.core.platform

import io.talevia.core.bus.BusEvent
import kotlinx.serialization.Serializable

/**
 * SDK-agnostic contract for a super-resolution / upscaling provider — the ML
 * "take a low-resolution image, emit a higher-resolution image" lane
 * (VISION §2 "ML 加工: … 超分 …").
 *
 * Modelled like [ImageGenEngine] rather than [AsrEngine] / [VisionEngine]:
 * the output is bytes (a new artifact), not derived text, so the same
 * provenance + lockfile disciplines apply. Diffusion-based SR models
 * (Real-ESRGAN + refiner, GFPGAN, SUPIR) have a `seed` knob that affects the
 * output, so the contract carries one even when the provider ignores it —
 * downstream `UpscaleAssetTool` hashes it into the lockfile cache key so the
 * caller's intent stays explicit across providers.
 *
 * Scope. v1 targets images. Video super-res is a separate concern (temporal
 * coherence, frame-batching); when we add it, the cleanest path is a sibling
 * `VideoUpscaleEngine` rather than overloading this one — image SR and video
 * SR share the "scale" field and not much else.
 *
 * Per CLAUDE.md §5, provider-native types MUST NOT leak past [upscale].
 */
interface UpscaleEngine {
    /** Stable provider id — recorded in [GenerationProvenance.providerId]. */
    val providerId: String

    suspend fun upscale(request: UpscaleRequest): UpscaleResult

    /**
     * Warmup-aware variant. See [MusicGenEngine.generate] for the rationale.
     * Engines invoke [onWarmup] with `Starting` right before the first
     * provider HTTP call and `Ready` after the first successful poll
     * response so UI subscribers can surface the cold-start lag.
     */
    suspend fun upscale(
        request: UpscaleRequest,
        onWarmup: suspend (BusEvent.ProviderWarmup.Phase) -> Unit,
    ): UpscaleResult = upscale(request)
}

/**
 * A request for a single upscaled image.
 *
 * @param imagePath absolute filesystem path to the source image. The Tool
 *   layer resolves an `AssetId` through [MediaPathResolver] first.
 * @param modelId provider-scoped model identifier (e.g. "real-esrgan-4x",
 *   "supir").
 * @param scale integer multiplier — 2, 3, or 4 in practice. Most providers
 *   gate scale via the model id (you pick a 2x model, a 4x model, etc.), so
 *   engines are free to clamp / round to what the chosen [modelId] supports
 *   and record the actual scale applied in [UpscaleResult].
 * @param seed RNG seed for stochastic models. Non-null — callers mint one
 *   client-side when omitted so provenance is always complete (VISION §3.1).
 *   Providers that ignore the seed still hash it into the cache key, so the
 *   caller's determinism intent is preserved.
 * @param format output image container — "png" default. "jpg" when lossy
 *   compression is acceptable.
 * @param parameters provider-specific extras (denoise strength, face
 *   enhancement flag, tile size, …). Merged into the request body verbatim
 *   and echoed into provenance.
 */
@Serializable
data class UpscaleRequest(
    val imagePath: String,
    val modelId: String,
    val scale: Int,
    val seed: Long,
    val format: String = "png",
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class UpscaleResult(
    val image: UpscaledImage,
    val provenance: GenerationProvenance,
)

/**
 * One upscaled image returned by the provider. [format] echoes the requested
 * container; [width] / [height] are the actual output dimensions, which the
 * caller uses to size the registered asset's `MediaMetadata`.
 */
@Serializable
data class UpscaledImage(
    val imageBytes: ByteArray,
    val format: String,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UpscaledImage) return false
        if (format != other.format) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (!imageBytes.contentEquals(other.imageBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = imageBytes.contentHashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
