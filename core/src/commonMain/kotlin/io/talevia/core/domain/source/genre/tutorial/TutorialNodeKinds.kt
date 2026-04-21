package io.talevia.core.domain.source.genre.tutorial

/**
 * Dotted-namespace kind strings for the Tutorial genre (VISION §2 genre list).
 * Kept as constants rather than an enum so the genre can grow new kinds
 * without a Core recompile — Core only sees
 * [io.talevia.core.domain.source.SourceNode.kind] as an opaque [String].
 *
 * Sibling of the vlog / narrative / music-mv genres; same "your namespace,
 * your versioning" contract, no imports between genre packages.
 */
object TutorialNodeKinds {
    /** The spoken script plus optional section headers for chaptering. */
    const val SCRIPT = "tutorial.script"

    /** Imported screen-capture / demo clips the tutorial draws from. */
    const val BROLL_LIBRARY = "tutorial.broll_library"

    /** Brand / look spec for the tutorial: product name, colors, lower-third style. */
    const val BRAND_SPEC = "tutorial.brand_spec"
}
