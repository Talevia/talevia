package io.talevia.core.domain.source.genre.narrative

/**
 * Dotted-namespace kind strings for the Narrative genre (VISION §6). Kept as
 * constants rather than an enum so the genre can grow new kinds without a Core
 * recompile — Core only sees [io.talevia.core.domain.source.SourceNode.kind]
 * as an opaque [String].
 *
 * Sibling of [io.talevia.core.domain.source.genre.vlog.VlogNodeKinds]; both
 * adhere to the same "your namespace, your versioning" contract and never
 * import from each other (enforced by file placement, not by Core).
 *
 * Character nodes reuse the genre-agnostic
 * [io.talevia.core.domain.source.consistency.ConsistencyKinds.CHARACTER_REF]
 * rather than minting a `narrative.character` — cross-shot character
 * consistency is a §3.3 first-class concept regardless of genre, and the
 * AIGC tools (`generate_image`, `generate_video`, `synthesize_speech`) fold
 * character_ref bindings uniformly. A per-genre character kind would
 * fragment that lane without benefit.
 */
object NarrativeNodeKinds {
    /** World / setting: geography, era, cultural rules, magic system, etc. */
    const val WORLD = "narrative.world"

    /** Story outline: logline, acts, major beats. One per project typically. */
    const val STORYLINE = "narrative.storyline"

    /** A single scene: where, when, who's in it, what happens. Many per project. */
    const val SCENE = "narrative.scene"

    /**
     * A single shot within a scene: framing, camera movement, dialogue,
     * intended duration. The unit the compiler (traditional or AIGC) targets.
     */
    const val SHOT = "narrative.shot"
}
