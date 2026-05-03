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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [runDescendantsQuery] + [runAncestorsQuery] —
 * `source_query(select=descendants|ancestors, root=<id>)`. Both
 * dispatch through the shared `runRelativesQuery` helper with
 * different neighbor-walk functions. Cycle 116 audit: 157 LOC,
 * **zero** transitive test references; the BFS + depth cap +
 * pagination + cycle safety surface was previously unprotected.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Direction semantics: descendants walks children, ancestors
 *    walks parents.** A→B→C: descendants(A) = {A@0, B@1, C@2};
 *    ancestors(C) = {C@0, B@1, A@2}. Symmetric. A regression
 *    swapping the neighborOf functions would silently invert
 *    every relatives query — descendants(C) would return the
 *    chain instead of just {C}, breaking every "what's downstream
 *    of this edit" workflow.
 *
 * 2. **`depthFromRoot=0` for the root, +1 per BFS hop.** The kdoc
 *    explicitly commits to "the root itself is included as row
 *    0". Off-by-one regressions (root=1, immediate=2) would
 *    silently corrupt depth filtering for upstream consumers
 *    (`source_query select=stale_clips` filtering by depth, etc.).
 *
 * 3. **Cycle safety via visited set.** A pathological DAG with
 *    a parent-cycle (a→b→a) would infinite-loop a naive walker.
 *    The BFS uses `if (next !in depthOf)` as a visited check; a
 *    regression dropping it would hang the query. Tested by
 *    constructing a 2-node cycle and verifying the result has 2
 *    entries (not infinite).
 */
class RelativesQueryTest {

    private fun project(nodes: List<SourceNode>): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(),
        source = Source(nodes = nodes, revision = 23),
    )

    private fun input(
        select: String,
        rootId: String?,
        depth: Int? = null,
        limit: Int? = null,
        offset: Int? = null,
        includeBody: Boolean? = null,
    ) = SourceQueryTool.Input(
        select = select,
        projectId = "p",
        root = rootId,
        depth = depth,
        limit = limit,
        offset = offset,
        includeBody = includeBody,
    )

    private fun decodeRows(out: SourceQueryTool.Output): List<NodeRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(NodeRow.serializer()),
            out.rows,
        )

    // ── input validation ──────────────────────────────────────────

    @Test fun descendantsMissingRootErrorsLoudWithRecoveryHint() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val ex = assertFailsWith<IllegalStateException> {
            runDescendantsQuery(project(listOf(a)), input(SourceQueryTool.SELECT_DESCENDANTS, null))
        }
        val msg = ex.message ?: ""
        assertTrue("requires root" in msg, "must mention required param; got: $msg")
        assertTrue("source_query(select=nodes)" in msg, "recovery hint; got: $msg")
    }

    @Test fun ancestorsMissingRootErrorsLoudWithRecoveryHint() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val ex = assertFailsWith<IllegalStateException> {
            runAncestorsQuery(project(listOf(a)), input(SourceQueryTool.SELECT_ANCESTORS, null))
        }
        assertTrue("requires root" in (ex.message ?: ""), "got: ${ex.message}")
    }

    @Test fun unknownRootErrorsWithProjectId() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val ex = assertFailsWith<IllegalStateException> {
            runDescendantsQuery(project(listOf(a)), input(SourceQueryTool.SELECT_DESCENDANTS, "ghost"))
        }
        val msg = ex.message ?: ""
        assertTrue("ghost" in msg, "must name id; got: $msg")
        assertTrue("p" in msg, "must name project; got: $msg")
    }

    // ── trivial: single node ──────────────────────────────────────

    @Test fun singleNodeReturnsItselfAtDepthZero() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(
            runDescendantsQuery(project(listOf(a)), input(SourceQueryTool.SELECT_DESCENDANTS, "a")).data,
        )
        val row = rows.single()
        assertEquals("a", row.id)
        assertEquals(0, row.depthFromRoot, "root depth = 0")
    }

    // ── direction semantics: descendants vs ancestors ─────────────

    @Test fun descendantsWalkChildrenFromRoot() {
        // a → b → c. descendants(a) = {a@0, b@1, c@2}.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val rows = decodeRows(
            runDescendantsQuery(project(listOf(a, b, c)), input(SourceQueryTool.SELECT_DESCENDANTS, "a")).data,
        )
        assertEquals(3, rows.size)
        val byId = rows.associateBy { it.id }
        assertEquals(0, byId.getValue("a").depthFromRoot)
        assertEquals(1, byId.getValue("b").depthFromRoot)
        assertEquals(2, byId.getValue("c").depthFromRoot)
    }

    @Test fun ancestorsWalkParentsFromRoot() {
        // a → b → c. ancestors(c) = {c@0, b@1, a@2} — symmetric
        // mirror of descendants(a).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val rows = decodeRows(
            runAncestorsQuery(project(listOf(a, b, c)), input(SourceQueryTool.SELECT_ANCESTORS, "c")).data,
        )
        assertEquals(3, rows.size)
        val byId = rows.associateBy { it.id }
        assertEquals(0, byId.getValue("c").depthFromRoot, "root c depth=0")
        assertEquals(1, byId.getValue("b").depthFromRoot, "parent b depth=1")
        assertEquals(2, byId.getValue("a").depthFromRoot, "grandparent a depth=2")
    }

    @Test fun descendantsFromTipReturnsOnlyItself() {
        // Inverse-direction sanity: descendants(c) on a→b→c is
        // just {c} (no children).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val rows = decodeRows(
            runDescendantsQuery(project(listOf(a, b, c)), input(SourceQueryTool.SELECT_DESCENDANTS, "c")).data,
        )
        assertEquals(listOf("c"), rows.map { it.id })
    }

    @Test fun ancestorsFromRootReturnsOnlyItself() {
        // Symmetric: ancestors(a) on a→b→c is just {a} (no parents).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val rows = decodeRows(
            runAncestorsQuery(project(listOf(a, b, c)), input(SourceQueryTool.SELECT_ANCESTORS, "a")).data,
        )
        assertEquals(listOf("a"), rows.map { it.id })
    }

    // ── diamond shape ─────────────────────────────────────────────

    @Test fun descendantsCoversDiamondReachableSet() {
        // a → b, a → c, b → d, c → d. descendants(a) = {a, b, c, d}.
        // Pin: each node visited once, at its first-discovery depth
        // (BFS guarantee).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val d = SourceNode.create(
            id = SourceNodeId("d"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("b")), SourceRef(SourceNodeId("c"))),
        )
        val rows = decodeRows(
            runDescendantsQuery(project(listOf(a, b, c, d)), input(SourceQueryTool.SELECT_DESCENDANTS, "a")).data,
        )
        assertEquals(setOf("a", "b", "c", "d"), rows.map { it.id }.toSet())
        val byId = rows.associateBy { it.id }
        assertEquals(0, byId.getValue("a").depthFromRoot)
        // b, c are immediate children of a — depth 1 (BFS visits
        // them at the same hop).
        assertEquals(1, byId.getValue("b").depthFromRoot)
        assertEquals(1, byId.getValue("c").depthFromRoot)
        // d reached via b OR c at depth 2 (BFS records first
        // visit, so depth=2 regardless of which path discovered it).
        assertEquals(2, byId.getValue("d").depthFromRoot)
    }

    // ── depth cap ─────────────────────────────────────────────────

    @Test fun depthCapZeroReturnsOnlyTheRoot() {
        // a → b → c. depth=0 means "only the root, no walk".
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val rows = decodeRows(
            runDescendantsQuery(
                project(listOf(a, b, c)),
                input(SourceQueryTool.SELECT_DESCENDANTS, "a", depth = 0),
            ).data,
        )
        assertEquals(listOf("a"), rows.map { it.id })
    }

    @Test fun depthCapOneReturnsRootAndImmediateChildren() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val rows = decodeRows(
            runDescendantsQuery(
                project(listOf(a, b, c)),
                input(SourceQueryTool.SELECT_DESCENDANTS, "a", depth = 1),
            ).data,
        )
        // a@0 + b@1; c at depth 2 is excluded.
        assertEquals(setOf("a", "b"), rows.map { it.id }.toSet())
    }

    @Test fun nullDepthIsUnbounded() {
        // Default null → walk the whole reachable set.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "k", parents = listOf(SourceRef(SourceNodeId("b"))))
        val rows = decodeRows(
            runDescendantsQuery(project(listOf(a, b, c)), input(SourceQueryTool.SELECT_DESCENDANTS, "a")).data,
        )
        assertEquals(3, rows.size)
    }

    @Test fun negativeDepthIsUnbounded() {
        // Per kdoc: "null or negative = unbounded". A regression
        // treating -1 as a hard limit (depth >= -1 → no walk) would
        // silently break unbounded queries.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val rows = decodeRows(
            runDescendantsQuery(
                project(listOf(a, b)),
                input(SourceQueryTool.SELECT_DESCENDANTS, "a", depth = -1),
            ).data,
        )
        assertEquals(2, rows.size, "negative depth = unbounded")
    }

    // ── cycle safety + dangling refs ──────────────────────────────

    @Test fun cycleInDagDoesNotInfiniteLoop() {
        // Pathological DAG: a → b → a (cycle). The visited check
        // (`next !in depthOf`) terminates BFS at the second visit.
        // Pin: result has 2 distinct nodes (a, b), not infinite.
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("b"))),
        )
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val rows = decodeRows(
            runAncestorsQuery(project(listOf(a, b)), input(SourceQueryTool.SELECT_ANCESTORS, "a")).data,
        )
        assertEquals(setOf("a", "b"), rows.map { it.id }.toSet(), "cycle terminates")
    }

    @Test fun danglingRefDropsSilentlyMatchesStaleSemantics() {
        // Per kdoc: "dangling ref — drop silently, matches
        // Source.stale semantics". A node whose parent points at
        // a non-existent id should NOT appear in ancestors —
        // ancestors(orphan) = {orphan} only, the ghost-parent is
        // dropped.
        val orphan = SourceNode.create(
            id = SourceNodeId("orphan"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("ghost-parent"))),
        )
        val rows = decodeRows(
            runAncestorsQuery(project(listOf(orphan)), input(SourceQueryTool.SELECT_ANCESTORS, "orphan")).data,
        )
        assertEquals(listOf("orphan"), rows.map { it.id }, "ghost-parent dropped")
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitClampsToOneAtMinimum() {
        val nodes = mutableListOf<SourceNode>()
        for (i in 1..5) {
            val parents = if (i == 1) emptyList() else listOf(SourceRef(SourceNodeId("n${i - 1}")))
            nodes += SourceNode.create(id = SourceNodeId("n$i"), kind = "k", parents = parents)
        }
        val result = runDescendantsQuery(
            project(nodes),
            input(SourceQueryTool.SELECT_DESCENDANTS, "n1", limit = 0),
        )
        assertEquals(1, decodeRows(result.data).size, "limit=0 clamps to 1")
        assertEquals(5, result.data.total, "total still reflects unfiltered count")
    }

    @Test fun offsetSkipsFirstNRows() {
        val nodes = mutableListOf<SourceNode>()
        for (i in 1..5) {
            val parents = if (i == 1) emptyList() else listOf(SourceRef(SourceNodeId("n${i - 1}")))
            nodes += SourceNode.create(id = SourceNodeId("n$i"), kind = "k", parents = parents)
        }
        val result = runDescendantsQuery(
            project(nodes),
            input(SourceQueryTool.SELECT_DESCENDANTS, "n1", offset = 2, limit = 100),
        )
        val rows = decodeRows(result.data)
        // BFS order from n1: n1@0, n2@1, n3@2, n4@3, n5@4.
        // offset=2 → skip n1, n2 → start at n3.
        assertEquals(3, rows.size)
        assertEquals("n3", rows[0].id)
        assertEquals("n4", rows[1].id)
        assertEquals("n5", rows[2].id)
    }

    @Test fun negativeOffsetCoercesToZero() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(
            runDescendantsQuery(project(listOf(a)), input(SourceQueryTool.SELECT_DESCENDANTS, "a", offset = -10)).data,
        )
        assertEquals(1, rows.size, "negative offset → 0; full result")
    }

    // ── includeBody ───────────────────────────────────────────────

    @Test fun includeBodyTruePopulatesBodyField() {
        val body = buildJsonObject { put("name", "Mei") }
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k", body = body)
        val rows = decodeRows(
            runDescendantsQuery(
                project(listOf(a)),
                input(SourceQueryTool.SELECT_DESCENDANTS, "a", includeBody = true),
            ).data,
        )
        assertEquals(body, rows.single().body, "body round-trips")
    }

    @Test fun includeBodyFalseLeavesBodyNull() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(
            runDescendantsQuery(
                project(listOf(a)),
                input(SourceQueryTool.SELECT_DESCENDANTS, "a", includeBody = false),
            ).data,
        )
        assertNull(rows.single().body, "body null when includeBody=false")
    }

    // ── outputForLlm summary ──────────────────────────────────────

    @Test fun outputForLlmIncludesDirectionRevisionAndDepthLabel() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "narrative.scene")
        val out = runDescendantsQuery(
            project(listOf(a)),
            input(SourceQueryTool.SELECT_DESCENDANTS, "a"),
        ).outputForLlm
        // Pin: format includes "Source revision N", direction
        // ("descendants"), root id, root kind, and "(depth ≤ N)"
        // or "unbounded".
        assertTrue("Source revision 23" in out, "revision; got: $out")
        assertTrue("descendants of a" in out, "direction; got: $out")
        assertTrue("(narrative.scene" in out, "root kind; got: $out")
        assertTrue("unbounded" in out, "unbounded label when null depth; got: $out")
        assertTrue("1 reachable node(s)" in out, "count; got: $out")
    }

    @Test fun outputForLlmShowsDepthCappedLabelWhenSet() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val out = runDescendantsQuery(
            project(listOf(a)),
            input(SourceQueryTool.SELECT_DESCENDANTS, "a", depth = 3),
        ).outputForLlm
        assertTrue("depth ≤ 3" in out, "depth label; got: $out")
    }

    @Test fun outputForLlmShowsPaginationNoteWhenTrimmed() {
        // Plant 5 nodes in a chain, fetch with limit=2 → 2 of 5
        // returned + paginate hint.
        val nodes = mutableListOf<SourceNode>()
        for (i in 1..5) {
            val parents = if (i == 1) emptyList() else listOf(SourceRef(SourceNodeId("n${i - 1}")))
            nodes += SourceNode.create(id = SourceNodeId("n$i"), kind = "k", parents = parents)
        }
        val out = runDescendantsQuery(
            project(nodes),
            input(SourceQueryTool.SELECT_DESCENDANTS, "n1", limit = 2),
        ).outputForLlm
        assertTrue("returning 2" in out, "returning count; got: $out")
        assertTrue("offset 0" in out, "offset; got: $out")
        assertTrue("limit 2" in out, "limit; got: $out")
    }

    @Test fun outputForLlmTailUsesBulletWithDepthMarker() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val out = runDescendantsQuery(
            project(listOf(a, b)),
            input(SourceQueryTool.SELECT_DESCENDANTS, "a"),
        ).outputForLlm
        // Pin tail format: "- <id> +<depth> (<kind>): <summary>".
        // Root's depthMarker is " +0".
        assertTrue("- a +0 (k):" in out, "root tail format; got: $out")
        assertTrue("- b +1 (k):" in out, "depth-1 tail format; got: $out")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun descendantsOutputCarriesSelectAndSourceRevision() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runDescendantsQuery(project(listOf(a)), input(SourceQueryTool.SELECT_DESCENDANTS, "a"))
        assertEquals(SourceQueryTool.SELECT_DESCENDANTS, result.data.select)
        assertEquals(1, result.data.total)
        assertEquals(1, result.data.returned)
        assertEquals(23, result.data.sourceRevision)
    }

    @Test fun ancestorsOutputCarriesSelectAndSourceRevision() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runAncestorsQuery(project(listOf(a)), input(SourceQueryTool.SELECT_ANCESTORS, "a"))
        assertEquals(SourceQueryTool.SELECT_ANCESTORS, result.data.select)
        assertEquals(23, result.data.sourceRevision)
    }

    @Test fun titleFormatIncludesDirectionRootIdAndCounts() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k", parents = listOf(SourceRef(SourceNodeId("a"))))
        val result = runDescendantsQuery(project(listOf(a, b)), input(SourceQueryTool.SELECT_DESCENDANTS, "a"))
        assertNotNull(result.title)
        // Pin: "source_query <direction> <id> (<returned>/<total>)".
        assertTrue(
            "source_query descendants a (2/2)" in result.title!!,
            "title format; got: ${result.title}",
        )
    }
}
