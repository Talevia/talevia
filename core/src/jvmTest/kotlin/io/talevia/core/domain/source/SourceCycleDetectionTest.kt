package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for cycle detection on the Source DAG. Two surfaces:
 *
 *  - [Source.cycleAt] — pure read-side detection, returns the cycle
 *    or null. Backs the mutation guards.
 *  - `addNode` / `replaceNode` mutation guards — reject cycle-introducing
 *    writes so the on-disk DAG always stays acyclic.
 *
 * A third surface ([deepContentHashOf] cycle defense) sits behind the
 * write guards — it's only reachable on already-corrupted on-disk
 * state — and is covered by [SourceDeepContentHashCycleSafetyTest].
 */
class SourceCycleDetectionTest {

    private fun nid(s: String) = SourceNodeId(s)

    private fun node(id: String, parents: List<String> = emptyList()): SourceNode =
        SourceNode.create(
            id = nid(id),
            kind = "test",
            parents = parents.map { SourceRef(nid(it)) },
        )

    @Test fun emptySourceHasNoCycle() {
        assertNull(Source.EMPTY.cycleAt())
    }

    @Test fun singleNodeNoParentsHasNoCycle() {
        val source = Source(nodes = listOf(node("a")))
        assertNull(source.cycleAt())
    }

    @Test fun linearChainHasNoCycle() {
        // a -> b -> c (parents point to upstream, so c.parents = [b], b.parents = [a])
        val source = Source(
            nodes = listOf(
                node("a"),
                node("b", parents = listOf("a")),
                node("c", parents = listOf("b")),
            ),
        )
        assertNull(source.cycleAt())
    }

    @Test fun diamondShapedDagHasNoCycle() {
        // a -> b, a -> c, b -> d, c -> d
        val source = Source(
            nodes = listOf(
                node("a"),
                node("b", parents = listOf("a")),
                node("c", parents = listOf("a")),
                node("d", parents = listOf("b", "c")),
            ),
        )
        assertNull(source.cycleAt())
    }

    @Test fun selfReferenceDetectedAsSingleNodeCycle() {
        // a -> a (a.parents includes a)
        val source = Source(nodes = listOf(node("a", parents = listOf("a"))))
        val cycle = source.cycleAt()
        assertEquals(listOf(nid("a"), nid("a")), cycle)
    }

    @Test fun twoNodeCycleDetected() {
        // a -> b -> a
        val source = Source(
            nodes = listOf(
                node("a", parents = listOf("b")),
                node("b", parents = listOf("a")),
            ),
        )
        val cycle = source.cycleAt()!!
        // Cycle should close on itself; first and last must match.
        assertEquals(cycle.first(), cycle.last())
        assertEquals(setOf(nid("a"), nid("b")), cycle.toSet())
    }

    @Test fun threeNodeCycleDetected() {
        // a -> b -> c -> a
        val source = Source(
            nodes = listOf(
                node("a", parents = listOf("b")),
                node("b", parents = listOf("c")),
                node("c", parents = listOf("a")),
            ),
        )
        val cycle = source.cycleAt()!!
        assertEquals(cycle.first(), cycle.last())
        assertEquals(setOf(nid("a"), nid("b"), nid("c")), cycle.toSet())
    }

    @Test fun danglingParentRefIsNotACycle() {
        // a -> nonexistent — dangling refs are a separate diagnostic,
        // not a cycle. cycleAt should ignore them.
        val source = Source(nodes = listOf(node("a", parents = listOf("ghost"))))
        assertNull(source.cycleAt())
    }

    @Test fun replaceNodeIntroducingCycleThrows() {
        // Acyclic start: a -> b. Then change a.parents to [b], creating
        // a <-> b cycle.
        val source = Source(
            nodes = listOf(
                node("a"),
                node("b", parents = listOf("a")),
            ),
        )
        val err = assertFailsWith<IllegalStateException> {
            source.replaceNode(nid("a")) { current ->
                current.copy(parents = listOf(SourceRef(nid("b"))))
            }
        }
        assertTrue(
            "cycle" in err.message.orEmpty(),
            "error message must mention cycle, got: ${err.message}",
        )
        assertTrue("replaceNode(a)" in err.message.orEmpty())
    }

    @Test fun replaceNodePreservingAcyclicGraphSucceeds() {
        val source = Source(nodes = listOf(node("a"), node("b", parents = listOf("a"))))
        val updated = source.replaceNode(nid("b")) { current ->
            // Keep parents=[a], just bump body. Should NOT throw.
            current.copy(body = current.body)
        }
        assertEquals(source.revision + 1, updated.revision)
    }

    @Test fun addNodeWithSelfReferenceParentThrows() {
        // addNode where the new node lists itself as a parent — direct
        // self-cycle. Should be rejected.
        val empty = Source.EMPTY
        val err = assertFailsWith<IllegalStateException> {
            empty.addNode(node("a", parents = listOf("a")))
        }
        assertTrue("cycle" in err.message.orEmpty())
        assertTrue("addNode(a)" in err.message.orEmpty())
    }
}
