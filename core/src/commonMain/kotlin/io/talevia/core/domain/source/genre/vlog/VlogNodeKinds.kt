package io.talevia.core.domain.source.genre.vlog

/**
 * Dotted-namespace kind strings for the Vlog genre. Kept as constants rather than an
 * enum so the genre layer can grow new kinds without a Core recompile — Core only sees
 * [io.talevia.core.domain.source.SourceNode.kind] as an opaque [String].
 *
 * This is the exemplar genre referenced in `docs/VISION.md` §6. Other genres (narrative,
 * MV, tutorial, ad) will live in sibling packages and must not import from this one.
 */
object VlogNodeKinds {
    const val RAW_FOOTAGE = "vlog.raw_footage"
    const val EDIT_INTENT = "vlog.edit_intent"
    const val STYLE_PRESET = "vlog.style_preset"
}
