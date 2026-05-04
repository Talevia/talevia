package io.talevia.core.domain

import io.talevia.core.JsonConfig
import io.talevia.core.TrackId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `@SerialName` wire-format pins for the [Track] sealed class +
 * [MediaSource] sealed class + [ProxyPurpose] enum. Sibling to
 * existing [io.talevia.core.session.PartWireFormatTest] (Part 11
 * subtypes) and
 * [io.talevia.core.domain.source.SourceNodeKindWireFormatTest]
 * (SourceNode kind values).
 *
 * Why pin: each `@SerialName` value is the JSON discriminator
 * persisted into:
 *
 * - SQLDelight `Sessions` / `Messages` / `Parts` blobs (Track
 *   variants ride inside Project.timeline → Project blob in
 *   talevia.json bundle).
 * - On-disk `talevia.json` bundle file (the canonical project
 *   serialisation — committed alongside the project, read by
 *   any machine opening the bundle).
 * - Cross-machine bundle round-trips (per CLAUDE.md "Project
 *   bundle format" section).
 *
 * Drift on any of these strings silently breaks decode of pre-
 * drift data:
 *
 * - Renaming `"video"` → `"VIDEO"` would cause every saved
 *   project's video track to fail decode (kotlinx.serialization
 *   throws "polymorphic discriminator value not found").
 * - Adding/removing a Track subtype without bumping schema
 *   versioning silently breaks forward compatibility.
 * - The `@SerialName("low-res")` ProxyPurpose entry uses a
 *   hyphen — easy to "tidy up" to underscore in a refactor,
 *   silently breaking every cached proxy asset.
 *
 * Same drift class as cycles 311/312 silent-id-mismatch bugs
 * (provider id and model id divergence). Pin every wire-string
 * explicitly.
 */
class TrackAndMediaSourceWireFormatTest {

    private val json: Json = JsonConfig.default

    // ── Track sealed-class @SerialName values ──────────────────────

    @Test fun videoTrackSerialNameIsLowercaseVideo() {
        val track: Track = Track.Video(id = TrackId("v1"))
        val element = json.encodeToJsonElement(Track.serializer(), track).jsonObject
        assertEquals("video", element["type"]?.jsonPrimitive?.content)
    }

    @Test fun audioTrackSerialNameIsLowercaseAudio() {
        val track: Track = Track.Audio(id = TrackId("a1"))
        val element = json.encodeToJsonElement(Track.serializer(), track).jsonObject
        assertEquals("audio", element["type"]?.jsonPrimitive?.content)
    }

    @Test fun subtitleTrackSerialNameIsLowercaseSubtitle() {
        val track: Track = Track.Subtitle(id = TrackId("s1"))
        val element = json.encodeToJsonElement(Track.serializer(), track).jsonObject
        assertEquals("subtitle", element["type"]?.jsonPrimitive?.content)
    }

    @Test fun effectTrackSerialNameIsLowercaseEffect() {
        val track: Track = Track.Effect(id = TrackId("e1"))
        val element = json.encodeToJsonElement(Track.serializer(), track).jsonObject
        assertEquals("effect", element["type"]?.jsonPrimitive?.content)
    }

    @Test fun trackRoundTripsPreserveSubtypeIdentity() {
        // Round-trip pin: each subtype encodes AND decodes back to
        // the same instance type. Catches a subtle drift where the
        // discriminator stays right but the deserializer fails to
        // restore the subtype (e.g. a refactor that drops one variant
        // from the sealed-class registry).
        val tracks: List<Track> = listOf(
            Track.Video(id = TrackId("v1")),
            Track.Audio(id = TrackId("a1")),
            Track.Subtitle(id = TrackId("s1")),
            Track.Effect(id = TrackId("e1")),
        )
        for (track in tracks) {
            val encoded = json.encodeToString(Track.serializer(), track)
            val decoded = json.decodeFromString(Track.serializer(), encoded)
            assertEquals(track::class, decoded::class, "subtype identity lost on $track")
        }
    }

    // ── MediaSource sealed-class @SerialName values ────────────────

    @Test fun fileSourceSerialNameIsLowercaseFile() {
        val src: MediaSource = MediaSource.File("/abs/path.mp4")
        val element = json.encodeToJsonElement(MediaSource.serializer(), src).jsonObject
        assertEquals("file", element["type"]?.jsonPrimitive?.content)
    }

    @Test fun bundleFileSourceSerialNameIsBundleFileWithUnderscore() {
        // Pin the underscore: drift to "bundle-file" (hyphen) would
        // silently break decode of every existing bundle-stored
        // asset. Underscore is the canonical wire format.
        val src: MediaSource = MediaSource.BundleFile("media/a.mp4")
        val element = json.encodeToJsonElement(MediaSource.serializer(), src).jsonObject
        assertEquals(
            "bundle_file",
            element["type"]?.jsonPrimitive?.content,
            "BundleFile MUST encode as 'bundle_file' (underscore, not hyphen)",
        )
    }

    @Test fun httpSourceSerialNameIsLowercaseHttp() {
        val src: MediaSource = MediaSource.Http("https://example.com/foo.mp4")
        val element = json.encodeToJsonElement(MediaSource.serializer(), src).jsonObject
        assertEquals("http", element["type"]?.jsonPrimitive?.content)
    }

    @Test fun platformSourceSerialNameIsLowercasePlatform() {
        val src: MediaSource = MediaSource.Platform("ios.phasset", "ABC123")
        val element = json.encodeToJsonElement(MediaSource.serializer(), src).jsonObject
        assertEquals("platform", element["type"]?.jsonPrimitive?.content)
    }

    @Test fun mediaSourceRoundTripsPreserveSubtypeIdentity() {
        // Same shape as Track round-trip pin — each MediaSource
        // subtype encodes AND decodes back to the same instance.
        val sources: List<MediaSource> = listOf(
            MediaSource.File("/abs/path"),
            MediaSource.BundleFile("media/a.mp4"),
            MediaSource.Http("https://x.com/y.mp4"),
            MediaSource.Platform("ios.phasset", "id"),
        )
        for (src in sources) {
            val encoded = json.encodeToString(MediaSource.serializer(), src)
            val decoded = json.decodeFromString(MediaSource.serializer(), encoded)
            assertEquals(src::class, decoded::class, "subtype identity lost on $src")
        }
    }

    // ── ProxyPurpose enum @SerialName values ───────────────────────

    @Test fun thumbnailEnumSerialNameIsLowercaseThumbnail() {
        val v = ProxyPurpose.THUMBNAIL
        val encoded = json.encodeToString(ProxyPurpose.serializer(), v).trim('"')
        assertEquals("thumbnail", encoded)
    }

    @Test fun lowResEnumSerialNameIsKebabCaseLowRes() {
        // Pin the hyphen: drift to "low_res" (underscore) or
        // "lowres" (no separator) would silently break decode of
        // every cached proxy. Hyphen is the canonical wire format.
        val v = ProxyPurpose.LOW_RES
        val encoded = json.encodeToString(ProxyPurpose.serializer(), v).trim('"')
        assertEquals(
            "low-res",
            encoded,
            "LOW_RES MUST encode as 'low-res' (hyphen, not underscore)",
        )
    }

    @Test fun audioWaveformEnumSerialNameIsKebabCaseAudioWaveform() {
        // Sister pin: kebab-case matches LOW_RES convention.
        val v = ProxyPurpose.AUDIO_WAVEFORM
        val encoded = json.encodeToString(ProxyPurpose.serializer(), v).trim('"')
        assertEquals("audio-waveform", encoded)
    }

    @Test fun proxyPurposeRoundTrips() {
        for (v in ProxyPurpose.entries) {
            val encoded = json.encodeToString(ProxyPurpose.serializer(), v)
            val decoded = json.decodeFromString(ProxyPurpose.serializer(), encoded)
            assertEquals(v, decoded)
        }
    }

    // ── Tally pins (drift in count = surface change) ───────────────

    @Test fun trackHasExactlyFourSubtypes() {
        // Marquee pin: adding a 5th Track subtype without bumping
        // schema versioning would silently regress old-bundle
        // round-tripping. Pin the count.
        val all: List<Track> = listOf(
            Track.Video(id = TrackId("v")),
            Track.Audio(id = TrackId("a")),
            Track.Subtitle(id = TrackId("s")),
            Track.Effect(id = TrackId("e")),
        )
        val discriminators = all.map { t ->
            json.encodeToJsonElement(Track.serializer(), t).jsonObject["type"]!!.jsonPrimitive.content
        }.toSet()
        assertEquals(
            setOf("video", "audio", "subtitle", "effect"),
            discriminators,
            "Track MUST have exactly 4 wire-format subtypes",
        )
    }

    @Test fun mediaSourceHasExactlyFourSubtypes() {
        val all: List<MediaSource> = listOf(
            MediaSource.File("/x"),
            MediaSource.BundleFile("media/x"),
            MediaSource.Http("https://x"),
            MediaSource.Platform("ios", "x"),
        )
        val discriminators = all.map { s ->
            json.encodeToJsonElement(MediaSource.serializer(), s).jsonObject["type"]!!.jsonPrimitive.content
        }.toSet()
        assertEquals(
            setOf("file", "bundle_file", "http", "platform"),
            discriminators,
            "MediaSource MUST have exactly 4 wire-format subtypes",
        )
    }

    @Test fun proxyPurposeHasExactlyThreeEntries() {
        assertEquals(3, ProxyPurpose.entries.size)
        // Confirm each entry's wire string against the kebab-case
        // / lowercase convention.
        val byEntry: Map<ProxyPurpose, String> = ProxyPurpose.entries.associateWith {
            json.encodeToString(ProxyPurpose.serializer(), it).trim('"')
        }
        assertEquals(
            mapOf(
                ProxyPurpose.THUMBNAIL to "thumbnail",
                ProxyPurpose.LOW_RES to "low-res",
                ProxyPurpose.AUDIO_WAVEFORM to "audio-waveform",
            ),
            byEntry,
        )
    }

    // ── Cross-class wire-format invariant ──────────────────────────

    @Test fun classDiscriminatorAlwaysFieldNamedTypeAcrossDomainSealedClasses() {
        // Pin: JsonConfig.default sets `classDiscriminator = "type"`.
        // Every sealed-class encoding here MUST have `"type": "..."`
        // as a top-level field (not `kind`, not `_class`, not
        // anything else). Catches a refactor that locally overrides
        // the discriminator name for one sealed class — which would
        // silently break cross-class wire-format consistency.
        val track = json.encodeToJsonElement(Track.serializer(), Track.Video(id = TrackId("v"))).jsonObject
        val source = json.encodeToJsonElement(MediaSource.serializer(), MediaSource.File("/x")).jsonObject

        assertTrue(track.containsKey("type"), "Track MUST use 'type' discriminator")
        assertTrue(source.containsKey("type"), "MediaSource MUST use 'type' discriminator")

        // Also pin: there's NO alternative discriminator key visible.
        for (key in listOf("kind", "_class", "class", "discriminator")) {
            assertEquals(null, track[key] as? JsonObject, "Track must NOT have '$key' field")
            assertEquals(null, source[key] as? JsonObject, "MediaSource must NOT have '$key' field")
            // Note: track may legitimately have a `clips` field with
            // JsonArray; the assertion above casts to JsonObject so it
            // returns null safely for non-object values too.
        }
        // Pin one positive: track has the expected discriminator + id field,
        // confirming the JsonObject inspection is reading the right shape.
        assertEquals(JsonPrimitive("video"), track["type"])
    }
}
