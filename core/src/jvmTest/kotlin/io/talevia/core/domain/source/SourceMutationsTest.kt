package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [Source.addNode] / [Source.replaceNode] /
 * [Source.removeNode] —
 * `core/domain/source/SourceMutations.kt`. The pure value-
 * level mutation primitives that every genre-layer write
 * goes through. Cycle 174 audit: 94 LOC, 0 direct test
 * refs (SourceCycleDetectionTest covers the cycle-rejection
 * paths but the mutation-level contracts — id-collision
 * rejection, id-preservation on replace, revision +
 * contentHash bumping, removeNode-doesn't-cascade — were
 * never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`addNode` requires unique id; `replaceNode` requires
 *    existing id AND id-preservation.** Drift to "silent
 *    overwrite" on addNode would let two callers race-
 *    write the same id; drift to "allow id-change on
 *    replaceNode" would break every reference held by
 *    other nodes' parents lists. Both throw
 *    `IllegalArgumentException` via `require`.
 *
 * 2. **All three mutations bump `Source.revision`.**
 *    Per kdoc: revision is "a per-project monotonic
 *    counter bumped on every structural mutation." Drift
 *    to "skip bump on remove" would break the
 *    revision-based observability that downstream
 *    consumers ([deepContentHashOf], staleness reports)
 *    rely on.
 *
 * 3. **addNode + replaceNode bump the touched node's
 *    revision AND recompute contentHash from `(kind,
 *    body, parents)`; removeNode does NOT cascade
 *    dangling refs.** Per kdoc: "Does not cascade —
 *    cleaning up dangling SourceRef entries on other
 *    nodes is the DAG lane's job." Drift in either
 *    direction (skip bump-on-write OR cascade-on-remove)
 *    would silently change the layered contract genre
 *    code relies on.
 */
class SourceMutationsTest {

    private fun nodeId(s: String) = SourceNodeId(s)

    private fun node(
        id: String,
        body: String = "v0",
        parents: List<String> = emptyList(),
    ): SourceNode = SourceNode.create(
        id = nodeId(id),
        kind = "test",
        body = JsonObject(mapOf("v" to JsonPrimitive(body))),
        parents = parents.map { SourceRef(nodeId(it)) },
    )

    private fun emptySource(): Source = Source.EMPTY

    private fun source(vararg nodes: SourceNode): Source = Source(nodes = nodes.toList())

    // ── addNode: happy path + revision bumps ──────────────────

    @Test fun addNodeAppendsNodeAndBumpsSourceRevision() {
        val src = emptySource()
        val n = node("a")
        val next = src.addNode(n)
        assertEquals(1L, next.revision, "Source.revision bumped from 0 → 1")
        assertEquals(1, next.nodes.size, "node appended")
        assertEquals(nodeId("a"), next.nodes[0].id)
    }

    @Test fun addNodeBumpsTheAppendedNodeRevisionAndContentHash() {
        // Marquee bumpedForWrite pin: a node passed in
        // with revision=0 and any contentHash gets
        // its revision bumped to 1 AND its contentHash
        // recomputed from (kind, body, parents). Drift to
        // "skip bump" would let genre code forge revision
        // numbers; drift to "skip contentHash recompute"
        // would let a stale contentHash land on disk.
        val n = SourceNode(
            id = nodeId("a"),
            kind = "test",
            body = JsonObject(mapOf("v" to JsonPrimitive("v1"))),
            revision = 0,
            // Plant a deliberately wrong contentHash to
            // verify recomputation.
            contentHash = "deadbeef",
        )
        val next = emptySource().addNode(n)
        val stored = next.nodes.single()
        assertEquals(1L, stored.revision, "node revision bumped to 1")
        assertNotEquals(
            "deadbeef",
            stored.contentHash,
            "contentHash recomputed from (kind, body, parents); drift to keep input would let stale hash stick",
        )
        assertEquals(16, stored.contentHash.length, "FNV-1a hex format")
    }

    // ── addNode: id-collision rejection ───────────────────────

    @Test fun addNodeWithCollidingIdThrowsIllegalArgumentException() {
        // Marquee collision-rejection pin: addNode is
        // append-only — it must NOT silently overwrite an
        // existing id. The kdoc explicitly directs
        // overwriting callers to replaceNode. Drift to
        // "silent overwrite" would let two concurrent
        // tools race-write the same id with last-writer-
        // winning; drift to "skip the check" would let
        // `nodes` accumulate duplicates.
        val src = source(node("a"))
        val ex = assertFailsWith<IllegalArgumentException> {
            src.addNode(node("a"))
        }
        assertTrue(
            "already exists" in (ex.message ?: ""),
            "expected collision phrase; got: ${ex.message}",
        )
        assertTrue(
            "replaceNode" in (ex.message ?: ""),
            "expected hint about replaceNode; got: ${ex.message}",
        )
    }

    // ── replaceNode: happy path + revision bumps ──────────────

    @Test fun replaceNodeRetainsSameNodeCountAndBumpsRevision() {
        val src = source(node("a", body = "v1"))
        val next = src.replaceNode(nodeId("a")) { it.copy(body = JsonObject(mapOf("v" to JsonPrimitive("v2")))) }
        assertEquals(1, next.nodes.size, "still one node — replace, not add")
        assertEquals(
            src.revision + 1,
            next.revision,
            "Source.revision bumps on replace",
        )
    }

    @Test fun replaceNodeBumpsTouchedNodeRevisionAndRecomputesContentHash() {
        // Pin: a single replaceNode call advances the
        // node's own revision by one AND recomputes
        // contentHash. Drift to "skip bump" would let
        // genre code observe stale (revision, contentHash)
        // pairs on every replace.
        val initial = source(node("a", body = "v1"))
        val originalNode = initial.nodes.single()
        val originalRevision = originalNode.revision
        val originalHash = originalNode.contentHash

        val next = initial.replaceNode(nodeId("a")) {
            it.copy(body = JsonObject(mapOf("v" to JsonPrimitive("v2"))))
        }
        val updated = next.nodes.single()
        assertEquals(
            originalRevision + 1,
            updated.revision,
            "node revision bumped exactly once",
        )
        assertNotEquals(
            originalHash,
            updated.contentHash,
            "contentHash recomputed because body changed",
        )
    }

    @Test fun replaceNodeWithoutBodyChangeStillBumpsRevisionButReusesContentHash() {
        // Pin: revision bump is unconditional (per
        // bumpedForWrite). contentHash is content-derived
        // — if the (kind, body, parents) tuple is
        // unchanged, the hash naturally stays the same
        // (deterministic from contentHashOf). The
        // identity-replace test verifies these two
        // dimensions evolve independently.
        val initial = source(node("a", body = "v1"))
        val originalNode = initial.nodes.single()
        val next = initial.replaceNode(nodeId("a")) { it /* no-op replace */ }
        val updated = next.nodes.single()
        assertEquals(
            originalNode.revision + 1,
            updated.revision,
            "revision bumps even on no-op replace",
        )
        assertEquals(
            originalNode.contentHash,
            updated.contentHash,
            "contentHash unchanged for content-equivalent replace",
        )
    }

    // ── replaceNode: missing id + id-change rejection ─────────

    @Test fun replaceNodeWithMissingIdThrowsIllegalArgumentException() {
        val ex = assertFailsWith<IllegalArgumentException> {
            emptySource().replaceNode(nodeId("ghost")) { it }
        }
        assertTrue(
            "not found" in (ex.message ?: ""),
            "expected not-found phrase; got: ${ex.message}",
        )
    }

    @Test fun replaceNodeMustNotChangeId() {
        // Marquee id-preservation pin. Drift would break
        // every reference held by other nodes' parents
        // lists — a "rename" that silently dangling-refs
        // every consumer. Per kdoc: "we assert identity
        // preservation to keep references stable."
        val src = source(node("a"))
        val ex = assertFailsWith<IllegalArgumentException> {
            src.replaceNode(nodeId("a")) { it.copy(id = nodeId("a-renamed")) }
        }
        assertTrue(
            "must not change id" in (ex.message ?: ""),
            "expected id-preserve phrase; got: ${ex.message}",
        )
    }

    // ── removeNode: happy path + revision bump + non-cascade ──

    @Test fun removeNodeShrinksNodesAndBumpsRevision() {
        val src = source(node("a"), node("b"))
        val next = src.removeNode(nodeId("a"))
        assertEquals(1, next.nodes.size)
        assertContentEquals(
            listOf(nodeId("b")),
            next.nodes.map { it.id },
            "remaining node preserved",
        )
        assertEquals(src.revision + 1, next.revision, "Source.revision bumps on remove")
    }

    @Test fun removeNodeDoesNotCascadeDanglingRefs() {
        // Marquee non-cascade pin: removeNode does NOT
        // touch other nodes' `parents` lists, even if
        // they reference the removed node. The kdoc
        // explicitly says "cleaning up dangling SourceRef
        // entries on other nodes is the DAG lane's job."
        // Drift to "auto-cascade" would change the
        // contract genre code relies on (some genres want
        // dangling refs to surface as warnings rather
        // than be silently scrubbed).
        val src = source(
            node("parent"),
            node("child", parents = listOf("parent")),
        )
        val next = src.removeNode(nodeId("parent"))
        val remainingChild = next.nodes.single { it.id == nodeId("child") }
        assertEquals(
            listOf(SourceRef(nodeId("parent"))),
            remainingChild.parents,
            "child's parents list still references the removed node — non-cascading",
        )
    }

    @Test fun removeNodeWithMissingIdThrowsIllegalArgumentException() {
        val ex = assertFailsWith<IllegalArgumentException> {
            emptySource().removeNode(nodeId("ghost"))
        }
        assertTrue(
            "not found" in (ex.message ?: ""),
            "expected not-found phrase; got: ${ex.message}",
        )
    }

    @Test fun removeNodeDoesNotBumpOtherNodesRevisions() {
        // Pin: removing node A does NOT touch node B's
        // revision counter. Drift to "stamp every node on
        // every Source revision" would break B's stable
        // identity for staleness consumers.
        val src = source(
            node("a"),
            node("b"),
        )
        val nodeBBefore = src.nodes.single { it.id == nodeId("b") }
        val next = src.removeNode(nodeId("a"))
        val nodeBAfter = next.nodes.single { it.id == nodeId("b") }
        assertEquals(
            nodeBBefore.revision,
            nodeBAfter.revision,
            "node B's revision unchanged when sibling A removed",
        )
        assertEquals(
            nodeBBefore.contentHash,
            nodeBAfter.contentHash,
            "node B's contentHash unchanged when sibling A removed",
        )
    }

    // ── ordering preservation ────────────────────────────────

    @Test fun replaceNodePreservesIndexInNodesList() {
        // Pin: replaceNode patches the node IN PLACE at
        // its existing index. Drift to "remove + append"
        // would shuffle ordering and break consumers
        // that rely on insertion order (e.g. UI lists,
        // `select=nodes` paginated reads).
        val src = source(node("a"), node("b"), node("c"))
        val next = src.replaceNode(nodeId("b")) {
            it.copy(body = JsonObject(mapOf("v" to JsonPrimitive("v-new"))))
        }
        assertContentEquals(
            listOf(nodeId("a"), nodeId("b"), nodeId("c")),
            next.nodes.map { it.id },
            "order preserved: a, b, c (NOT a, c, b)",
        )
    }

    @Test fun addNodeAppendsToTailNotMiddle() {
        // Pin: addNode is append-only. Drift to "insert
        // sorted by id" would shuffle the list.
        val src = source(node("zebra"), node("apple"))
        val next = src.addNode(node("middle"))
        assertContentEquals(
            listOf(nodeId("zebra"), nodeId("apple"), nodeId("middle")),
            next.nodes.map { it.id },
            "appended to end, not sorted",
        )
    }

    // ── revision monotonicity across mixed operations ─────────

    @Test fun mixedOperationsAdvanceRevisionMonotonically() {
        // Pin: every mutation bumps revision exactly once
        // — three mutations → revision 0 → 3. Drift to
        // "double-bump" or "skip bump on remove" would
        // surface here.
        val s0 = emptySource()
        val s1 = s0.addNode(node("a"))
        val s2 = s1.replaceNode(nodeId("a")) {
            it.copy(body = JsonObject(mapOf("v" to JsonPrimitive("v2"))))
        }
        val s3 = s2.removeNode(nodeId("a"))
        assertEquals(0L, s0.revision)
        assertEquals(1L, s1.revision)
        assertEquals(2L, s2.revision)
        assertEquals(3L, s3.revision)
    }

    // ── purity: original Source unaffected ────────────────────

    @Test fun mutationsReturnNewSourceWithoutMutatingOriginal() {
        // Pin: copy-on-write semantics. The Source
        // returned by addNode / replaceNode / removeNode
        // is a NEW instance; the original is unchanged.
        // Drift to "in-place mutation" would break the
        // ProjectStore.mutate guarantee that snapshots
        // before/after a mutation can be safely held.
        val original = source(node("a"))
        val originalNodeCount = original.nodes.size
        val originalRevision = original.revision

        original.addNode(node("b"))
        original.replaceNode(nodeId("a")) {
            it.copy(body = JsonObject(mapOf("v" to JsonPrimitive("v-mut"))))
        }
        original.removeNode(nodeId("a"))

        // Original stays intact.
        assertEquals(originalNodeCount, original.nodes.size)
        assertEquals(originalRevision, original.revision)
        assertEquals(
            nodeId("a"),
            original.nodes.single().id,
            "original's node identity preserved",
        )
    }
}
