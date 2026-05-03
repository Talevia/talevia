package io.talevia.core.domain.source

import io.talevia.core.SourceNodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [Source.cycleAt] —
 * `core/domain/source/CycleDetection.kt`. The iterative-DFS
 * cycle-detector that gates `Source.replaceNode` mutations
 * against introducing back-edges into the parent DAG.
 * Cycle 169 audit: 80 LOC, 0 direct test refs (the function
 * is exercised transitively through SourceMutations tests
 * but its own contracts — three-color DFS, cycle
 * reconstruction format, dangling-ref skip, multi-component
 * walk — were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Cycle reconstruction format: `[start, ..., closing
 *    on start]`.** Per kdoc: "`[A, B, C, A]` means `A → B
 *    → C → A`." The first and last elements are equal —
 *    this is the contract downstream error messages rely
 *    on for "cycle visualisation." Drift to "open path"
 *    `[A, B, C]` would silently break the user-facing
 *    diagnostic.
 *
 * 2. **Dangling parent refs SILENTLY SKIPPED, NOT thrown.**
 *    Per kdoc: "Skip dangling refs — they're a separate
 *    diagnostic surfaced by `project_query(select=
 *    validation)`, not a cycle." A ghost parent must not
 *    trigger false-cycle detection AND must not crash. The
 *    cycle-detector is the post-write gate; dangling-refs
 *    are caught earlier by `resolveParentRefs` (cycle 167).
 *
 * 3. **Three-color DFS distinguishes back-edges from
 *    cross-edges.** A diamond `A → B, A → C, B → D, C → D`
 *    is a DAG (D is fully explored when re-encountered via
 *    C's path) — must return null. Drift to two-color
 *    (visited/unvisited) would false-positive the diamond
 *    as a cycle.
 */
class CycleDetectionTest {

    // ── Helpers ────────────────────────────────────────────────

    private fun nodeId(s: String) = SourceNodeId(s)

    private fun node(id: String, parents: List<String> = emptyList()): SourceNode =
        SourceNode(
            id = nodeId(id),
            kind = "test",
            parents = parents.map { SourceRef(nodeId(it)) },
        )

    private fun source(vararg nodes: SourceNode): Source = Source(nodes = nodes.toList())

    // ── Acyclic cases → null ──────────────────────────────────

    @Test fun emptyGraphReturnsNull() {
        // Pin: zero-node graph is trivially acyclic.
        assertNull(Source.EMPTY.cycleAt())
    }

    @Test fun singleNodeWithNoParentsReturnsNull() {
        assertNull(source(node("a")).cycleAt())
    }

    @Test fun chainGraphReturnsNull() {
        // A → B → C (parent edges) — a chain is a DAG.
        val src = source(
            node("a", parents = listOf("b")),
            node("b", parents = listOf("c")),
            node("c"),
        )
        assertNull(src.cycleAt())
    }

    @Test fun forestOfDisjointTreesReturnsNull() {
        // Multiple roots, none connected to each other or
        // any cycle.
        val src = source(
            node("a", parents = listOf("a-parent")),
            node("a-parent"),
            node("b", parents = listOf("b-parent")),
            node("b-parent"),
            node("standalone"),
        )
        assertNull(src.cycleAt())
    }

    @Test fun diamondGraphReturnsNull() {
        // The marquee three-color pin: a diamond
        // A → B, A → C, B → D, C → D is a DAG. D gets
        // fully explored via B's path; when C's traversal
        // re-encounters D, the three-color state machine
        // sees D as state=2 (fully explored) — NOT state=1
        // (on stack) — and correctly skips it without
        // false-cycle.
        //
        // Drift to two-color (visited/unvisited) would
        // false-positive every diamond as a cycle.
        val src = source(
            node("a", parents = listOf("b", "c")),
            node("b", parents = listOf("d")),
            node("c", parents = listOf("d")),
            node("d"),
        )
        assertNull(src.cycleAt())
    }

    // ── Self-loop ──────────────────────────────────────────────

    @Test fun selfLoopReturnsCycleAA() {
        // Pin: a node referencing itself produces the
        // minimal cycle `[A, A]`. Drift to "skip self-
        // loops" would let `update_source_node_body` mark
        // a node as its own parent. Drift to non-`[A, A]`
        // shape (e.g. just `[A]`) would break the
        // documented "first and last equal" contract.
        val src = source(node("a", parents = listOf("a")))
        val cycle = src.cycleAt()
        assertEquals(listOf(nodeId("a"), nodeId("a")), cycle)
    }

    // ── Multi-node cycles ─────────────────────────────────────

    @Test fun twoNodeCycleProducesClosingPath() {
        // Pin: A → B → A produces `[A, B, A]` (or its
        // rotation depending on which root the DFS starts).
        // The contract is "first == last" + "all
        // intermediate nodes appear once."
        val src = source(
            node("a", parents = listOf("b")),
            node("b", parents = listOf("a")),
        )
        val cycle = src.cycleAt()
        assertNotNull(cycle, "two-node cycle detected")
        // First == last (closing edge).
        assertEquals(
            cycle.first(),
            cycle.last(),
            "cycle closes on its starting node",
        )
        // Length is 3 (start, middle, close).
        assertEquals(3, cycle.size)
        // Both A and B appear (rotation may vary).
        assertTrue(
            nodeId("a") in cycle && nodeId("b") in cycle,
            "both cycle members appear; got: $cycle",
        )
    }

    @Test fun threeNodeCycleProducesClosingPath() {
        // Pin: A → B → C → A produces a 4-element list
        // starting AND ending on the same node, with the
        // two intermediate nodes between.
        val src = source(
            node("a", parents = listOf("b")),
            node("b", parents = listOf("c")),
            node("c", parents = listOf("a")),
        )
        val cycle = src.cycleAt()
        assertNotNull(cycle, "three-node cycle detected")
        assertEquals(4, cycle.size, "cycle has 3 members + closing element")
        assertEquals(
            cycle.first(),
            cycle.last(),
            "first == last",
        )
        // Each of the three nodes appears.
        for (id in listOf("a", "b", "c")) {
            assertTrue(
                nodeId(id) in cycle,
                "node $id appears in reconstructed cycle: $cycle",
            )
        }
    }

    @Test fun cycleEmbeddedInLargerGraphIsFound() {
        // Pin: a graph with both acyclic + cyclic
        // components — the cycle should be reported, NOT
        // the acyclic part. Drift to "stop at first
        // visited" early would miss embedded cycles.
        val src = source(
            // Acyclic chain: x → y → z
            node("x", parents = listOf("y")),
            node("y", parents = listOf("z")),
            node("z"),
            // Cyclic: a → b → a
            node("a", parents = listOf("b")),
            node("b", parents = listOf("a")),
        )
        val cycle = src.cycleAt()
        assertNotNull(cycle, "cycle in mixed graph is detected")
        // The cycle nodes are a, b — NOT x/y/z.
        assertTrue(
            nodeId("a") in cycle && nodeId("b") in cycle,
            "cycle contains the cyclic-component nodes",
        )
        assertTrue(
            nodeId("x") !in cycle && nodeId("y") !in cycle && nodeId("z") !in cycle,
            "cycle does NOT contain acyclic-component nodes; got: $cycle",
        )
    }

    // ── Dangling refs silently skipped ────────────────────────

    @Test fun danglingParentRefDoesNotCauseFalseCycle() {
        // Marquee dangling-ref pin: a parent ref that
        // doesn't resolve in `byId` is skipped (per kdoc
        // "they're a separate diagnostic surfaced by
        // project_query(select=validation), not a cycle").
        // Drift to "throw" would crash the cycle detector
        // every time a node referenced a deleted parent.
        // Drift to "treat ghost as cycle-node" would surface
        // false cycles.
        val src = source(node("a", parents = listOf("ghost-parent")))
        assertNull(
            src.cycleAt(),
            "dangling parent → null (NOT a false cycle, NOT throw)",
        )
    }

    @Test fun chainWithDanglingMidParentDoesNotCrash() {
        // Pin: dangling refs anywhere in the graph (not
        // just at leaves) silently skipped. Drift to
        // partial-walk failure would intermittently miss
        // real cycles when ghosts also exist.
        val src = source(
            node("a", parents = listOf("b", "ghost-1")),
            node("b", parents = listOf("ghost-2")),
        )
        assertNull(src.cycleAt())
    }

    @Test fun cycleAdjacentToDanglingRefStillFound() {
        // Pin: ghosts don't mask real cycles. Mixed graph
        // with both: the real cycle still surfaces.
        val src = source(
            node("a", parents = listOf("b", "ghost")),
            node("b", parents = listOf("a")),
        )
        val cycle = src.cycleAt()
        assertNotNull(cycle, "real cycle found despite adjacent ghost")
        assertTrue(
            nodeId("a") in cycle && nodeId("b") in cycle,
            "cycle has the real members, not the ghost",
        )
        assertTrue(
            nodeId("ghost") !in cycle,
            "ghost is NOT included in the reported cycle",
        )
    }

    // ── Multi-component traversal ─────────────────────────────

    @Test fun walkContinuesPastFullyExploredComponentsToFindLaterCycle() {
        // Pin: the outer `for (root in byId.keys)` loop
        // continues past components that completed
        // acyclically. A graph where the FIRST root
        // explored is acyclic but a LATER root has a cycle
        // must still find the cycle.
        //
        // Drift to "early exit on first-acyclic-component"
        // would hide cycles in non-first components.
        val src = source(
            node("acyclic-1", parents = listOf("acyclic-2")),
            node("acyclic-2"),
            node("a", parents = listOf("b")),
            node("b", parents = listOf("a")),
        )
        val cycle = src.cycleAt()
        assertNotNull(cycle, "cycle in later component found")
        assertTrue(
            nodeId("a") in cycle && nodeId("b") in cycle,
        )
    }

    @Test fun manyDisjointAcyclicComponentsReturnNull() {
        // Pin: the walk handles many components without
        // re-exploring fully-explored ones (the
        // `state[root] == 2` skip). Drift would O(N²)
        // performance-wise but still return null —
        // pinning the result is the contract.
        val nodes = (1..20).flatMap { i ->
            listOf(
                node("root-$i", parents = listOf("leaf-$i")),
                node("leaf-$i"),
            )
        }
        val src = source(*nodes.toTypedArray())
        assertNull(src.cycleAt())
    }

    // ── Cycle reconstruction format detail ────────────────────

    @Test fun reconstructedCycleHasNoDuplicatesExceptClosingNode() {
        // Pin: the reconstructed cycle has each
        // intermediate node appearing exactly once; only
        // the start node appears twice (at index 0 and the
        // last index). Drift to "include the whole DFS
        // stack including pre-cycle nodes" would mistakenly
        // surface a longer prefix.
        val src = source(
            // pre-cycle approach: x → y → a (start of cycle)
            node("x", parents = listOf("y")),
            node("y", parents = listOf("a")),
            node("a", parents = listOf("b")),
            node("b", parents = listOf("c")),
            node("c", parents = listOf("a")), // closes onto a
        )
        val cycle = src.cycleAt()
        assertNotNull(cycle)
        assertEquals(cycle.first(), cycle.last(), "first == last")
        // Drop the closing duplicate; the rest should be
        // distinct.
        val withoutClosing = cycle.dropLast(1)
        val distinctCount = withoutClosing.toSet().size
        assertEquals(
            withoutClosing.size,
            distinctCount,
            "intermediate nodes appear exactly once; got: $cycle",
        )
        // Pre-cycle nodes (x, y) MUST NOT appear — only
        // the cycle members.
        assertTrue(
            nodeId("x") !in cycle,
            "pre-cycle node x not in reconstructed cycle: $cycle",
        )
        assertTrue(
            nodeId("y") !in cycle,
            "pre-cycle node y not in reconstructed cycle: $cycle",
        )
    }
}
