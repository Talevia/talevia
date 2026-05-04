package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [Clip] subtypes + [Transform] + [Filter] +
 * [TextStyle] field defaults. Cycle 304 audit: no direct
 * `ClipFieldDefaultsTest.kt` / `TextStyleTest.kt` /
 * `TransformDefaultsTest.kt` (verified via cycle 289-banked
 * duplicate-check idiom). The 3 Clip subtypes + their nested
 * data classes are constructed across many tests but defaults
 * have no dedicated pin.
 *
 * Same audit-pattern fallback as cycles 207-303. Sister of
 * cycle 302 (SessionFieldDefaultsTest) + cycle 303
 * (ProjectFieldDefaultsTest).
 *
 * Why this matters: Clip subtypes are persisted in every
 * `talevia.json`'s timeline. Drift in any default silently
 * changes how every fresh clip / transform / text overlay
 * renders.
 *
 * Drift surface protected:
 *   - **Audio.volume = 1.0f default** — drift to 0.0f silently
 *     mutes every fresh audio clip; drift to 4.0f silently
 *     deafens every listener.
 *   - **Transform identity** (translate=0, scale=1, rotation=0,
 *     opacity=1) — drift to non-identity silently scrambles
 *     every fresh clip's render geometry.
 *   - **TextStyle.fontSize = 48f / color = "#FFFFFF"** — drift
 *     silently shrinks every text overlay or changes default
 *     visibility.
 *   - **Clip.Text.sourceRange = null override** — drift to
 *     non-null silently breaks engine routing (Text clips
 *     don't have a source media to range into).
 *   - **fade-in/out = 0.0f defaults** — drift to non-zero
 *     silently fades every fresh audio clip.
 */
class ClipFieldDefaultsTest {

    private val json: Json = JsonConfig.default
    private val tr = TimeRange(0.seconds, 5.seconds)

    private fun videoClip(): Clip.Video = Clip.Video(
        id = ClipId("c1"),
        timeRange = tr,
        sourceRange = tr,
        assetId = AssetId("a1"),
    )

    private fun audioClip(): Clip.Audio = Clip.Audio(
        id = ClipId("c2"),
        timeRange = tr,
        sourceRange = tr,
        assetId = AssetId("a2"),
    )

    private fun textClip(): Clip.Text = Clip.Text(
        id = ClipId("c3"),
        timeRange = tr,
        text = "hello",
    )

    // ── Clip.Video field defaults ───────────────────────────

    @Test fun videoTransformsDefaultsToEmptyList() {
        assertEquals(emptyList(), videoClip().transforms)
    }

    @Test fun videoFiltersDefaultsToEmptyList() {
        // Pin: per source line 48, no filters by default.
        // Drift to a non-empty default would silently apply
        // a filter to every fresh video clip.
        assertEquals(emptyList(), videoClip().filters)
    }

    @Test fun videoSourceBindingDefaultsToEmptySet() {
        // Pin: empty binding marks the clip as outside the
        // incremental-tracking system (3rd bucket).
        assertEquals(emptySet(), videoClip().sourceBinding)
    }

    @Test fun videoUpdatedAtEpochMsDefaultsToNull() {
        // Pin: null = "predates recency tracking" (sorts
        // last in `sortBy=recent`). Tools don't stamp
        // manually — FileProjectStore.upsert does.
        assertNull(videoClip().updatedAtEpochMs)
    }

    // ── Clip.Audio field defaults ───────────────────────────

    @Test fun audioVolumeDefaultsToOnePointZero() {
        // Marquee volume pin: 1.0f = unchanged. Drift to 0.0
        // silently mutes every fresh audio clip; drift to
        // 4.0 silently boosts to max amplification.
        assertEquals(
            1.0f,
            audioClip().volume,
            "Audio.volume MUST default to 1.0f (drift to 0.0 mutes; drift to 4.0 deafens)",
        )
    }

    @Test fun audioFadeInSecondsDefaultsToZero() {
        // Marquee fade pin: 0.0f disables. Drift to non-zero
        // silently fades every fresh audio clip's head.
        assertEquals(
            0.0f,
            audioClip().fadeInSeconds,
            "Audio.fadeInSeconds MUST default to 0.0f (no fade)",
        )
    }

    @Test fun audioFadeOutSecondsDefaultsToZero() {
        assertEquals(0.0f, audioClip().fadeOutSeconds)
    }

    @Test fun audioTransformsDefaultsToEmptyList() {
        assertEquals(emptyList(), audioClip().transforms)
    }

    @Test fun audioSourceBindingDefaultsToEmptySet() {
        assertEquals(emptySet(), audioClip().sourceBinding)
    }

    // ── Clip.Text field defaults ────────────────────────────

    @Test fun textStyleDefaultsToTextStyleEmpty() {
        // Pin: TextStyle() default — every other TextStyle
        // field is itself defaulted (covered separately).
        assertEquals(TextStyle(), textClip().style)
    }

    @Test fun textTransformsDefaultsToEmptyList() {
        assertEquals(emptyList(), textClip().transforms)
    }

    @Test fun textSourceBindingDefaultsToEmptySet() {
        assertEquals(emptySet(), textClip().sourceBinding)
    }

    @Test fun textSourceRangeOverrideIsAlwaysNull() {
        // Marquee Text-routing pin: per source line 89,
        // Text overrides sourceRange to ALWAYS null. Drift
        // to allow a non-null sourceRange would silently
        // break engine routing — Text clips don't have a
        // source media to range into.
        assertNull(
            textClip().sourceRange,
            "Clip.Text.sourceRange MUST be null (Text clips have no source media)",
        )
    }

    // ── Transform identity defaults ─────────────────────────

    @Test fun transformDefaultsAreIdentityTransform() {
        // Marquee identity pin: drift in any of the 6 fields
        // would silently scramble every fresh clip's geometry.
        val t = Transform()
        assertEquals(0f, t.translateX, "translateX MUST default to 0")
        assertEquals(0f, t.translateY, "translateY MUST default to 0")
        assertEquals(1f, t.scaleX, "scaleX MUST default to 1 (NOT 0)")
        assertEquals(1f, t.scaleY, "scaleY MUST default to 1 (NOT 0)")
        assertEquals(0f, t.rotationDeg, "rotationDeg MUST default to 0")
        assertEquals(1f, t.opacity, "opacity MUST default to 1 (visible, NOT 0 invisible)")
    }

    @Test fun transformDefaultIsRoundTripStable() {
        // Pin: serialized identity transform survives round-
        // trip via JsonConfig.default.
        val identity = Transform()
        val encoded = json.encodeToString(Transform.serializer(), identity)
        val decoded = json.decodeFromString(Transform.serializer(), encoded)
        assertEquals(identity, decoded)
    }

    // ── Filter field defaults ───────────────────────────────

    @Test fun filterParamsDefaultsToEmptyMap() {
        val f = Filter(name = "blur")
        assertEquals(
            emptyMap(),
            f.params,
            "Filter.params MUST default to emptyMap (numeric-only knob bag)",
        )
    }

    @Test fun filterAssetIdDefaultsToNull() {
        val f = Filter(name = "blur")
        assertNull(
            f.assetId,
            "Filter.assetId MUST default to null (most filters are numeric-only; LUT uses this slot)",
        )
    }

    @Test fun filterRequiresOnlyName() {
        // Pin: name is the only required field.
        val f = Filter(name = "vignette")
        assertEquals("vignette", f.name)
        assertTrue(f.params.isEmpty())
        assertNull(f.assetId)
    }

    // ── TextStyle field defaults ────────────────────────────

    @Test fun textStyleFontFamilyDefaultsToSystem() {
        // Pin: "system" maps to the platform's system font
        // (San Francisco on macOS, Roboto on Android, etc.).
        // Drift to a specific font silently changes every
        // fresh text overlay's appearance.
        assertEquals("system", TextStyle().fontFamily)
    }

    @Test fun textStyleFontSizeDefaultsToFortyEightPoints() {
        // Marquee size pin: 48f = readable subtitle size at
        // 1080p. Drift to 12f / 24f silently shrinks every
        // text overlay below readability.
        assertEquals(
            48f,
            TextStyle().fontSize,
            "TextStyle.fontSize MUST default to 48f (drift silently shrinks overlays)",
        )
    }

    @Test fun textStyleColorDefaultsToWhite() {
        // Marquee color pin: white = visible against most
        // video. Drift to "#000000" silently makes text
        // overlays invisible on dark scenes.
        assertEquals(
            "#FFFFFF",
            TextStyle().color,
            "TextStyle.color MUST default to '#FFFFFF' (visible against most video)",
        )
    }

    @Test fun textStyleBackgroundColorDefaultsToNull() {
        // Pin: null = no background (transparent). Drift to
        // a specific color silently adds a background to
        // every text overlay.
        assertNull(
            TextStyle().backgroundColor,
            "TextStyle.backgroundColor MUST default to null (transparent)",
        )
    }

    @Test fun textStyleBoldAndItalicDefaultToFalse() {
        val ts = TextStyle()
        assertEquals(false, ts.bold)
        assertEquals(false, ts.italic)
    }

    // ── Serialization round-trip pins ──────────────────────

    @Test fun videoClipRoundTripPreservesDefaults() {
        val original = videoClip()
        val encoded = json.encodeToString(Clip.serializer(), original)
        val decoded = json.decodeFromString(Clip.serializer(), encoded) as Clip.Video
        assertEquals(original, decoded)
        // Defaults preserved.
        assertEquals(emptyList(), decoded.transforms)
        assertEquals(emptyList(), decoded.filters)
        assertEquals(emptySet(), decoded.sourceBinding)
        assertNull(decoded.updatedAtEpochMs)
    }

    @Test fun audioClipRoundTripPreservesDefaults() {
        val original = audioClip()
        val encoded = json.encodeToString(Clip.serializer(), original)
        val decoded = json.decodeFromString(Clip.serializer(), encoded) as Clip.Audio
        assertEquals(original, decoded)
        assertEquals(1.0f, decoded.volume)
        assertEquals(0.0f, decoded.fadeInSeconds)
        assertEquals(0.0f, decoded.fadeOutSeconds)
    }

    @Test fun textClipRoundTripPreservesDefaults() {
        val original = textClip()
        val encoded = json.encodeToString(Clip.serializer(), original)
        val decoded = json.decodeFromString(Clip.serializer(), encoded) as Clip.Text
        assertEquals(original, decoded)
        // sourceRange is the override — null on Text.
        assertNull(decoded.sourceRange)
    }

    @Test fun transformAtIdentityIsTheSameAsDefaultConstructor() {
        // Sister sanity pin: Transform() == Transform with all
        // identity values supplied.
        val explicit = Transform(
            translateX = 0f, translateY = 0f,
            scaleX = 1f, scaleY = 1f,
            rotationDeg = 0f, opacity = 1f,
        )
        assertEquals(Transform(), explicit)
    }

    @Test fun textStyleAtDefaultIsTheSameAsExplicitDefaultValues() {
        val explicit = TextStyle(
            fontFamily = "system",
            fontSize = 48f,
            color = "#FFFFFF",
            backgroundColor = null,
            bold = false,
            italic = false,
        )
        assertEquals(TextStyle(), explicit)
    }

    @Test fun minimalClipVideoJsonHonorsDefaults() {
        // Marquee back-compat pin: encoding a Video with
        // only required fields produces JSON that decodes
        // back with every default honored. (Hand-writing the
        // JSON would couple the test to the exact wire shape
        // for TimeRange / Duration; round-tripping the
        // canonical encode is the same back-compat
        // guarantee — drift in defaults surfaces here.)
        val original = videoClip()
        val encoded = json.encodeToString(Clip.serializer(), original)
        val decoded = json.decodeFromString(Clip.serializer(), encoded) as Clip.Video
        // Every default came back at its documented value.
        assertEquals(emptyList(), decoded.transforms)
        assertEquals(emptyList(), decoded.filters)
        assertEquals(emptySet(), decoded.sourceBinding)
        assertNull(decoded.updatedAtEpochMs)
        // Encoded JSON does NOT contain default field names
        // (encodeDefaults=false), so back-compat reads of
        // legacy bundles without those fields work.
        assertTrue(
            "transforms" !in encoded,
            "default transforms (empty) MUST be omitted from encoded JSON; got: $encoded",
        )
        assertTrue(
            "filters" !in encoded,
            "default filters (empty) MUST be omitted from encoded JSON",
        )
        assertTrue(
            "sourceBinding" !in encoded,
            "default sourceBinding (empty) MUST be omitted from encoded JSON",
        )
        assertTrue(
            "updatedAtEpochMs" !in encoded,
            "default updatedAtEpochMs (null) MUST be omitted from encoded JSON",
        )
    }
}
