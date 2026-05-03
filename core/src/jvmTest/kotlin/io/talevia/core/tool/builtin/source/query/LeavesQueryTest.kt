package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [runLeavesQuery] — `source_query(select=leaves)`,
 * the "downstream tips" view that returns every DAG node no other
 * node lists as a parent. Cycle 111 audit: 86 LOC, **zero**
 * transitive test references.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Leaf = no children (no other node references it as parent).**
 *    Root vs leaf semantics are symmetric: roots have no parents,
 *    leaves have no children. A regression flipping the predicate
 *    (e.g., filtering by `parents.isEmpty()` instead of children-
 *    empty) would silently report roots as leaves.
 *
 * 2. **`parentCount` distinguishes standalone leaves from chain
 *    tips.** Standalone (parents=0, children=0) needs different
 *    next-step guidance than a chain tip (parents>0, children=0)
 *    per the kdoc. A regression dropping the field would force
 *    the LLM into a `select=nodes` follow-up to disambiguate.
 *
 * 3. **Sorted by id for diff stability.** Multiple leaves must
 *    appear in alphabetic order so re-running the query on the
 *    same DAG produces byte-identical output.
 */
class LeavesQueryTest {

    private fun project(nodes: List<SourceNode>): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(),
        source = Source(nodes = nodes, revision = 5),
    )

    private fun decodeRows(out: SourceQueryTool.Output): List<LeafRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(LeafRow.serializer()),
            out.rows,
        )

    // ── empty / trivial DAGs ──────────────────────────────────────

    @Test fun emptyDagReturnsZeroLeaves() {
        val result = runLeavesQuery(project(emptyList()))
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(emptyList(), decodeRows(result.data))
    }

    @Test fun emptyDagOutputForLlmExplainsEmptyDagState() {
        // Pin: empty case has dedicated user-facing text. A regression
        // falling through to the non-empty path would say "0 leaf
        // source nodes" — technically correct but less actionable
        // than the explicit "empty DAG" hint.
        val result = runLeavesQuery(project(emptyList()))
        val out = result.outputForLlm
        assertTrue("No leaf source nodes" in out, "no-leaf marker; got: $out")
        assertTrue("empty DAG" in out, "empty-DAG hint; got: $out")
    }

    @Test fun singleIsolatedNodeIsAStandaloneLeaf() {
        // Isolated node has parents=0 AND children=0 → it IS a leaf
        // (per the children-empty predicate) but `parentCount=0`
        // marks it standalone vs a normal chain tip.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(runLeavesQuery(project(listOf(a))).data)
        val leaf = rows.single()
        assertEquals("a", leaf.id)
        assertEquals(0, leaf.parentCount, "standalone leaf has parentCount=0")
    }

    // ── linear chain ──────────────────────────────────────────────

    @Test fun linearChainHasExactlyOneLeafAtTheTip() {
        // a → b → c. Only c has no children → only c is a leaf.
        // Pin: roots and middle nodes are NOT leaves.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val rows = decodeRows(runLeavesQuery(project(listOf(a, b, c))).data)
        val ids = rows.map { it.id }
        assertEquals(listOf("c"), ids, "only c is a leaf; got: $ids")
        // Pin parentCount carries node.parents.size — c has 1 parent.
        assertEquals(1, rows.single().parentCount)
    }

    // ── diamond / multi-leaf ──────────────────────────────────────

    @Test fun forkProducesMultipleLeavesSortedAlphabetically() {
        // a → b, a → c. b and c are both leaves.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val z = SourceNode.create(id = SourceNodeId("z"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val rows = decodeRows(runLeavesQuery(project(listOf(a, z, b))).data)
        // Pin sortedBy { it.id } — b before z.
        assertEquals(listOf("b", "z"), rows.map { it.id }, "leaves sorted alphabetically")
    }

    @Test fun diamondHasOneLeafAtTheConvergence() {
        // a → b, a → c, b → d, c → d. Only d has no children.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val d = SourceNode.create(
            id = SourceNodeId("d"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("b")), SourceRef(SourceNodeId("c"))),
        )
        val rows = decodeRows(runLeavesQuery(project(listOf(a, b, c, d))).data)
        assertEquals(listOf("d"), rows.map { it.id })
        // d has 2 parents (b, c); parentCount=2.
        assertEquals(2, rows.single().parentCount)
    }

    // ── revision + kind round-trip ────────────────────────────────

    @Test fun rowCarriesNodeRevisionAndKind() {
        // Pin: LeafRow's revision and kind fields round-trip from
        // SourceNode. UI consumes these to show "this leaf is at
        // revision 5 of kind narrative.scene" without a follow-up
        // node-detail query.
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "narrative.scene",
            revision = 7,
        )
        val rows = decodeRows(runLeavesQuery(project(listOf(a))).data)
        val leaf = rows.single()
        assertEquals("narrative.scene", leaf.kind)
        assertEquals(7L, leaf.revision)
    }

    // ── title + outputForLlm pluralisation ────────────────────────

    @Test fun titlePluralisesLeafNodesCorrectly() {
        // 0 leaves → "(0 leaf nodes)" — plural since count != 1.
        val zero = runLeavesQuery(project(emptyList()))
        assertTrue(
            "(0 leaf nodes)" in (zero.title ?: ""),
            "0-count plural; got: ${zero.title}",
        )
        // 1 leaf → "(1 leaf node)" — singular.
        val one = runLeavesQuery(
            project(listOf(SourceNode.create(id = SourceNodeId("a"), kind = "k"))),
        )
        assertTrue(
            "(1 leaf node)" in (one.title ?: ""),
            "1-count singular; got: ${one.title}",
        )
        // 2 leaves → "(2 leaf nodes)" plural.
        val two = runLeavesQuery(
            project(
                listOf(
                    SourceNode.create(id = SourceNodeId("a"), kind = "k"),
                    SourceNode.create(id = SourceNodeId("b"), kind = "k"),
                ),
            ),
        )
        assertTrue(
            "(2 leaf nodes)" in (two.title ?: ""),
            "2-count plural; got: ${two.title}",
        )
    }

    @Test fun nonEmptyOutputForLlmIncludesParentCountGuidance() {
        // Pin: the LLM-facing text includes the "parentCount=0 marks
        // standalone, parentCount>0 marks chain tip" guidance so the
        // LLM knows how to interpret the rows. Without this, it
        // would treat all leaves identically.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val out = runLeavesQuery(project(listOf(a))).outputForLlm
        assertTrue("parentCount=0" in out, "standalone hint; got: $out")
        assertTrue("parentCount > 0" in out, "chain-tip hint; got: $out")
        assertTrue("regenerate-after-edit" in out, "use-case hint; got: $out")
        // Cross-reference to the orphans selector for clip-binding
        // status — the LLM needs to know which sibling query to call.
        assertTrue("select=orphans" in out, "orphans cross-ref; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesSelectAndSourceRevision() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runLeavesQuery(project(listOf(a)))
        assertEquals(SourceQueryTool.SELECT_LEAVES, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
        assertEquals(5, result.data.sourceRevision, "sourceRevision must round-trip")
    }
}
