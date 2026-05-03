package io.talevia.core.tool.builtin.source.query

import io.talevia.core.AssetId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runAsciiTreeQuery] — `source_query(select=
 * ascii_tree)`, the indented ASCII projection of the source DAG used
 * for direct terminal viewing. Cycle 108 audit: 165 LOC, **zero**
 * transitive test references; the kdoc commits to detailed shape
 * decisions (`├─` / `└─` / `│` box-drawing chars, `(dup)` marker
 * on repeat mentions of multi-parent nodes, `[orphan]` marker on
 * unbound nodes) but no test pinned them.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Diamond `(dup)` marker keeps output linear in edge count,
 *    not exponential in path count.** Pinned by `diamondShape
 *    SecondMentionGetsDupMarkerAndStopsRecursing`. A regression
 *    that didn't add the dup marker would silently re-expand the
 *    diamond's lower half twice (once under each parent) — for
 *    deeply branching DAGs, output grows exponentially. The kdoc
 *    explicitly commits to "tied to edge count + root count
 *    rather than node count".
 *
 * 2. **`[orphan]` marker fires when clipsBoundTo returns empty.**
 *    Pinned both ways (`nodesWithBoundClipsAreNotMarkedOrphan`,
 *    `nodesWithoutBoundClipsAreMarkedOrphan`). A regression
 *    flipping the predicate would invert which nodes look
 *    "structurally connected" to users reading the tree.
 *
 * 3. **Branch markers (`├─` for non-last, `└─` for last) +
 *    matching prefix continuation (`│  ` vs `   `).** This is the
 *    visual contract that makes the tree look like a tree.
 *    `lastChildBranchMarkerSwitchesToCorner` pins the `└─` branch
 *    on the last child + the prefix-continuation logic.
 */
class AsciiTreeQueryTest {

    private val timeRange = TimeRange(start = 0.seconds, duration = 5.seconds)

    private fun videoClip(id: String, assetId: String, binding: Set<SourceNodeId>) = Clip.Video(
        id = ClipId(id),
        timeRange = timeRange,
        sourceRange = timeRange,
        assetId = AssetId(assetId),
        sourceBinding = binding,
    )

    private fun project(
        nodes: List<SourceNode>,
        clips: List<Clip> = emptyList(),
    ): Project {
        val tracks = if (clips.isEmpty()) emptyList() else listOf(Track.Video(TrackId("v1"), clips))
        return Project(
            id = ProjectId("p"),
            timeline = Timeline(tracks = tracks),
            source = Source(nodes = nodes, revision = 7),
        )
    }

    private fun decodeRow(out: SourceQueryTool.Output): AsciiTreeRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(AsciiTreeRow.serializer()),
            out.rows,
        ).single()

    // ── empty / trivial DAGs ──────────────────────────────────────

    @Test fun emptyDagRendersEmptyMarkerAndZeroCounts() {
        val result = runAsciiTreeQuery(project(emptyList()))
        val row = decodeRow(result.data)
        assertTrue(
            "(empty source DAG)" in row.tree,
            "empty marker; got: ${row.tree}",
        )
        assertEquals(0, row.nodeCount)
        assertEquals(0, row.edgeCount)
        assertEquals(0, row.rootCount)
    }

    @Test fun singleNodeAtRootHasNoBranchMarker() {
        // Pin: roots are emitted at the left margin (no `├─` / `└─`
        // / `│` prefix). A regression treating roots like children
        // would prepend a stray branch marker to every root line.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runAsciiTreeQuery(project(listOf(a)))
        val row = decodeRow(result.data)
        // Root line: "a (k) [orphan]\n" — orphan because no clip
        // binding. Crucially: NO box-drawing prefix.
        assertTrue(row.tree.startsWith("a (k)"), "root line shape; got: ${row.tree}")
        assertFalse("├─ a" in row.tree, "no ├─ on root; got: ${row.tree}")
        assertFalse("└─ a" in row.tree, "no └─ on root; got: ${row.tree}")
    }

    // ── linear chain ──────────────────────────────────────────────

    @Test fun linearChainShowsLastChildCornerMarker() {
        // a → b. Single child → `└─ b` (last + only child).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val result = runAsciiTreeQuery(project(listOf(a, b)))
        val row = decodeRow(result.data)
        assertTrue("└─ b" in row.tree, "single child uses corner; got: ${row.tree}")
        // No `│` continuation needed — only one child of a.
    }

    // ── multi-child branch markers ────────────────────────────────

    @Test fun lastChildBranchMarkerSwitchesToCorner() {
        // a has 3 children: b, c, d. Pin the markers:
        //   ├─ b
        //   ├─ c
        //   └─ d   (last child uses └─)
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val d = SourceNode.create(id = SourceNodeId("d"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val result = runAsciiTreeQuery(project(listOf(a, b, c, d)))
        val tree = decodeRow(result.data).tree
        assertTrue("├─ b" in tree, "non-last → ├─; got: $tree")
        assertTrue("├─ c" in tree, "non-last → ├─")
        assertTrue("└─ d" in tree, "last child → └─; got: $tree")
    }

    @Test fun grandchildPrefixHasVerticalBarUnderNonLastParent() {
        // a → b, c
        // b → x, y
        // c → z
        // Pin: under b (non-last sibling under a), x and y indent with
        // `│  ` prefix so visually the tree continues. Under c (last
        // sibling under a), z indents with `   ` (3 spaces, no bar).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val x = SourceNode.create(id = SourceNodeId("x"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val y = SourceNode.create(id = SourceNodeId("y"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val z = SourceNode.create(id = SourceNodeId("z"), kind = "k", parents = listOf(SourceRef(SourceNodeId("c"))))
        val result = runAsciiTreeQuery(project(listOf(a, b, c, x, y, z)))
        val tree = decodeRow(result.data).tree
        // Under b (non-last under a), grandchildren prefix with "│  ".
        assertTrue("│  ├─ x" in tree, "non-last parent's child has │  ├─; got: $tree")
        assertTrue("│  └─ y" in tree, "non-last parent's last child has │  └─; got: $tree")
        // Under c (last under a), grandchild prefix uses 3 spaces (no
        // bar) followed by └─.
        assertTrue("   └─ z" in tree, "last parent's child has '   └─'; got: $tree")
    }

    // ── diamond / multi-parent (dup) marker ──────────────────────

    @Test fun diamondShapeSecondMentionGetsDupMarkerAndStopsRecursing() {
        // a → b
        // a → c
        // b → d
        // c → d  (same `d` reached from b AND c)
        // d → e  (subtree under d)
        // The `(dup)` keeps output linear: d expands once (under b
        // since b sorts before c), then on the second mention under
        // c we get `└─ d (k) (dup)` and STOP — e MUST NOT re-expand.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val d = SourceNode.create(
            id = SourceNodeId("d"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("b")), SourceRef(SourceNodeId("c"))),
        )
        val e = SourceNode.create(id = SourceNodeId("e"), kind = "k", parents = listOf(SourceRef(SourceNodeId("d"))))
        val result = runAsciiTreeQuery(project(listOf(a, b, c, d, e)))
        val tree = decodeRow(result.data).tree

        // Pin: `(dup)` marker present on second mention of d.
        assertTrue("(dup)" in tree, "(dup) marker missing; got: $tree")
        // Pin: `e` appears EXACTLY ONCE (NOT twice). Counting line
        // occurrences guards against double-expansion.
        val eLines = tree.lines().count { "e (k)" in it }
        assertEquals(1, eLines, "node e must appear exactly once; got $eLines occurrences in: $tree")
        // Pin: `d` appears TWICE (once expanded, once with (dup)).
        val dLines = tree.lines().count { "d (k)" in it }
        assertEquals(2, dLines, "node d must appear twice (once expanded, once dup); got $dLines")
    }

    @Test fun edgeCountReflectsRawParentEdgesNotUniqueNodes() {
        // Pin: edgeCount sums every node's parents.size — diamond
        // has 4 edges (a→b, a→c, b→d, c→d). nodeCount = 4. The
        // counts are deliberately decoupled per kdoc.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val d = SourceNode.create(
            id = SourceNodeId("d"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("b")), SourceRef(SourceNodeId("c"))),
        )
        val result = runAsciiTreeQuery(project(listOf(a, b, c, d)))
        val row = decodeRow(result.data)
        assertEquals(4, row.nodeCount, "4 unique nodes")
        assertEquals(4, row.edgeCount, "4 parent-edges (a→b, a→c, b→d, c→d)")
        assertEquals(1, row.rootCount, "only `a` has no parent")
    }

    // ── orphan marker ─────────────────────────────────────────────

    @Test fun nodesWithoutBoundClipsAreMarkedOrphan() {
        // No clips bound → all 3 nodes are orphans → all 3 lines
        // carry `[orphan]`.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k")
        val result = runAsciiTreeQuery(project(listOf(a, b, c)))
        val tree = decodeRow(result.data).tree
        val orphanLines = tree.lines().count { "[orphan]" in it }
        assertEquals(3, orphanLines, "all 3 nodes orphans; got: $tree")
    }

    @Test fun nodesWithBoundClipsAreNotMarkedOrphan() {
        // Clip bound to `a` → a NOT orphan; b is orphan.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val result = runAsciiTreeQuery(project(listOf(a, b), listOf(clip)))
        val tree = decodeRow(result.data).tree
        // Find the line containing "a (k)"; assert it does NOT contain [orphan].
        val aLine = tree.lines().single { "a (k)" in it }
        assertFalse("[orphan]" in aLine, "node `a` has bound clip; should not be orphan; got: $aLine")
        // Line for `b` DOES carry [orphan].
        val bLine = tree.lines().single { "b (k)" in it }
        assertTrue("[orphan]" in bLine, "node `b` has no bound clip; should be orphan; got: $bLine")
    }

    // ── multi-root sorting ────────────────────────────────────────

    @Test fun multipleRootsAreSortedAlphabetically() {
        // 3 isolated nodes — order in `nodes` list is z, a, m. Roots
        // emitted alphabetically (a, m, z).
        val z = SourceNode.create(id = SourceNodeId("z"), kind = "k")
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val m = SourceNode.create(id = SourceNodeId("m"), kind = "k")
        val result = runAsciiTreeQuery(project(listOf(z, a, m)))
        val tree = decodeRow(result.data).tree
        val lines = tree.lines().filter { it.isNotBlank() }
        // First three non-blank lines are the 3 roots in order.
        assertTrue(lines[0].startsWith("a (k)"), "first root must be 'a'; got: ${lines[0]}")
        assertTrue(lines[1].startsWith("m (k)"), "second root must be 'm'; got: ${lines[1]}")
        assertTrue(lines[2].startsWith("z (k)"), "third root must be 'z'; got: ${lines[2]}")
    }

    @Test fun siblingChildrenSortedAlphabetically() {
        // a → x, b, m  (insertion order). Tree must show a's children
        // in alphabetical order: b, m, x.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val x = SourceNode.create(id = SourceNodeId("x"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val m = SourceNode.create(id = SourceNodeId("m"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val result = runAsciiTreeQuery(project(listOf(a, x, b, m)))
        val tree = decodeRow(result.data).tree
        // b appears before m, m appears before x.
        val bIdx = tree.indexOf("b (k)")
        val mIdx = tree.indexOf("m (k)")
        val xIdx = tree.indexOf("x (k)")
        assertTrue(bIdx in 0..<mIdx, "b before m; tree=\n$tree")
        assertTrue(mIdx in 0..<xIdx, "m before x; tree=\n$tree")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun titleIncludesNodeAndRootCountsWithCorrectPluralisation() {
        // Pin: title format "(N node(s), R root(s))" — both
        // pluralisation arms verified.
        val one = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val singleResult = runAsciiTreeQuery(project(listOf(one)))
        // Single node + single root → singular forms.
        assertTrue(
            "(1 node, 1 root)" in (singleResult.title ?: ""),
            "singular forms; got: ${singleResult.title}",
        )

        // 3 isolated nodes → plural for both.
        val three = (1..3).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }
        val threeResult = runAsciiTreeQuery(project(three))
        assertTrue(
            "(3 nodes, 3 roots)" in (threeResult.title ?: ""),
            "plural forms; got: ${threeResult.title}",
        )
    }

    @Test fun outputForLlmExposesCountsAndDupGuidance() {
        // Pin the LLM-facing summary text shape. The LLM needs to
        // know:
        //   - which DAG (project id)
        //   - the 3 counts (nodes, edges, roots)
        //   - that `(dup)` markers exist (so it doesn't double-count
        //     when reading the tree)
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runAsciiTreeQuery(project(listOf(a)))
        val out = result.outputForLlm
        assertTrue("Source DAG for 'p'" in out, "project id; got: $out")
        assertTrue("1 nodes" in out || "1 node" in out, "node count; got: $out")
        assertTrue("0 edges" in out, "edge count; got: $out")
        assertTrue("1 roots" in out || "1 root" in out, "root count; got: $out")
        assertTrue("(dup)" in out, "must explain (dup) marker; got: $out")
    }

    @Test fun outputCarriesSelectAndSourceRevision() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runAsciiTreeQuery(project(listOf(a)))
        assertEquals(SourceQueryTool.SELECT_ASCII_TREE, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
        assertEquals(7, result.data.sourceRevision, "sourceRevision must round-trip from project")
    }

    @Test fun nodeKindAppearsInParensInTreeLine() {
        // Pin: line shape `<id> (<kind>)`. A regression dropping
        // the kind would silently lose context — readers would
        // see node ids without their semantic class.
        val a = SourceNode.create(id = SourceNodeId("alpha"), kind = "narrative.character")
        val result = runAsciiTreeQuery(project(listOf(a)))
        assertTrue(
            "alpha (narrative.character)" in decodeRow(result.data).tree,
            "id (kind) format; got: ${decodeRow(result.data).tree}",
        )
    }
}
