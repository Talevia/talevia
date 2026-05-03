package io.talevia.core.platform

import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.source.consistency.LoraPin
import kotlinx.serialization.Serializable

/**
 * SDK-agnostic contract for a text-to-image generative provider.
 *
 * Modelled as one interface per modality on purpose — there is no shared
 * `GenerativeEngine` umbrella yet. Video / TTS / music will get their own
 * interfaces when the first real impl lands; a premature cross-modality
 * abstraction would either be too vague to type-check inputs / outputs or
 * leak modality-specific concepts into each other.
 *
 * Implementations (e.g. `OpenAiImageGenEngine`) translate their native HTTP /
 * SDK payloads into these common types at the boundary. Per CLAUDE.md §5
 * provider abstraction rules, provider-native types MUST NOT leak past the
 * engine's surface.
 */
interface ImageGenEngine {
    /** Stable identifier for the provider — recorded in [GenerationProvenance.providerId]. */
    val providerId: String

    /**
     * Whether the provider returns N distinct images in a **single API call**
     * when `request.n > 1` (`aigc-multi-variant-phase3-openai-native-n`). True
     * = caller can batch — issue one provider request for `n` variants and
     * pay 1 round-trip + the per-variant cost on the provider's side. False
     * = caller should loop `request.n` separate `n=1` calls (cycle 29 phase 2's
     * sequential default), because the engine doesn't natively fan out and
     * may either ignore `n` or error.
     *
     * Default `false` so any new ImageGenEngine impl is **safe by
     * construction** (the dispatcher falls back to the proven sequential
     * loop). Engines that DO natively batch (OpenAI image-gen) explicitly
     * override this to true. Adding a new batch-supporting engine = one
     * `override val` line + an integration test that asserts a single
     * provider call for `n>1`.
     */
    val supportsNativeBatch: Boolean get() = false

    suspend fun generate(request: ImageGenRequest): ImageGenResult

    /**
     * Warmup-aware variant mirroring [MusicGenEngine.generate]'s
     * [onWarmup]. Engines that want to surface the provider's cold-
     * start lag (see [BusEvent.ProviderWarmup]) call [onWarmup] with
     * `Starting` right before the provider HTTP call and `Ready` after
     * the first successful response byte. Default delegates to the
     * plain [generate] so existing impls + tests keep working.
     */
    suspend fun generate(
        request: ImageGenRequest,
        onWarmup: suspend (BusEvent.ProviderWarmup.Phase) -> Unit,
    ): ImageGenResult = generate(request)
}

/**
 * A request for N images at a fixed size. [seed] is non-null — callers (the
 * Tool layer) are expected to generate one client-side if the user did not
 * supply one, so provenance is always complete. See VISION §3.1.
 *
 * [parameters] is reserved for provider-specific extras the common fields
 * do not cover (e.g. `style`, `quality`, `guidance`). Engines must merge it
 * into the final request body verbatim.
 *
 * [negativePrompt], [referenceAssetIds], and [loraPins] are populated by the
 * Tool layer from a folded consistency binding (VISION §3.3). Engines that
 * don't support a given hook (e.g. DALL-E has no LoRA) MUST still record
 * the incoming value in [GenerationProvenance.parameters] — dropping it on
 * the floor breaks replay / audit and makes the lockfile cache key ambiguous.
 * Engines that DO support a hook should translate it into the native wire
 * shape.
 */
@Serializable
data class ImageGenRequest(
    val prompt: String,
    val modelId: String,
    val width: Int,
    val height: Int,
    val seed: Long,
    val n: Int = 1,
    val parameters: Map<String, String> = emptyMap(),
    val negativePrompt: String? = null,
    val referenceAssetPaths: List<String> = emptyList(),
    val loraPins: List<LoraPin> = emptyList(),
)

@Serializable
data class ImageGenResult(
    val images: List<GeneratedImage>,
    val provenance: GenerationProvenance,
)

/**
 * A single PNG the provider produced. Width/height are carried on the image
 * because per-provider APIs vary (OpenAI does not echo per-image size); the
 * engine is expected to backfill from the request.
 */
@Serializable
data class GeneratedImage(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GeneratedImage) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (!pngBytes.contentEquals(other.pngBytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pngBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
