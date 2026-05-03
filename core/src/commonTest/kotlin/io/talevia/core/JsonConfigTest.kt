package io.talevia.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct regression tests for [JsonConfig] — the single canonical
 * `Json` instance every serialization path uses (142 transitive
 * references across the codebase per the cycle 83 audit). Each of the
 * 4 property settings is load-bearing across every persisted blob:
 *
 * - `classDiscriminator = "type"` — polymorphic sealed-type JSON shape.
 * - `ignoreUnknownKeys = true` — forward-compat for newly-added fields.
 * - `encodeDefaults = false` — old blobs without new fields decode
 *   correctly (default values aren't roundtripped into the wire).
 * - `prettyPrintIndent = "  "` (2 spaces) — git-friendly bundle diffs.
 *
 * A regression to any setting silently corrupts serialization across
 * every `Project` / `Session` / `Lockfile` / `Source` blob. Direct
 * tests catch the regression at the unit boundary instead of waiting
 * for an integration test to fail somewhere downstream.
 */
class JsonConfigTest {

    @Serializable
    sealed class Shape {
        @Serializable
        @SerialName("circle")
        data class Circle(val radius: Double) : Shape()

        @Serializable
        @SerialName("square")
        data class Square(val side: Double = 1.0) : Shape()
    }

    @Serializable
    data class WithDefault(val name: String = "default-name", val count: Int = 0)

    @Serializable
    data class Pinned(val a: Int, val b: String)

    @Test fun classDiscriminatorIsTypeForBothInstances() {
        // Pin the literal discriminator string. Every sealed-type JSON
        // emit / parse uses this. A regression to "kind" / "_type" /
        // anything else would silently break every existing Project /
        // Session blob on disk.
        val circle: Shape = Shape.Circle(radius = 3.0)
        val text = JsonConfig.default.encodeToString(Shape.serializer(), circle)
        assertTrue(
            "\"type\":\"circle\"" in text,
            "default JsonConfig must use classDiscriminator='type'; got: $text",
        )
        val pretty = JsonConfig.prettyPrint.encodeToString(Shape.serializer(), circle)
        assertTrue(
            "\"type\": \"circle\"" in pretty,
            "prettyPrint JsonConfig must use classDiscriminator='type'; got: $pretty",
        )
    }

    @Test fun ignoreUnknownKeysAcceptsForwardCompatFields() {
        // `ignoreUnknownKeys = true`: a future-version blob carrying
        // a field this build doesn't know about must decode without
        // throwing. Critical for cross-version persistence.
        val futureBlob = """{"a": 7, "b": "ok", "addedInV2": "ignored"}"""
        val parsed = JsonConfig.default.decodeFromString(Pinned.serializer(), futureBlob)
        assertEquals(Pinned(a = 7, b = "ok"), parsed)
    }

    @Test fun encodeDefaultsFalseStripsDefaultValuesFromOutput() {
        // `encodeDefaults = false`: the default value should NOT appear
        // in the encoded JSON. Pin so a refactor accidentally enabling
        // encodeDefaults=true wouldn't bloat every blob with redundant
        // default-equal fields. Old blobs WITHOUT the new field would
        // decode the same, but new writes would carry it — drift.
        val obj = WithDefault() // both fields default
        val text = JsonConfig.default.encodeToString(WithDefault.serializer(), obj)
        // With encodeDefaults=false + both fields at default, the JSON
        // body is empty `{}`.
        assertEquals("{}", text)

        val partialDefault = WithDefault(name = "explicit", count = 0)
        val text2 = JsonConfig.default.encodeToString(WithDefault.serializer(), partialDefault)
        // `count` stays default → omitted; `name` is explicit → emitted.
        assertTrue("\"name\":\"explicit\"" in text2, "non-default field emitted")
        assertFalse("\"count\":0" in text2, "default int field stripped: $text2")
    }

    @Test fun prettyPrintUsesTwoSpaceIndent() {
        // `prettyPrintIndent = "  "`: git-friendly. The bundle's
        // talevia.json is committed with this format; a refactor
        // changing the indent (e.g. tabs / 4 spaces) would produce
        // a one-time massive diff every user pays.
        val obj = Pinned(a = 1, b = "x")
        val pretty = JsonConfig.prettyPrint.encodeToString(Pinned.serializer(), obj)
        // The pretty output should have a blank-2-space indent on each field line.
        // Match a line that begins with exactly 2 spaces then a quote.
        val twoSpaceFieldLines = pretty.lines().count { it.startsWith("  \"") }
        assertEquals(
            2,
            twoSpaceFieldLines,
            "prettyPrint must use 2-space indent for each of the 2 fields; got:\n$pretty",
        )
    }

    @Test fun defaultIsCompactSingleLineNoExtraWhitespace() {
        // `prettyPrint = false` for the default: no newlines / extra
        // whitespace. Pin so the on-the-wire serialization stays compact
        // for SQL blobs / SSE event payloads where size matters.
        val text = JsonConfig.default.encodeToString(Pinned.serializer(), Pinned(1, "x"))
        assertFalse("\n" in text, "default JSON must not contain newlines (prettyPrint=false)")
        assertEquals("""{"a":1,"b":"x"}""", text)
    }

    @Test fun roundTripDefaultPreservesValuesIncludingDefaults() {
        // Even though encodeDefaults=false strips defaults on encode,
        // a round trip must reconstitute them via the constructor.
        val original = WithDefault() // all defaults
        val encoded = JsonConfig.default.encodeToString(WithDefault.serializer(), original)
        val decoded = JsonConfig.default.decodeFromString(WithDefault.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test fun roundTripPolymorphicSealedTypesPreserveDiscriminator() {
        // Polymorphic round trip: encode → decode → equal. Pins the
        // (discriminator + sealed) wiring across any future kotlinx.
        // serialization version bump.
        val shape: Shape = Shape.Square(side = 2.5)
        val text = JsonConfig.default.encodeToString(Shape.serializer(), shape)
        val decoded = JsonConfig.default.decodeFromString(Shape.serializer(), text)
        assertEquals(shape, decoded)
    }

    @Test fun unknownDiscriminatorValueIsRejected() {
        // Negative pin: a blob with a discriminator that isn't a
        // registered subclass must NOT silently fall through to a
        // default — it should throw. This catches the foot-gun where
        // a typo in `@SerialName` would silently start producing the
        // wrong concrete type.
        val unknownTypeBlob = """{"type":"hexagon","sides":6}"""
        var threw = false
        try {
            JsonConfig.default.decodeFromString(Shape.serializer(), unknownTypeBlob)
        } catch (_: Throwable) {
            threw = true
        }
        assertTrue(threw, "decoding unknown discriminator value must fail loud, not silently")
    }

    @Test fun jsonObjectRoundTripsWithoutAlteringFields() {
        // Anti-corruption check: a JsonObject passed through encode →
        // decode must be byte-equal except for whitespace. Used by
        // tools persisting opaque `body: JsonObject` payloads.
        val obj = JsonObject(
            mapOf(
                "z" to JsonPrimitive("last"),
                "a" to JsonPrimitive(1),
                "m" to JsonPrimitive(true),
            ),
        )
        val text = JsonConfig.default.encodeToString(JsonObject.serializer(), obj)
        val decoded = JsonConfig.default.decodeFromString(JsonObject.serializer(), text)
        assertEquals(obj, decoded)
    }
}
