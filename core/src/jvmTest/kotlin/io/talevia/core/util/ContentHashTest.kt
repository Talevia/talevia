package io.talevia.core.util

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.SourceRef
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Direct tests for [fnv1a64Hex] and [contentHashOf] — the FNV-1a 64-bit
 * content fingerprint that every `SourceNode.contentHash` derives from.
 * Cycle 82 audit found this module zero-direct-test (6 transitive refs
 * via SourceNode tests).
 *
 * The hash function is a load-bearing correctness primitive for the
 * VISION §3.2 stale-clip detection: a regression that breaks
 * determinism would mark every node "changed" on every read, firing
 * regenerate cascades. A regression that accidentally collides nodes
 * with different kinds but same body would let staleness propagate
 * across unrelated source graphs.
 */
class ContentHashTest {

    @Test fun emptyInputProducesCanonicalFnvHash() {
        // FNV-1a 64-bit offset basis (0xCBF29CE484222325) is the empty-
        // input result. Pin so a refactor accidentally changing the
        // initial seed value catches immediately.
        assertEquals("cbf29ce484222325", fnv1a64Hex(""))
    }

    @Test fun outputIsAlways16HexCharsZeroPaddedFromLeft() {
        // Pin: kdoc says "padStart(16, '0')". Some inputs hash to a
        // value with high zero bits, which would render as <16 hex
        // chars without the pad. Deterministic length is a downstream
        // invariant for storage + display.
        // Find a few inputs and assert length.
        for (input in listOf("", "a", "x", "hello", "0", "0000000000000000")) {
            val hex = fnv1a64Hex(input)
            assertEquals(16, hex.length, "fnv1a64Hex('$input') = '$hex' is not 16 chars")
            // Lower-case hex.
            assertEquals(hex, hex.lowercase(), "fnv1a64Hex must emit lowercase hex")
        }
    }

    @Test fun sameInputAlwaysHashesSame() {
        // Determinism: two calls with same input return same bytes.
        // Trivially true today since the function is pure, but pin so
        // a future "cache the hash" refactor (e.g. stateful computer)
        // breaks loudly if it accidentally introduces non-determinism.
        repeat(5) {
            assertEquals(fnv1a64Hex("hello world"), fnv1a64Hex("hello world"))
        }
    }

    @Test fun differentInputsHashToDifferentValues() {
        // Sanity: changing one bit of input changes the output. FNV-1a
        // 64 has good avalanche; pin a few near-neighbour pairs.
        assertNotEquals(fnv1a64Hex("a"), fnv1a64Hex("b"))
        assertNotEquals(fnv1a64Hex("hello"), fnv1a64Hex("Hello"))
        assertNotEquals(fnv1a64Hex("foo"), fnv1a64Hex("foo "))
        assertNotEquals(fnv1a64Hex(""), fnv1a64Hex(" "))
    }

    @Test fun contentHashOfIsStableForIdenticalInputs() {
        // Determinism on the composite path. Same (kind, body, parents)
        // → same hash, every time.
        val body = buildJsonObject { put("name", "Mei") }
        val parents = listOf(SourceRef(SourceNodeId("p1")))
        val first = contentHashOf("character_ref", body, parents)
        val second = contentHashOf("character_ref", body, parents)
        assertEquals(first, second)
    }

    @Test fun differentKindWithSameBodyHashesDifferently() {
        // Pin the load-bearing collision-safety property: a `kind`
        // change must produce a different hash, even if `body` and
        // `parents` are identical. Otherwise nodes of different
        // semantic types could share a hash and cause stale-detection
        // cross-talk.
        val body = JsonObject(emptyMap())
        val parents = emptyList<SourceRef>()
        val a = contentHashOf("character_ref", body, parents)
        val b = contentHashOf("style_bible", body, parents)
        assertNotEquals(a, b, "different kinds with same body must hash differently")
    }

    @Test fun pipeInKindCannotForgeCollisionWithDifferentKindBodySplit() {
        // The kdoc commits: "Use the JSON primitive for `kind` so a kind
        // string containing `|` cannot forge a collision with a different
        // `kind,body` split." Pin: a kind containing `|` followed by what
        // looks like a body must NOT collide with a (legitimate kind,
        // legitimate body) pair where the legitimate kind happens to
        // match the prefix.
        val body = buildJsonObject { put("x", 1) }
        // Forge attempt: kind with `|` baked in, body trivial.
        val malicious = contentHashOf("character_ref|{\"x\":1}", JsonNull, emptyList())
        // Legitimate: kind="character_ref", body={"x":1}.
        val legitimate = contentHashOf("character_ref", body, emptyList())
        assertNotEquals(
            malicious,
            legitimate,
            "JSON-encoded kind prevents `|`-injection collision per kdoc",
        )
    }

    @Test fun differentBodyWithSameKindHashesDifferently() {
        val parents = emptyList<SourceRef>()
        val a = contentHashOf("character_ref", buildJsonObject { put("name", "Mei") }, parents)
        val b = contentHashOf("character_ref", buildJsonObject { put("name", "Lea") }, parents)
        assertNotEquals(a, b)
    }

    @Test fun differentParentListHashesDifferently() {
        // Order in parents matters because `ListSerializer` preserves
        // order. Pin so a refactor accidentally sorting parents
        // doesn't silently coalesce nodes that should be distinct.
        val body = JsonObject(emptyMap())
        val a = contentHashOf("clip", body, listOf(SourceRef(SourceNodeId("a"))))
        val b = contentHashOf("clip", body, listOf(SourceRef(SourceNodeId("b"))))
        val c = contentHashOf(
            "clip",
            body,
            listOf(SourceRef(SourceNodeId("a")), SourceRef(SourceNodeId("b"))),
        )
        val cReversed = contentHashOf(
            "clip",
            body,
            listOf(SourceRef(SourceNodeId("b")), SourceRef(SourceNodeId("a"))),
        )
        assertNotEquals(a, b)
        assertNotEquals(a, c)
        assertNotEquals(c, cReversed, "parent list order matters — different orders hash differently")
    }

    @Test fun emptyParentsAndEmptyBodyStillProduceStableHash() {
        // Edge: a leaf node with empty body + empty parents must still
        // produce a canonical, stable hash. Used by genre-template seed
        // helpers that scaffold "blank" nodes.
        val a = contentHashOf("character_ref", JsonObject(emptyMap()), emptyList())
        val b = contentHashOf("character_ref", JsonObject(emptyMap()), emptyList())
        assertEquals(a, b, "blank node hash must be deterministic")
    }
}
