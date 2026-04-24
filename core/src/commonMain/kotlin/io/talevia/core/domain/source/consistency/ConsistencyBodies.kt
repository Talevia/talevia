package io.talevia.core.domain.source.consistency

import io.talevia.core.AssetId
import kotlinx.serialization.Serializable

/**
 * A named character / subject / performer whose visual *and* vocal identity must stay
 * stable across shots (VISION §3.3, §5.5). The body carries every signal a downstream
 * AIGC tool might consume to keep the same face/body/silhouette/voice between
 * generations.
 *
 * @property name Human-readable handle — the LLM references this when reasoning.
 * @property visualDescription Natural-language description of the character's look,
 *   age, vibe, costume, etc. Folded into AIGC prompts by
 *   [io.talevia.core.domain.source.consistency.foldConsistencyIntoPrompt].
 * @property referenceAssetIds Project assets (image uploads, generated keyframes) that
 *   can be passed to image-gen models supporting reference images. Ordered; first is
 *   the canonical reference.
 * @property loraPin Optional LoRA / DreamBooth / IP-Adapter binding — the single
 *   strongest mechanism we have for per-character identity lock, when the provider
 *   supports it. `null` means "rely on prompt + reference images only".
 * @property voiceId Optional provider-scoped voice id (e.g. OpenAI `"alloy"`,
 *   ElevenLabs voice uuid). Consumed by [foldVoice] so `synthesize_speech` picks up
 *   this character's voice automatically when the character_ref is bound, instead of
 *   the caller having to pass the voice string on every call.
 */
@Serializable
data class CharacterRefBody(
    val name: String,
    val visualDescription: String,
    val referenceAssetIds: List<AssetId> = emptyList(),
    val loraPin: LoraPin? = null,
    val voiceId: String? = null,
)

/**
 * A pinned LoRA / adapter weight: which adapter to load and how strongly to apply it.
 * Provider-specific identifiers live in [adapterId] — we don't try to unify across
 * providers, because the URI scheme for HuggingFace / Civitai / custom hosts all differ.
 * The provider-side adapter runs interpret it.
 */
@Serializable
data class LoraPin(
    val adapterId: String,
    val weight: Float = 1.0f,
    val triggerTokens: List<String> = emptyList(),
)

/**
 * A coherent visual style shared across a project — look, color grade, negative
 * prompts, mood keywords. A [StyleBibleBody] tends to apply globally; a character
 * tends to apply per-shot. Keeping them as separate kinds lets the prompt folder
 * know the right ordering ("[style] [character] [shot-specific prompt]").
 *
 * @property name Short handle ("cinematic warm", "gritty handheld").
 * @property description Natural-language style description.
 * @property lutReference Optional project asset id of a LUT file, when the traditional
 *   color pipeline should apply one. The AIGC layer mostly ignores this; the traditional
 *   filter pass consumes it.
 * @property negativePrompt Freeform text the image-gen pass should steer away from.
 * @property moodKeywords Short adjectives folded into the prompt — "warm", "nostalgic",
 *   "frenetic".
 */
@Serializable
data class StyleBibleBody(
    val name: String,
    val description: String,
    val lutReference: AssetId? = null,
    val negativePrompt: String? = null,
    val moodKeywords: List<String> = emptyList(),
)

/**
 * A brand's visual identity: palette, typography hints. Used for ads / marketing
 * genres and for product-led content. Kept separate from [StyleBibleBody] because
 * brands have legal / compliance constraints (exact hex matches, typography licensing)
 * that a free-form style bible doesn't.
 */
@Serializable
data class BrandPaletteBody(
    val name: String,
    /** Hex colors, e.g. `["#0A84FF", "#FF3B30"]`. First is the primary. */
    val hexColors: List<String>,
    val typographyHints: List<String> = emptyList(),
)

/**
 * A recurring location / setting / environment that should look the same across shots —
 * the café where two characters keep meeting, the warehouse a product demo films in,
 * the vlogger's kitchen. Distinct from [CharacterRefBody] (subjects / identity) and
 * [StyleBibleBody] (global look); a location is a *place* the camera keeps returning to.
 * Cross-genre by construction: narrative settings, vlog home bases, MV backdrops, product
 * shoot locations all share the same structural need — "this spatial context must read
 * as the same place every time we cut to it."
 *
 * Kept minimal on purpose. Name + description + reference images mirrors [CharacterRefBody]
 * minus identity-specific fields (no LoRA pin, no voice). If providers grow
 * location-specific adapters (e.g. per-location style transfer), they can be added
 * later without schema churn because the fields are already optional.
 *
 * @property name Short handle the LLM references ("the café", "the warehouse").
 * @property description Natural-language description of the location — architecture,
 *   lighting, era, signage, any signature spatial features. Folded into AIGC prompts
 *   as a `Setting:` fragment by [foldConsistencyIntoPrompt].
 * @property referenceAssetIds Project assets (location photos, establishing shots)
 *   passed through to image-gen models that accept reference images. Ordered; first is
 *   the canonical reference.
 */
@Serializable
data class LocationRefBody(
    val name: String,
    val description: String,
    val referenceAssetIds: List<AssetId> = emptyList(),
)
