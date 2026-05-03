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
 * Direct tests for [runDotQuery] — `source_query(select=dot)`, the
 * Graphviz DOT projection of the source DAG used for external SVG
 * rendering. Cycle 110 audit: 106 LOC, **zero** transitive test
 * references; the kdoc commits to specific shape decisions
 * (rankdir=LR, parent→child edge direction, dashed-grey styling
 * for orphans) but no test pinned the output format.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Edge direction is parent → child (upstream → downstream).**
 *    Per the kdoc: "the rendered graph reads upstream → downstream".
 *    A regression flipping the direction would render every DAG
 *    upside down — derived nodes pointing back to their sources.
 *    `edgesGoFromParentToChild` constructs `b.parents = [a]` and
 *    asserts the DOT output contains `a -> b` (NOT `b -> a`).
 *
 * 2. **Orphan nodes get `style=dashed, color=gray50`; non-orphans
 *    don't.** The kdoc commits to "orphans visible in rendered
 *    SVG without forcing the caller to diff against
 *    DagSummaryRow.orphanedNodeIds". A regression flipping the
 *    predicate would invert which nodes look "structurally
 *    connected" in the user's eyes — exactly the wrong kind of
 *    silent visual regression.
 *
 * 3. **Special characters in node IDs are DOT-escaped.** The
 *    `dotQuote` helper escapes `\` and `"` before wrapping in
 *    quotes. A regression dropping the escape would produce
 *    invalid DOT that crashes Graphviz silently — user pipes to
 *    `dot -Tsvg` and gets an empty output. Pinned via node IDs
 *    containing both special chars.
 */
class DotQueryTest {

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
            source = Source(nodes = nodes, revision = 11),
        )
    }

    private fun decodeRow(out: SourceQueryTool.Output): DotRow =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(DotRow.serializer()),
            out.rows,
        ).single()

    // ── DOT preamble ──────────────────────────────────────────────

    @Test fun emptyDagProducesValidDigraphHeaderAndFooter() {
        // Pin: even the empty graph must produce a valid DOT
        // document. Graphviz must not crash on `dot -Tsvg` with
        // an empty subject.
        val result = runDotQuery(project(emptyList()))
        val row = decodeRow(result.data)
        assertTrue(row.dot.startsWith("digraph SourceDAG {"), "preamble; got: ${row.dot}")
        assertTrue(row.dot.trimEnd().endsWith("}"), "closing brace; got: ${row.dot}")
        assertTrue("rankdir=LR;" in row.dot, "rankdir directive; got: ${row.dot}")
        assertTrue("shape=box" in row.dot, "node shape default; got: ${row.dot}")
        assertEquals(0, row.nodeCount)
        assertEquals(0, row.edgeCount)
    }

    @Test fun singleNodeProducesOneNodeLineAndNoEdges() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runDotQuery(project(listOf(a)))
        val row = decodeRow(result.data)
        // Pin: 1 node line, 0 edge lines.
        val nodeLines = row.dot.lines().count { line -> "[label=" in line }
        val edgeLines = row.dot.lines().count { line -> " -> " in line }
        assertEquals(1, nodeLines)
        assertEquals(0, edgeLines)
        assertEquals(1, row.nodeCount)
        assertEquals(0, row.edgeCount)
    }

    // ── edge direction (the marquee pin) ──────────────────────────

    @Test fun edgesGoFromParentToChild() {
        // Pin: parent → child direction. The kdoc commits to
        // "upstream → downstream" reading order — a regression
        // flipping arrow direction would render every DAG with
        // backwards data flow.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val result = runDotQuery(project(listOf(a, b)))
        val dot = decodeRow(result.data).dot
        // Pin: "a" -> "b" appears.
        assertTrue("\"a\" -> \"b\";" in dot, "parent → child edge; got: $dot")
        // Inverse direction MUST NOT appear.
        assertFalse("\"b\" -> \"a\";" in dot, "no reversed edge; got: $dot")
    }

    @Test fun diamondHasFourEdgesAllParentToChild() {
        // a → b, a → c, b → d, c → d.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val d = SourceNode.create(
            id = SourceNodeId("d"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("b")), SourceRef(SourceNodeId("c"))),
        )
        val result = runDotQuery(project(listOf(a, b, c, d)))
        val row = decodeRow(result.data)
        assertEquals(4, row.edgeCount, "4 edges (sum of parent.size)")
        val dot = row.dot
        assertTrue("\"a\" -> \"b\";" in dot)
        assertTrue("\"a\" -> \"c\";" in dot)
        assertTrue("\"b\" -> \"d\";" in dot)
        assertTrue("\"c\" -> \"d\";" in dot)
    }

    // ── orphan styling ────────────────────────────────────────────

    @Test fun orphanNodesGetDashedGreyStyle() {
        // Node with no clips bound is an orphan. The kdoc commits
        // to making them visually distinct in the rendered SVG.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runDotQuery(project(listOf(a)))
        val dot = decodeRow(result.data).dot
        assertTrue("style=dashed" in dot, "orphan must be dashed; got: $dot")
        assertTrue("color=gray50" in dot, "orphan must be gray50; got: $dot")
    }

    @Test fun nonOrphanNodesAreNotDashed() {
        // Node with bound clip is NOT orphan. Pin the inverse so
        // a regression flipping the predicate gets caught.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val result = runDotQuery(project(listOf(a), listOf(clip)))
        val dot = decodeRow(result.data).dot
        // Find the line for node `a`. It must NOT contain dashed
        // styling.
        val aLine = dot.lines().single { "\"a\"" in it && "[label=" in it }
        assertFalse("style=dashed" in aLine, "non-orphan must NOT be dashed; got: $aLine")
        assertFalse("color=gray50" in aLine, "non-orphan must NOT be gray50; got: $aLine")
    }

    @Test fun mixedGraphHasOrphanAndNonOrphanCorrectlyStyled() {
        // a has bound clip → not orphan. b has no bound clip →
        // orphan. Pin both styles in the same graph.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val clip = videoClip("c1", "asset-1", setOf(SourceNodeId("a")))
        val result = runDotQuery(project(listOf(a, b), listOf(clip)))
        val dot = decodeRow(result.data).dot
        val aLine = dot.lines().single { "\"a\"" in it && "[label=" in it }
        val bLine = dot.lines().single { "\"b\"" in it && "[label=" in it }
        assertFalse("style=dashed" in aLine, "a (non-orphan) is NOT dashed")
        assertTrue("style=dashed" in bLine, "b (orphan) IS dashed; got: $bLine")
    }

    // ── node label format ─────────────────────────────────────────

    @Test fun nodeLabelHasIdAndKindSeparatedByLiteralBackslashN() {
        // Pin: label format `<id>\n<kind>` with the LITERAL `\n`
        // break (not a real newline). DOT renderers parse `\n` as
        // a line break in the rendered label. A regression
        // dropping the break would smash id and kind into one
        // unreadable string.
        val a = SourceNode.create(id = SourceNodeId("alpha"), kind = "narrative.character")
        val result = runDotQuery(project(listOf(a)))
        val dot = decodeRow(result.data).dot
        // The DOT label is wrapped in quotes; look for the literal
        // \n sequence between id and kind.
        assertTrue(
            "alpha\\nnarrative.character" in dot,
            "label must contain literal \\n break; got: $dot",
        )
    }

    // ── DOT escaping ──────────────────────────────────────────────

    @Test fun nodeIdWithSpecialCharsIsEscaped() {
        // Pin: backslash and double-quote in a node id are escaped
        // before wrapping. A regression dropping the escape would
        // produce invalid DOT — Graphviz would silently fail or
        // render an empty graph.
        // Note: SourceNodeId values come from user input via
        // create_source_node, so adversarial id construction is a
        // real surface even though typical UUIDs are safe.
        val a = SourceNode.create(id = SourceNodeId("a\"b\\c"), kind = "k")
        val result = runDotQuery(project(listOf(a)))
        val dot = decodeRow(result.data).dot
        // Pin: the quote is escaped to \" and backslash is doubled.
        // The id appears as "a\"b\\c" in the wrapped form.
        assertTrue(
            "\"a\\\"b\\\\c\"" in dot,
            "id with \" and \\ must be escaped; got: $dot",
        )
    }

    // ── sorting ───────────────────────────────────────────────────

    @Test fun nodeLinesAreSortedAlphabeticallyByNodeId() {
        // Pin: nodes are emitted in alphabetic id order (per
        // `nodes.sortedBy { it.id.value }` at the top of buildDot).
        // Stable order matters for diffability — re-running on
        // the same graph must produce byte-identical output.
        val z = SourceNode.create(id = SourceNodeId("z"), kind = "k")
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val m = SourceNode.create(id = SourceNodeId("m"), kind = "k")
        val result = runDotQuery(project(listOf(z, a, m)))
        val dot = decodeRow(result.data).dot
        // Find the indices of "a", "m", "z" label lines.
        val aIdx = dot.indexOf("\"a\" [label=")
        val mIdx = dot.indexOf("\"m\" [label=")
        val zIdx = dot.indexOf("\"z\" [label=")
        assertTrue(aIdx in 0..<mIdx, "a before m; dot=\n$dot")
        assertTrue(mIdx in 0..<zIdx, "m before z")
    }

    // ── title + outputForLlm pluralisation ────────────────────────

    @Test fun titlePluralisesNodesAndEdgesCorrectly() {
        // Pin: "(N node(s), N edge(s))" — both pluralisation arms.
        val one = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val singleResult = runDotQuery(project(listOf(one)))
        // 1 node, 0 edges → singular "node", but 0 edges goes
        // plural ("edges") because the predicate is `if (count == 1)`.
        assertTrue(
            "(1 node, 0 edges)" in (singleResult.title ?: ""),
            "1-node 0-edge title; got: ${singleResult.title}",
        )

        // 1 node + 1 edge: single self-loop is unusual but pin the
        // singular form. Use a→b chain → 2 nodes, 1 edge.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val pairResult = runDotQuery(project(listOf(a, b)))
        assertTrue(
            "(2 nodes, 1 edge)" in (pairResult.title ?: ""),
            "2-node 1-edge title singular for edge; got: ${pairResult.title}",
        )
    }

    @Test fun outputForLlmExposesCountsAndDotPipeGuidance() {
        // The LLM-facing summary should give:
        //   - project id
        //   - node + edge counts
        //   - the `dot -Tsvg` recipe so the LLM can hand the
        //     payload to the user with a working command.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runDotQuery(project(listOf(a)))
        val out = result.outputForLlm
        assertTrue("Source DAG for 'p'" in out, "project id; got: $out")
        assertTrue("1 nodes, 0 edges" in out, "counts; got: $out")
        assertTrue("dot -Tsvg" in out, "render recipe; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesSelectAndSourceRevision() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runDotQuery(project(listOf(a)))
        assertEquals(SourceQueryTool.SELECT_DOT, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
        assertEquals(11, result.data.sourceRevision, "sourceRevision must round-trip")
    }
}
