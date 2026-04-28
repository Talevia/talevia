package io.talevia.core.domain

import io.talevia.core.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pin VISION §5.2 / M4 #1 (cost-of-three-layers measurement anchor):
 * the [FilterKind] registry must (a) cover every filter name today's
 * engines (FFmpeg JVM, Media3 Android, AVFoundation iOS) recognise so
 * cross-engine parity is type-safe, and (b) silently degrade to `null`
 * for unrecognised names so legacy / experimental bundles don't crash
 * mid-render.
 *
 * Whenever a new effect kind is added (M4's growth axis), this test
 * gets a new positive case AND the engines' sealed `when` arms fail to
 * compile until they handle it — that's the "cost-of-three-layers"
 * compile-time gate the milestone is built on.
 */
class FilterKindTest {

    private fun filter(name: String, params: Map<String, Float> = emptyMap(), assetId: AssetId? = null) =
        Filter(name = name, params = params, assetId = assetId)

    @Test fun fromStringMatchesEveryRegisteredKind() {
        assertEquals(FilterKind.Brightness, FilterKind.fromString("brightness"))
        assertEquals(FilterKind.Saturation, FilterKind.fromString("saturation"))
        assertEquals(FilterKind.Blur, FilterKind.fromString("blur"))
        assertEquals(FilterKind.Vignette, FilterKind.fromString("vignette"))
        assertEquals(FilterKind.Lut, FilterKind.fromString("lut"))
    }

    @Test fun fromStringIsCaseInsensitive() {
        // Engines historically called `.lowercase()` before switching; the
        // registry's `fromString` preserves that contract so existing
        // tool inputs that stamp `"Brightness"` or `"VIGNETTE"` continue
        // to dispatch.
        assertEquals(FilterKind.Brightness, FilterKind.fromString("Brightness"))
        assertEquals(FilterKind.Vignette, FilterKind.fromString("VIGNETTE"))
        assertEquals(FilterKind.Lut, FilterKind.fromString("Lut"))
    }

    @Test fun fromStringTrimsWhitespace() {
        // tool input JSON occasionally arrives with leading/trailing
        // whitespace from the LLM ("  blur  "). Trim before lookup so
        // the typed view matches what the engines used to do via
        // `name.lowercase()` (which itself doesn't trim, but the prior
        // `else -> null` branch silently dropped trim variants — this
        // is a strict-mode improvement that surfaces those as the
        // intended kind).
        assertEquals(FilterKind.Blur, FilterKind.fromString("  blur  "))
    }

    @Test fun fromStringReturnsNullForUnknownNames() {
        // Forward-compat: a future filter name in a bundle written by a
        // newer Talevia must NOT crash the renderer on this build —
        // engines drop unknown kinds silently. `null` is the agreed
        // unknown-marker; the engines' `null -> null` arms preserve
        // the same drop-silently behaviour the old `else -> null`
        // branches had.
        assertNull(FilterKind.fromString("emboss"))
        assertNull(FilterKind.fromString(""))
        assertNull(FilterKind.fromString(null as String?))
    }

    @Test fun filterKindGetterRoundTripsThroughName() {
        // The extension `Filter.kind` must be a pure function of
        // `Filter.name` — no hidden state. Two filters with the same
        // name lookup to the same kind regardless of params / assetId.
        val a = filter("vignette", params = mapOf("intensity" to 0.4f))
        val b = filter("vignette", params = mapOf("intensity" to 0.9f))
        val c = filter("Lut", assetId = AssetId("lut-asset-1"))
        assertEquals(FilterKind.Vignette, a.kind)
        assertEquals(FilterKind.Vignette, b.kind)
        assertEquals(FilterKind.Lut, c.kind)
    }

    @Test fun filterWithUnknownNameKindIsNull() {
        // Engines test for `filter.kind == null` to drop unknown
        // filters; this is the inverse-positive case proving the
        // extension getter wires through to fromString correctly.
        val f = filter("hue_shift")
        assertNull(f.kind)
    }

    @Test fun registrySizeMeetsM4OneCriterion() {
        // M4 §5.2 criterion 1: registry must have ≥ 5 known kinds for
        // the cost-of-three-layers measurement to be non-trivial.
        // Adding the 6th kind exercises the full path (sealed arm +
        // engine when arm + tool schema enum value), which is the
        // measurement criterion this test guards.
        assertEquals(
            5,
            FilterKind.entries.size,
            "FilterKind grew or shrank — update both the M4 §5.2 cost-of-three-layers " +
                "evidence in MILESTONES.md and every engine's `when (filter.kind)` arms.",
        )
    }
}
