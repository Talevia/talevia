package io.talevia.core.domain.source.genre.ad

/**
 * Dotted-namespace kind strings for the Ad / Marketing genre (VISION §2
 * genre list). Kept as constants rather than an enum so the genre can grow
 * new kinds without a Core recompile — Core only sees
 * [io.talevia.core.domain.source.SourceNode.kind] as an opaque [String].
 *
 * Sibling of the vlog / narrative / music-mv / tutorial genres; same "your
 * namespace, your versioning" contract, no imports between genre packages.
 */
object AdNodeKinds {
    /** Strategy brief: brand, tagline, tone, audience, CTA. */
    const val BRAND_BRIEF = "ad.brand_brief"

    /** Product spec: name, description, key benefits, reference imagery. */
    const val PRODUCT_SPEC = "ad.product_spec"

    /**
     * One shipping variant of the ad (duration × aspect × language). One ad
     * project typically has many — this is the genre's distinguishing
     * property.
     */
    const val VARIANT_REQUEST = "ad.variant_request"
}
