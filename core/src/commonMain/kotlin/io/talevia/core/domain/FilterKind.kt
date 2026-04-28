package io.talevia.core.domain

/**
 * Registered filter kinds — the closed set of [Filter] effects every engine
 * in Talevia knows how to render. M4 §5.2 "expert effects" cost-of-three-
 * layers measurement anchor: adding a new kind means
 *
 *   1. add an enum entry here (1 layer — Core),
 *   2. add a `FilterKind.<NewKind> -> ...` arm in each engine's filter
 *      dispatch (1 layer per platform),
 *   3. expose the kind in the `filter_action` tool schema's enum
 *      (1 layer — tool surface).
 *
 * No `core.domain` re-shape, no AppContainer wiring, no new Tool. The
 * sealed `when` over [FilterKind] in each engine produces a compile-time
 * exhaustiveness check (with [FilterKind.fromString] returning `null` on
 * unknown names so legacy / experimental filter names survive as a
 * silent-drop branch instead of a hard fail).
 *
 * [Filter.name] stays the canonical wire format so existing `talevia.json`
 * bundles + tool input JSON keep working unchanged. [Filter.kind] is a
 * derived view that engines opt into for type-safe dispatch — see
 * [Filter.kind] extension getter below.
 */
enum class FilterKind {
    /** EQ-style brightness adjustment; param `intensity` ∈ [-1, 1]. */
    Brightness,

    /** EQ-style saturation; param `intensity` ∈ [0, 1] (0 desaturated, 0.5 unchanged, 1 max). */
    Saturation,

    /** Gaussian blur; param `radius` ∈ [0, 1] or `sigma` direct. */
    Blur,

    /**
     * Radial darkening at the frame corners. FFmpeg `vignette`,
     * Media3 radial-gradient `BitmapOverlay`, AVFoundation `CIVignette` —
     * see CLAUDE.md "Known incomplete" cross-engine parity note.
     */
    Vignette,

    /**
     * 3D LUT (`.cube` / `.3dl`) color grading. Requires [Filter.assetId]
     * to point at a bundle-resolvable LUT file. Engines drop silently
     * when the asset can't be resolved, consistent with "unknown
     * filter inputs are dropped" rather than failing the whole render.
     */
    Lut,
    ;

    companion object {
        /**
         * Map a free-form filter name to its known [FilterKind], or `null`
         * when the name doesn't match any registered kind. Case-insensitive
         * — matches the existing engines' `.lowercase()` switch convention.
         *
         * Why an explicit map rather than `valueOf` magic: legacy bundles
         * and tool inputs use lowercase strings (`"vignette"`), but
         * Kotlin's `valueOf` requires the exact `enum` spelling
         * (`Vignette`). Unrecognised names stay `null` so callers can
         * gracefully degrade instead of throwing
         * `IllegalArgumentException` mid-render.
         */
        fun fromString(name: String?): FilterKind? {
            if (name == null) return null
            return when (name.trim().lowercase()) {
                "brightness" -> Brightness
                "saturation" -> Saturation
                "blur" -> Blur
                "vignette" -> Vignette
                "lut" -> Lut
                else -> null
            }
        }
    }
}

/**
 * Type-safe view of [Filter.name] as a [FilterKind] enum. `null` when the
 * name doesn't match any registered kind (caller treats as "unknown
 * filter, drop silently" — same semantic the engines' previous
 * `else -> null` branches had). Engines switch on this getter to gain
 * compile-time exhaustiveness on the closed set of known kinds.
 */
val Filter.kind: FilterKind?
    get() = FilterKind.fromString(name)
