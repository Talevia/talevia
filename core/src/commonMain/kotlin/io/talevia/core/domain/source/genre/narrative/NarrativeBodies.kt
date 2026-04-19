package io.talevia.core.domain.source.genre.narrative

import io.talevia.core.AssetId
import kotlinx.serialization.Serializable

/**
 * Typed payload bodies for the Narrative genre's
 * [io.talevia.core.domain.source.SourceNode]s.
 *
 * Each body is a regular [kotlinx.serialization.Serializable] data class — it
 * is encoded into a [kotlinx.serialization.json.JsonElement] via the canonical
 * [io.talevia.core.JsonConfig.default] at write time and decoded on read by
 * [NarrativeSourceExt] accessors. Core never sees these types; that is what
 * keeps the genre schema out of Core.
 *
 * Field philosophy: lean, free-form strings for the bulk (`description`,
 * `action`) plus a few structured fields where downstream tools / UI benefit
 * from them (`durationSeconds`, `framing`, `cameraMovement`). Avoid enums —
 * genre schemas need to absorb edge cases without a Core recompile, and
 * stringly-typed fields with UI conventions are cheaper to evolve than a
 * hand-curated enum the agent will violate on day two.
 */

/**
 * World / setting node. Describes the rules of the story universe — geography,
 * era, cultural conventions, speculative elements. Bound to scenes/shots that
 * depend on it via [io.talevia.core.domain.source.SourceNode.parents]; an edit
 * to the world flows through the DAG to mark every descendant scene stale.
 *
 * @param name a short identifier the agent can reference ("neo-shibuya-2087").
 * @param description free-form prose describing geography / time / rules.
 * @param era optional hint for pacing / reference generation (e.g. "cyberpunk",
 *   "edo-period"). Agent may look at it to pick AIGC style defaults.
 * @param referenceAssetIds concept-art / mood-board images the compiler can
 *   pass as visual references to `generate_image` / `generate_video` the
 *   same way character_ref does for character consistency.
 */
@Serializable
data class NarrativeWorldBody(
    val name: String,
    val description: String,
    val era: String? = null,
    val referenceAssetIds: List<AssetId> = emptyList(),
)

/**
 * Story outline. The top-level "what happens" — logline + act structure. One
 * per project is typical; branching stories can have multiple and parents
 * on scenes point at the one in effect.
 *
 * @param logline one-sentence pitch. Folded into AIGC prompts for the whole
 *   project the same way a style_bible gets folded.
 * @param synopsis longer-form summary.
 * @param acts ordered list of major beats. Each act is free-form prose —
 *   promoting to a typed struct would lock in a three-act assumption that
 *   not every narrative follows.
 * @param targetDurationSeconds optional total runtime hint for the agent.
 */
@Serializable
data class NarrativeStorylineBody(
    val logline: String,
    val synopsis: String = "",
    val acts: List<String> = emptyList(),
    val targetDurationSeconds: Int? = null,
)

/**
 * Scene node. One scene in the story: where it takes place, who's in it,
 * what happens. The unit a shot list is organised under.
 *
 * @param title short scene title ("arrival at the border checkpoint").
 * @param location free-form place description.
 * @param timeOfDay optional lighting / mood hint ("dusk", "blue-hour").
 * @param action prose description of what happens in the scene — this is the
 *   payload the compiler folds when generating shot imagery / video.
 * @param characterIds `SourceNodeId`s of `character_ref` nodes present in the
 *   scene. Listed here as simple strings (rather than `SourceRef`) because the
 *   strong reference relationship lives on `SourceNode.parents` for DAG
 *   propagation; this field is the scene-local "who's on stage" view.
 */
@Serializable
data class NarrativeSceneBody(
    val title: String,
    val location: String = "",
    val timeOfDay: String? = null,
    val action: String = "",
    val characterIds: List<String> = emptyList(),
)

/**
 * Shot node. The unit the compiler targets — one shot becomes one (or more)
 * clips on the timeline. The shot's body captures the creative intent; the
 * agent translates it into AIGC calls (`generate_video`) or edits of
 * imported footage.
 *
 * @param sceneId scene this shot belongs to — mirrors [parents] but kept here
 *   for quick scene-scoped queries without walking the DAG.
 * @param framing "wide", "medium", "close-up", "over-shoulder", etc. Free-form
 *   string; the agent resolves to compiler parameters.
 * @param cameraMovement "static", "pan left", "slow push-in", etc.
 * @param action what happens in the shot — physical action, staging.
 * @param dialogue optional line delivered in this shot. When present, paired
 *   with a character_ref binding (via `parents`) this is the payload
 *   `synthesize_speech` uses to produce audio with the character's voice.
 * @param speakerId character node id of the speaker (must be among the scene's
 *   `characterIds` when set).
 * @param targetDurationSeconds intended shot duration. Informs `generate_video`
 *   durationSeconds and clip trimming.
 */
@Serializable
data class NarrativeShotBody(
    val sceneId: String,
    val framing: String = "",
    val cameraMovement: String = "",
    val action: String = "",
    val dialogue: String? = null,
    val speakerId: String? = null,
    val targetDurationSeconds: Double? = null,
)
