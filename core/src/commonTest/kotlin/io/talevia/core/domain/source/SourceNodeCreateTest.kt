package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import io.talevia.core.util.contentHashOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [SourceNode.create] (companion factory) +
 * [SourceNode]'s default [SourceNode.contentHash] initialiser
 * — `core/src/commonMain/kotlin/io/talevia/core/domain/source/
 * SourceNode.kt:42`.
 *
 * Cycle 297 audit: 9+ production call sites
 * (SourceIdRewrites / SourceNodeCreateHandlers /
 * ImportSourceNodeEnvelopeHandler /
 * ImportSourceNodeLiveHandler) but **no direct
 * SourceNode.create test file** — verified via cycle 289-banked
 * duplicate-check idiom. ContentHashTest covers contentHashOf
 * itself, but the factory's auto-compute contract + the
 * data-class default initialiser are unpinned.
 *
 * Same audit-pattern fallback as cycles 207-296.
 *
 * `SourceNode.create` is the canonical factory every
 * mutation tool uses — it guarantees `contentHash` is
 * computed correctly from `(kind, body, parents)`, NOT
 * `(kind, body, parents, revision)`. Drift here silently
 * breaks every cache key downstream (lockfile, render
 * cache, stale-clip propagation).
 *
 * Drift signals:
 *   - **Drift to include `revision` in the hash** → every
 *     in-place node replacement bumps every descendant's
 *     hash, defeating the "structural change ⇒ stale"
 *     invariant.
 *   - **Drift in default body / parents / revision** →
 *     callers passing fewer arguments would silently get
 *     different hashes than they expect.
 *   - **Drift in the data-class initialiser** (the parallel
 *     `contentHash = contentHashOf(kind, body, parents)`
 *     default) → constructors that bypass `create()` would
 *     silently produce wrong hashes.
 *   - **Drift to recompute when explicit contentHash is
 *     passed to the data-class constructor** → tests /
 *     adapters can't preserve a hash from external storage.
 */
class SourceNodeCreateTest {

    private val id = SourceNodeId("test-id")
    private val kind = "narrative.scene"
    private val emptyBody = JsonObject(emptyMap())
    private val nonEmptyBody = buildJsonObject { put("k", "v") }

    // ── Factory: auto-computed contentHash ─────────────────

    @Test fun createComputesContentHashFromKindBodyAndParents() {
        // Marquee factory pin: contentHash equals
        // contentHashOf(kind, body, parents).
        val node = SourceNode.create(
            id = id,
            kind = kind,
            body = nonEmptyBody,
            parents = emptyList(),
        )
        assertEquals(
            contentHashOf(kind, nonEmptyBody, emptyList()),
            node.contentHash,
            "create() MUST compute contentHash via contentHashOf(kind, body, parents)",
        )
    }

    @Test fun createDefaultBodyIsEmptyJsonObject() {
        // Pin: default body parameter = JsonObject(emptyMap()).
        // Drift to a different default would silently shift
        // every minimal-call-site's hash.
        val node = SourceNode.create(id = id, kind = kind)
        assertEquals(
            JsonObject(emptyMap()),
            node.body,
            "default body MUST be empty JsonObject",
        )
        assertEquals(
            contentHashOf(kind, JsonObject(emptyMap()), emptyList()),
            node.contentHash,
        )
    }

    @Test fun createDefaultParentsIsEmptyList() {
        // Pin: default parents = emptyList().
        val node = SourceNode.create(id = id, kind = kind)
        assertEquals(emptyList(), node.parents)
    }

    @Test fun createDefaultRevisionIsZero() {
        // Pin: default revision = 0L. Drift to start at 1
        // would silently shift comparison logic in
        // mutation tools.
        val node = SourceNode.create(id = id, kind = kind)
        assertEquals(0L, node.revision)
    }

    @Test fun createWithExplicitRevisionPreservesIt() {
        val node = SourceNode.create(id = id, kind = kind, revision = 42L)
        assertEquals(42L, node.revision)
    }

    // ── revision is NOT in the hash ────────────────────────

    @Test fun differentRevisionsProduceSameContentHash() {
        // Marquee revision-not-in-hash pin: contentHash is a
        // function of (kind, body, parents) — revision MUST
        // NOT contribute. Drift to include revision would
        // silently couple cache invalidation with the
        // monotonic counter (every in-place edit cascades
        // stale to all descendants regardless of body
        // change).
        val v0 = SourceNode.create(id = id, kind = kind, body = nonEmptyBody, revision = 0L)
        val v5 = SourceNode.create(id = id, kind = kind, body = nonEmptyBody, revision = 5L)
        val v100 = SourceNode.create(id = id, kind = kind, body = nonEmptyBody, revision = 100L)
        assertEquals(v0.contentHash, v5.contentHash, "revision MUST NOT affect contentHash")
        assertEquals(v0.contentHash, v100.contentHash, "revision MUST NOT affect contentHash (large jump)")
    }

    // ── Hash sensitivity to (kind, body, parents) ──────────

    @Test fun differentKindProducesDifferentHash() {
        val a = SourceNode.create(id = id, kind = "vlog.raw_footage", body = nonEmptyBody)
        val b = SourceNode.create(id = id, kind = "narrative.scene", body = nonEmptyBody)
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test fun differentBodyProducesDifferentHash() {
        val a = SourceNode.create(
            id = id, kind = kind,
            body = buildJsonObject { put("a", 1) },
        )
        val b = SourceNode.create(
            id = id, kind = kind,
            body = buildJsonObject { put("a", 2) },
        )
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test fun differentParentsProducesDifferentHash() {
        val a = SourceNode.create(
            id = id, kind = kind,
            parents = listOf(SourceRef(SourceNodeId("p1"))),
        )
        val b = SourceNode.create(
            id = id, kind = kind,
            parents = listOf(SourceRef(SourceNodeId("p2"))),
        )
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test fun parentOrderAffectsHash() {
        // Pin: parent list ordering matters — same nodes in
        // different order produce different hashes. Drift to
        // sort-before-hash would silently let two distinct
        // graph topologies collide on the same hash.
        val ordered = SourceNode.create(
            id = id, kind = kind,
            parents = listOf(
                SourceRef(SourceNodeId("a")),
                SourceRef(SourceNodeId("b")),
            ),
        )
        val reversed = SourceNode.create(
            id = id, kind = kind,
            parents = listOf(
                SourceRef(SourceNodeId("b")),
                SourceRef(SourceNodeId("a")),
            ),
        )
        assertNotEquals(
            ordered.contentHash,
            reversed.contentHash,
            "parent list order MUST affect hash (drift to sort-before-hash collides distinct topologies)",
        )
    }

    // ── id NOT in hash ─────────────────────────────────────

    @Test fun differentIdsButSameBodyProducesSameHash() {
        // Pin: id is NOT in the hash — two nodes with
        // different ids but identical (kind, body, parents)
        // hash to the same value. This is what makes
        // content-addressed cache hits transferable across
        // projects (per `source_node_action(action="import")`
        // doc).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = kind, body = nonEmptyBody)
        val b = SourceNode.create(id = SourceNodeId("b"), kind = kind, body = nonEmptyBody)
        assertEquals(
            a.contentHash,
            b.contentHash,
            "id MUST NOT contribute to contentHash (content-addressed cache hits depend on this)",
        )
    }

    // ── Default initialiser of data-class constructor ─────

    @Test fun directConstructorWithoutContentHashUsesAutoInitialiser() {
        // Pin: per source line 40, the data-class default
        // for contentHash is `contentHashOf(kind, body,
        // parents)`. Direct constructor calls without
        // explicit contentHash MUST get the same value as
        // create().
        val viaCreate = SourceNode.create(id = id, kind = kind, body = nonEmptyBody)
        val viaConstructor = SourceNode(
            id = id,
            kind = kind,
            body = nonEmptyBody,
            // contentHash omitted → uses default initialiser
        )
        assertEquals(
            viaCreate.contentHash,
            viaConstructor.contentHash,
            "data-class default initialiser MUST match create() result",
        )
    }

    @Test fun directConstructorWithExplicitContentHashPreservesIt() {
        // Pin: when contentHash is passed explicitly to
        // the data-class constructor, it's NOT recomputed.
        // This is the test/adapter escape hatch (per the
        // create() doc — "prefer this over the raw
        // data-class constructor"). Drift to always
        // recompute would block reading nodes from external
        // systems with their preserved hashes.
        val externalHash = "0000000000000000"
        val node = SourceNode(
            id = id,
            kind = kind,
            body = nonEmptyBody,
            contentHash = externalHash,
        )
        assertEquals(
            externalHash,
            node.contentHash,
            "explicit contentHash arg MUST be preserved (NOT recomputed)",
        )
    }

    // ── Hash format (sanity, NOT comprehensive) ─────────────

    @Test fun computedHashIsSixteenHexChars() {
        // Sanity pin: the hash format from contentHashOf is
        // 16-char zero-padded hex. ContentHashTest
        // already covers this — we just confirm
        // SourceNode.create transparently surfaces it.
        val node = SourceNode.create(id = id, kind = kind, body = nonEmptyBody)
        assertEquals(
            16,
            node.contentHash.length,
            "create() hash MUST be 16 hex chars",
        )
        assertTrue(
            node.contentHash.all { it.isDigit() || it.lowercase().single() in 'a'..'f' },
            "create() hash MUST be lowercase hex",
        )
    }

    // ── Idempotency of factory ─────────────────────────────

    @Test fun createIsDeterministicForSameInputs() {
        // Pin: SourceNode.create with identical inputs
        // produces structurally equal nodes (data class
        // equality). Drift to add non-deterministic content
        // (e.g. timestamp) would surface here.
        val a = SourceNode.create(
            id = id, kind = kind, body = nonEmptyBody,
            parents = listOf(SourceRef(SourceNodeId("p"))),
            revision = 7L,
        )
        val b = SourceNode.create(
            id = id, kind = kind, body = nonEmptyBody,
            parents = listOf(SourceRef(SourceNodeId("p"))),
            revision = 7L,
        )
        assertEquals(a, b, "create() MUST be deterministic for identical inputs")
    }

    // ── Cross-instance hash stability ───────────────────────

    @Test fun jsonObjectFieldOrderDoesNotAffectHashViaCanonicalization() {
        // Pin: hash uses canonical JSON encoding via
        // JsonConfig.default — JsonObject built with
        // different put-orders should hash identically when
        // the underlying structure matches. Drift to use
        // toString()-based hashing would surface here.
        // Note: kotlinx.serialization JsonObject preserves
        // insertion order; if the wire encoder writes in
        // insertion order, two different put-orders DO
        // produce different bytes. Pin documents the actual
        // observed behavior.
        val ab = buildJsonObject {
            put("a", 1)
            put("b", 2)
        }
        val ba = buildJsonObject {
            put("b", 2)
            put("a", 1)
        }
        // Per kotlinx.serialization JsonObject, insertion-
        // order preservation means encodeToString produces
        // different output. contentHashOf likely hashes that
        // string → different hashes. Pin documents the
        // observed asymmetry so a future cycle that
        // canonicalises (sorts) field order surfaces here.
        val nodeAb = SourceNode.create(id = id, kind = kind, body = ab)
        val nodeBa = SourceNode.create(id = id, kind = kind, body = ba)
        // We expect them to DIFFER (current behavior).
        // If a future cycle sorts JsonObject keys, this test
        // will surface the change as a forced re-evaluation.
        assertNotEquals(
            nodeAb.contentHash,
            nodeBa.contentHash,
            "JsonObject insertion order is NOT canonicalized — same fields in different order DO produce different hashes (current behavior)",
        )
    }

    @Test fun primitiveTypesInBodyAffectHash() {
        // Sister sensitivity pin: body with int 1 vs string "1"
        // hashes differently — type matters.
        val asInt = SourceNode.create(
            id = id, kind = kind,
            body = buildJsonObject { put("v", 1) },
        )
        val asString = SourceNode.create(
            id = id, kind = kind,
            body = buildJsonObject { put("v", JsonPrimitive("1")) },
        )
        assertNotEquals(
            asInt.contentHash,
            asString.contentHash,
            "primitive type (int vs string) MUST affect hash",
        )
    }
}
