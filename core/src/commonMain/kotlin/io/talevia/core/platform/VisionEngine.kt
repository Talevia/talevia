package io.talevia.core.platform

import kotlinx.serialization.Serializable

/**
 * SDK-agnostic contract for a vision / multimodal-describe provider — the ML
 * "take an image, emit a text description" lane (VISION §5.2 ML enhancement).
 *
 * Pairs with [AsrEngine] in shape: both are *enhancements* (existing asset →
 * derived data), not generations, so the result is text + provenance rather
 * than bytes + a new artifact. The [GenerationProvenance] still rides along
 * so the same replay/lockfile disciplines apply.
 *
 * Implementations (e.g. `OpenAiVisionEngine`) translate native HTTP payloads
 * into these common types at the boundary. Per CLAUDE.md §5, provider-native
 * types MUST NOT leak past the engine surface.
 */
interface VisionEngine {
    /** Stable provider id — recorded in [GenerationProvenance.providerId]. */
    val providerId: String

    suspend fun describe(request: VisionRequest): VisionResult
}

/**
 * @param imagePath absolute filesystem path to an image the engine can open.
 *   The Tool layer resolves an `AssetId` through `MediaPathResolver` first.
 * @param modelId provider-scoped model identifier (e.g. "gpt-4o-mini").
 * @param prompt optional instruction ("describe this image", "what brand
 *   is on the mug"). When null the engine uses its own generic describe prompt.
 * @param parameters provider-specific extras the common fields don't cover —
 *   merged into the request body verbatim and recorded in provenance so a
 *   replay is byte-identical to what the user asked for.
 */
@Serializable
data class VisionRequest(
    val imagePath: String,
    val modelId: String,
    val prompt: String? = null,
    val parameters: Map<String, String> = emptyMap(),
)

@Serializable
data class VisionResult(
    /** Free-form text description produced by the provider. */
    val text: String,
    val provenance: GenerationProvenance,
)
