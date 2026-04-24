package io.talevia.core.domain.source.consistency

/**
 * Kind constants for **cross-shot consistency** source nodes (VISION §3.3).
 *
 * These nodes live in Core rather than in any genre extension because multiple genres
 * share the same concept — a "character" in a narrative short is structurally identical
 * to a "performer" in an MV or a "subject" in a vlog. Hardcoding them in Core is *not*
 * a VISION §"在 Core 里硬编码某一个 genre 的 source schema" violation: these abstractions
 * are the *way* we express cross-genre consistency, not a particular genre's shape.
 *
 * Namespace is `core.consistency.*` so genre extensions can reference them without
 * collision (e.g. a genre can't accidentally define its own `character_ref` on top).
 */
object ConsistencyKinds {
    const val CHARACTER_REF = "core.consistency.character_ref"
    const val STYLE_BIBLE = "core.consistency.style_bible"
    const val BRAND_PALETTE = "core.consistency.brand_palette"
    const val LOCATION_REF = "core.consistency.location_ref"

    /** All consistency kinds — useful for filtering in folds/resolvers. */
    val ALL: Set<String> = setOf(CHARACTER_REF, STYLE_BIBLE, BRAND_PALETTE, LOCATION_REF)
}
