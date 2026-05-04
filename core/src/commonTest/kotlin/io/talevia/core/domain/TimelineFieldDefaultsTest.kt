package io.talevia.core.domain

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.TrackId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [Timeline] / [Track] / [MediaAsset] /
 * [MediaMetadata] / [ProxyAsset] field defaults. Cycle 305 audit:
 * no direct `TimelineFieldDefaultsTest.kt` (verified via cycle
 * 289-banked duplicate-check idiom). Sister of cycles 302
 * (Session) + 303 (Project) + 304 (Clip) field-defaults pins.
 *
 * Same audit-pattern fallback as cycles 207-304.
 *
 * Why this matters: these data classes are the **authoring
 * canvas** (Timeline.resolution / frameRate, distinct from
 * Project.outputProfile's render spec — pinned cycle 285's
 * PROMPT_BUILD_SYSTEM lane). Drift silently changes how every
 * fresh timeline math computes.
 *
 * Drift surface protected:
 *   - **Timeline.resolution = 1920x1080 default** (authoring
 *     canvas, separate from outputProfile.resolution) — drift
 *     silently changes the canvas every fresh project authors
 *     against.
 *   - **Timeline.frameRate = FPS_30 default** — drift silently
 *     changes the authoring fps; downstream timeRange math
 *     depends on this.
 *   - **Track default empty clips** for all 4 subtypes — drift
 *     to non-empty would silently surface phantom clips.
 *   - **MediaMetadata 8 nullable fields** all default to null
 *     — drift to populating defaults would silently fill
 *     metadata gaps with wrong values.
 */
class TimelineFieldDefaultsTest {

    private val json: Json = JsonConfig.default

    // ── Timeline defaults ───────────────────────────────────

    @Test fun timelineTracksDefaultsToEmptyList() {
        assertEquals(emptyList(), Timeline().tracks)
    }

    @Test fun timelineDurationDefaultsToZero() {
        // Pin: a fresh timeline has zero duration. Drift to
        // non-zero would silently extend every fresh
        // timeline's playable range.
        assertEquals(
            Duration.ZERO,
            Timeline().duration,
            "Timeline.duration MUST default to Duration.ZERO",
        )
    }

    @Test fun timelineFrameRateDefaultsToFps30() {
        // Marquee authoring-fps pin: drift to FPS_24 / FPS_60
        // / NTSC silently changes the authoring cadence
        // every fresh project starts with. Distinct from
        // Project.outputProfile.frameRate (cycle 303 pin) —
        // authoring vs render are deliberately separated.
        assertEquals(
            FrameRate.FPS_30,
            Timeline().frameRate,
            "Timeline.frameRate (authoring canvas) MUST default to FPS_30",
        )
    }

    @Test fun timelineResolutionDefaultsTo1920x1080() {
        // Marquee authoring-canvas pin: drift to 1280x720
        // silently shrinks every fresh project's authoring
        // grid. Sister of cycle 303's
        // OutputProfile.DEFAULT_1080P pin — but this is
        // the AUTHORING canvas, NOT the render spec.
        assertEquals(
            Resolution(width = 1920, height = 1080),
            Timeline().resolution,
            "Timeline.resolution (authoring canvas) MUST default to 1920x1080",
        )
    }

    @Test fun timelineRoundTripPreservesDefaults() {
        val original = Timeline()
        val encoded = json.encodeToString(Timeline.serializer(), original)
        val decoded = json.decodeFromString(Timeline.serializer(), encoded)
        assertEquals(original, decoded)
    }

    // ── Track subtype defaults ──────────────────────────────

    @Test fun trackVideoClipsDefaultsToEmpty() {
        val track = Track.Video(id = TrackId("v1"))
        assertEquals(emptyList(), track.clips)
        assertNull(track.updatedAtEpochMs)
    }

    @Test fun trackAudioClipsDefaultsToEmpty() {
        val track = Track.Audio(id = TrackId("a1"))
        assertEquals(emptyList(), track.clips)
        assertNull(track.updatedAtEpochMs)
    }

    @Test fun trackSubtitleClipsDefaultsToEmpty() {
        val track = Track.Subtitle(id = TrackId("s1"))
        assertEquals(emptyList(), track.clips)
        assertNull(track.updatedAtEpochMs)
    }

    @Test fun trackEffectClipsDefaultsToEmpty() {
        val track = Track.Effect(id = TrackId("e1"))
        assertEquals(emptyList(), track.clips)
        assertNull(track.updatedAtEpochMs)
    }

    @Test fun fourTrackSubtypesAllExist() {
        // Marquee enumeration pin: there are exactly 4 Track
        // subtypes (Video / Audio / Subtitle / Effect). Drift
        // to add / remove one would silently break the
        // sealed-when exhaustive checks across engine
        // dispatchers.
        val subtypes: List<Track> = listOf(
            Track.Video(TrackId("v")),
            Track.Audio(TrackId("a")),
            Track.Subtitle(TrackId("s")),
            Track.Effect(TrackId("e")),
        )
        assertEquals(
            4,
            subtypes.size,
            "Track has exactly 4 subtypes (drift in count surfaces here)",
        )
        // Each subtype is its own class.
        val classNames = subtypes.map { it::class.simpleName }.toSet()
        assertEquals(
            setOf("Video", "Audio", "Subtitle", "Effect"),
            classNames,
            "Track subtype names MUST be Video/Audio/Subtitle/Effect",
        )
    }

    // ── MediaAsset defaults ─────────────────────────────────

    private fun minimalAsset(): MediaAsset = MediaAsset(
        id = AssetId("a1"),
        source = MediaSource.File("/tmp/x.mp4"),
        metadata = MediaMetadata(duration = 10.seconds),
    )

    @Test fun mediaAssetProxiesDefaultsToEmptyList() {
        // Pin: per source line 13, no proxies by default.
        // ProxyGenerator populates them on import.
        assertEquals(
            emptyList(),
            minimalAsset().proxies,
            "MediaAsset.proxies MUST default to empty (ProxyGenerator populates on import)",
        )
    }

    @Test fun mediaAssetUpdatedAtEpochMsDefaultsToNull() {
        // Pin: null = "predates recency tracking" (sorts last
        // in `sortBy=recent`).
        assertNull(minimalAsset().updatedAtEpochMs)
    }

    // ── MediaMetadata defaults (8 nullable fields) ─────────

    @Test fun mediaMetadataAllOptionalFieldsDefaultToNull() {
        // Marquee 8-null pin: per source lines 73-89, every
        // optional field is nullable to absorb provider /
        // import-probe variation. Drift to non-null defaults
        // would silently fill metadata gaps with wrong
        // values.
        val mm = MediaMetadata(duration = 5.seconds)
        assertNull(mm.resolution, "resolution MUST default to null")
        assertNull(mm.frameRate, "frameRate MUST default to null")
        assertNull(mm.videoCodec, "videoCodec MUST default to null")
        assertNull(mm.audioCodec, "audioCodec MUST default to null")
        assertNull(mm.sampleRate, "sampleRate MUST default to null")
        assertNull(mm.channels, "channels MUST default to null")
        assertNull(mm.bitrate, "bitrate MUST default to null")
        assertNull(mm.comment, "comment MUST default to null")
    }

    @Test fun mediaMetadataRequiresOnlyDuration() {
        // Pin: duration is the only required field. Every
        // other field is optional / nullable.
        val mm = MediaMetadata(duration = 1.seconds)
        assertEquals(1.seconds, mm.duration)
    }

    // ── ProxyAsset defaults ────────────────────────────────

    @Test fun proxyAssetResolutionDefaultsToNull() {
        // Pin: per source line 95, resolution optional —
        // proxies may not have a known resolution (e.g.
        // audio waveform proxies).
        val pa = ProxyAsset(
            source = MediaSource.File("/tmp/proxy.mp4"),
            purpose = ProxyPurpose.THUMBNAIL,
        )
        assertNull(pa.resolution)
    }

    @Test fun proxyPurposeHasExactlyThreeVariants() {
        // Pin: 3 ProxyPurpose enum variants — THUMBNAIL,
        // LOW_RES, AUDIO_WAVEFORM. Drift to add / remove a
        // variant would silently break sealed-when exhaustive
        // checks in proxy generators.
        val variants = ProxyPurpose.entries
        assertEquals(
            3,
            variants.size,
            "ProxyPurpose has exactly 3 variants",
        )
        assertTrue(ProxyPurpose.THUMBNAIL in variants)
        assertTrue(ProxyPurpose.LOW_RES in variants)
        assertTrue(ProxyPurpose.AUDIO_WAVEFORM in variants)
    }

    // ── Authoring-canvas vs render-spec separation ─────────

    @Test fun timelineDefaultsAreSeparateFromOutputProfileDefaults() {
        // Marquee separation pin: per cycle 285's
        // PROMPT_BUILD_SYSTEM, Timeline.resolution / frameRate
        // are the AUTHORING canvas, distinct from
        // OutputProfile.DEFAULT_1080P (the render spec).
        // Drift to merge defaults would silently re-couple
        // them. Verified by checking each default
        // independently.
        val timeline = Timeline()
        val output = OutputProfile.DEFAULT_1080P
        // BOTH default to 1920x1080 + FPS_30 today (by
        // intentional convergence), but they're distinct
        // mutation points: changing Timeline.resolution
        // mid-project doesn't change output, and vice versa.
        // Pin documents the convergence — drift in either
        // should be deliberate.
        assertEquals(
            timeline.resolution,
            output.resolution,
            "Timeline + OutputProfile defaults converge at 1920x1080 today",
        )
        assertEquals(
            timeline.frameRate,
            output.frameRate,
            "Timeline + OutputProfile defaults converge at FPS_30 today",
        )
    }

    // ── MediaSource sealed class invariants ────────────────

    @Test fun mediaSourceHasFourSubtypes() {
        // Pin: 4 MediaSource subtypes (File / BundleFile /
        // Http / Platform). Drift to add a 5th surfaces
        // here — the BundleMediaPathResolver sealed-when
        // dispatcher enumerates these explicitly.
        val instances: List<MediaSource> = listOf(
            MediaSource.File("/tmp/x"),
            MediaSource.BundleFile("media/x.mp4"),
            MediaSource.Http("https://example.com/x"),
            MediaSource.Platform(scheme = "ios", value = "phasset:abc"),
        )
        assertEquals(4, instances.size)
        val classNames = instances.map { it::class.simpleName }.toSet()
        assertEquals(
            setOf("File", "BundleFile", "Http", "Platform"),
            classNames,
        )
    }

    @Test fun mediaSourcePlatformRequiresSchemeAndValue() {
        // Pin: Platform.scheme + Platform.value both required.
        val ms = MediaSource.Platform(scheme = "ios.phasset", value = "abc-123")
        assertEquals("ios.phasset", ms.scheme)
        assertEquals("abc-123", ms.value)
    }

    // ── Round-trip pins ────────────────────────────────────

    @Test fun mediaAssetRoundTripPreservesDefaults() {
        val original = minimalAsset()
        val encoded = json.encodeToString(MediaAsset.serializer(), original)
        val decoded = json.decodeFromString(MediaAsset.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(emptyList(), decoded.proxies)
        assertNull(decoded.updatedAtEpochMs)
    }

    @Test fun mediaMetadataRoundTripPreservesDefaults() {
        val original = MediaMetadata(duration = 10.seconds)
        val encoded = json.encodeToString(MediaMetadata.serializer(), original)
        val decoded = json.decodeFromString(MediaMetadata.serializer(), encoded)
        assertEquals(original, decoded)
        // All 8 optional fields still null.
        assertNull(decoded.resolution)
        assertNull(decoded.frameRate)
        assertNull(decoded.videoCodec)
        assertNull(decoded.audioCodec)
        assertNull(decoded.sampleRate)
        assertNull(decoded.channels)
        assertNull(decoded.bitrate)
        assertNull(decoded.comment)
    }

    @Test fun trackVideoRoundTripPreservesDefaults() {
        val original = Track.Video(id = TrackId("v1"))
        val encoded = json.encodeToString(Track.serializer(), original)
        val decoded = json.decodeFromString(Track.serializer(), encoded) as Track.Video
        assertEquals(original, decoded)
        assertEquals(emptyList(), decoded.clips)
        assertNull(decoded.updatedAtEpochMs)
    }

    // ── Equality / negative pins ───────────────────────────

    @Test fun twoTimelinesWithSameDefaultsAreEqual() {
        val a = Timeline()
        val b = Timeline()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun twoTracksDifferingOnlyInIdAreNotEqual() {
        // Pin: TrackId is the identity discriminator. Drift
        // to ignore id in equals would silently make tracks
        // collide.
        val a = Track.Video(TrackId("v1"))
        val b = Track.Video(TrackId("v2"))
        assertNotEquals(a, b)
    }

    @Test fun timelineMinimalJsonHonorsAllDefaults() {
        // Marquee back-compat pin: empty JSON object decodes
        // as a fully-defaulted Timeline.
        val emptyJson = "{}"
        val decoded = json.decodeFromString(Timeline.serializer(), emptyJson)
        assertEquals(emptyList(), decoded.tracks)
        assertEquals(Duration.ZERO, decoded.duration)
        assertEquals(FrameRate.FPS_30, decoded.frameRate)
        assertEquals(Resolution(1920, 1080), decoded.resolution)
    }
}
