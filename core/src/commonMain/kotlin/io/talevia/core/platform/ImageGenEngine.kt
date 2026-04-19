package io.talevia.core.platform

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

    suspend fun generate(request: ImageGenRequest): ImageGenResult
}

/**
 * A request for N images at a fixed size. [seed] is non-null — callers (the
 * Tool layer) are expected to generate one client-side if the user did not
 * supply one, so provenance is always complete. See VISION §3.1.
 *
 * [parameters] is reserved for provider-specific extras the common fields
 * do not cover (e.g. `style`, `quality`, `guidance`). Engines must merge it
 * into the final request body verbatim.
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
