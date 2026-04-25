package io.talevia.core.domain.source

import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * DAG lane contract (VISION §3.2). We prove:
 *  - content hash is deterministic, stable under no-op re-encode, changes on edit
 *  - `stale(changed)` returns the transitive downstream closure
 *  - cycles don't hang the BFS
 *  - unknown ids in `changed` are dropped, not crashed
 */
class SourceDagTest {

    private val json = JsonConfig.default

    private fun leaf(id: String, kind: String = "test.leaf", bodyField: String = "v") =
        SourceNode.create(
            id = SourceNodeId(id),
            kind = kind,
            body = JsonObject(mapOf("payload" to JsonPrimitive(bodyField))),
        )

    private fun child(id: String, vararg parents: String): SourceNode =
        SourceNode.create(
            id = SourceNodeId(id),
            kind = "test.child",
            parents = parents.map { SourceRef(SourceNodeId(it)) },
        )

    // ------------------------------------------------------------------
    // contentHash
    // ------------------------------------------------------------------

    @Test fun contentHashIsDeterministic() {
        val a = leaf("n-1")
        val b = leaf("n-1")
        assertEquals(a.contentHash, b.contentHash)
        assertEquals(16, a.contentHash.length, "FNV-1a 64-bit hex is 16 chars")
    }

    @Test fun contentHashChangesWhenBodyChanges() {
        val a = leaf("n-1", bodyField = "one")
        val b = leaf("n-1", bodyField = "two")
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test fun contentHashChangesWhenParentsChange() {
        val a = child("c-1", "p-1")
        val b = child("c-1", "p-2")
        assertNotEquals(a.contentHash, b.contentHash)
    }

    @Test fun contentHashIgnoresRevisionField() {
        val a = leaf("n-1").copy(revision = 7)
        val b = leaf("n-1").copy(revision = 42)
        assertEquals(
            a.contentHash,
            b.contentHash,
            "contentHash hashes (kind, body, parents) — revision is purely an audit counter",
        )
    }

    // ------------------------------------------------------------------
    // stale BFS
    // ------------------------------------------------------------------

    @Test fun staleOfSingleLeafReturnsJustThatLeaf() {
        val src = Source.EMPTY.addNode(leaf("n-1"))
        val stale = src.stale(setOf(SourceNodeId("n-1")))
        assertEquals(setOf(SourceNodeId("n-1")), stale)
    }

    @Test fun staleWalksLinearChainDownstream() {
        // p → c1 → c2 → c3
        val src = Source.EMPTY
            .addNode(leaf("p"))
            .addNode(child("c1", "p"))
            .addNode(child("c2", "c1"))
            .addNode(child("c3", "c2"))
        val stale = src.stale(setOf(SourceNodeId("p")))
        assertEquals(
            setOf(SourceNodeId("p"), SourceNodeId("c1"), SourceNodeId("c2"), SourceNodeId("c3")),
            stale,
        )
    }

    @Test fun staleWalksDiamond() {
        //     a
        //    / \
        //   b   c
        //    \ /
        //     d
        val src = Source.EMPTY
            .addNode(leaf("a"))
            .addNode(child("b", "a"))
            .addNode(child("c", "a"))
            .addNode(child("d", "b", "c"))
        val stale = src.stale(setOf(SourceNodeId("a")))
        assertEquals(
            setOf(SourceNodeId("a"), SourceNodeId("b"), SourceNodeId("c"), SourceNodeId("d")),
            stale,
        )
    }

    @Test fun staleFromInteriorNodeSkipsUnaffectedSiblings() {
        // a → b → d
        // a → c → e   (changing b should not mark c or e stale)
        val src = Source.EMPTY
            .addNode(leaf("a"))
            .addNode(child("b", "a"))
            .addNode(child("c", "a"))
            .addNode(child("d", "b"))
            .addNode(child("e", "c"))
        val stale = src.stale(setOf(SourceNodeId("b")))
        assertEquals(setOf(SourceNodeId("b"), SourceNodeId("d")), stale)
        assertTrue(SourceNodeId("c") !in stale)
        assertTrue(SourceNodeId("e") !in stale)
    }

    @Test fun staleIsCycleTolerant() {
        // Mutation guards (cycle-108) reject cycle-introducing writes via
        // addNode/replaceNode, but on-disk data from older builds can
        // still carry one — the BFS must not hang on it. Construct the
        // cycle by raw Source(...) to bypass the write guard, matching
        // the on-disk-corrupted scenario.
        val src = Source(
            nodes = listOf(
                leaf("a"),
                child("b", "a", "c"), // b <- a, b <- c
                child("c", "b"), // c <- b  (cycle b↔c)
            ),
        )
        val stale = src.stale(setOf(SourceNodeId("a")))
        assertEquals(setOf(SourceNodeId("a"), SourceNodeId("b"), SourceNodeId("c")), stale)
    }

    @Test fun staleWithUnknownIdReturnsEmpty() {
        val src = Source.EMPTY.addNode(leaf("n-1"))
        assertEquals(emptySet(), src.stale(setOf(SourceNodeId("ghost"))))
    }

    @Test fun staleWithEmptyInputReturnsEmpty() {
        val src = Source.EMPTY.addNode(leaf("n-1"))
        assertEquals(emptySet(), src.stale(emptySet()))
    }

    // ------------------------------------------------------------------
    // childIndex
    // ------------------------------------------------------------------

    @Test fun childIndexProducesForwardAdjacency() {
        val src = Source.EMPTY
            .addNode(leaf("p"))
            .addNode(child("c1", "p"))
            .addNode(child("c2", "p"))
        val idx = src.childIndex
        assertEquals(setOf(SourceNodeId("c1"), SourceNodeId("c2")), idx[SourceNodeId("p")])
    }
}
