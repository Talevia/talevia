package io.talevia.core.domain.source.genre.musicmv

/**
 * Dotted-namespace kind strings for the Music-MV genre (VISION §2 genre list).
 * Kept as constants rather than an enum so the genre can grow new kinds
 * without a Core recompile — Core only sees
 * [io.talevia.core.domain.source.SourceNode.kind] as an opaque [String].
 *
 * Sibling of [io.talevia.core.domain.source.genre.vlog.VlogNodeKinds] and
 * [io.talevia.core.domain.source.genre.narrative.NarrativeNodeKinds]; all three
 * adhere to the same "your namespace, your versioning" contract and never
 * import from each other. Palette consistency is handled via the genre-
 * agnostic `core.consistency.brand_palette` node rather than minting a
 * `musicmv.palette` — cross-genre brand consistency should live in the
 * consistency lane.
 */
object MusicMvNodeKinds {
    /** The MV's source audio track (song, BPM, key, lyrics reference). */
    const val TRACK = "musicmv.track"

    /** Creative brief for the MV's visual concept: mood, motifs, palette. */
    const val VISUAL_CONCEPT = "musicmv.visual_concept"

    /** A performance shot — performer, action, imported take assets. */
    const val PERFORMANCE_SHOT = "musicmv.performance_shot"
}
