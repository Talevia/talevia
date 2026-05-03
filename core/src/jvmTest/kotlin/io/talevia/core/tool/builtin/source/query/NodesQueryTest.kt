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
 * Direct tests for [runNodesQuery] — `source_query(select=nodes)`,
 * the canonical single-project source-node enumeration. Cycle 117
 * audit: 149 LOC, **zero** transitive test references; the kdoc
 * commits to "replaces both `list_source_nodes` and
 * `search_source_nodes`" but the merged behaviour was unprotected.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`projectId` field stays NULL on single-project rows.** Per
 *    [NodeRow] kdoc: "Null on single-project queries because the
 *    owning project is already in the Input echo." Distinguishes
 *    single-scope rows from cross-project rows
 *    (`scope=all_projects` populates projectId). A regression
 *    accidentally stamping the field would confuse decoders that
 *    branch on projectId presence.
 *
 * 2. **`sourceRevision` carries the project's actual revision (not
 *    the cross-project 0L sentinel).** Single-project queries
 *    expose monotonic revision numbers UI consumers use for cache
 *    invalidation; the `scope=all_projects` sibling deliberately
 *    returns 0L because the union has no single revision. A
 *    regression returning 0L on the single-project path would
 *    silently break every UI cache that depends on revision
 *    increments.
 *
 * 3. **contentSubstring search populates `snippet` + `matchOffset`
 *    when matched, leaves them null otherwise.** A regression
 *    populating snippet on non-search calls would surface
 *    misleading "match" data on every node enumeration; a
 *    regression dropping snippet on search calls would force
 *    follow-up `node_detail` calls to find the match site.
 */
class NodesQueryTest {

    private fun project(nodes: List<SourceNode>): Project = Project(
        id = ProjectId("p"),
        timeline = Timeline(),
        source = Source(nodes = nodes, revision = 31),
    )

    private fun input(
        kind: String? = null,
        kindPrefix: String? = null,
        nodeId: String? = null,
        sortBy: String? = null,
        contentSubstring: String? = null,
        caseSensitive: Boolean? = null,
        includeBody: Boolean? = null,
        limit: Int? = null,
        offset: Int? = null,
        hasParent: Boolean? = null,
    ) = SourceQueryTool.Input(
        select = SourceQueryTool.SELECT_NODES,
        projectId = "p",
        kind = kind,
        kindPrefix = kindPrefix,
        id = nodeId,
        sortBy = sortBy,
        contentSubstring = contentSubstring,
        caseSensitive = caseSensitive,
        includeBody = includeBody,
        limit = limit,
        offset = offset,
        hasParent = hasParent,
    )

    private fun decodeRows(out: SourceQueryTool.Output): List<NodeRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(NodeRow.serializer()),
            out.rows,
        )

    // ── empty / shape ─────────────────────────────────────────────

    @Test fun emptyProjectReturnsNoMatchesAndZeroCount() {
        val result = runNodesQuery(project(emptyList()), input())
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(emptyList(), decodeRows(result.data))
        assertTrue("no matches" in result.outputForLlm, "got: ${result.outputForLlm}")
    }

    @Test fun rowsHaveNullProjectIdOnSingleProjectQuery() {
        // The marquee distinguish-from-cross-project pin. Per
        // NodeRow kdoc: projectId is null when the query is
        // single-scope.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(runNodesQuery(project(listOf(a)), input()).data)
        assertNull(rows.single().projectId, "projectId NULL on single-project query")
    }

    @Test fun outputSourceRevisionIsProjectRevisionNotZero() {
        // Inverse of the cross-project sentinel: single-project
        // returns the actual source.revision (= 31 in fixture).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runNodesQuery(project(listOf(a)), input())
        assertEquals(31, result.data.sourceRevision, "single-project surfaces real revision")
    }

    // ── content-substring search vs no-search rows ────────────────

    @Test fun snippetAndMatchOffsetNullWhenContentSubstringNotProvided() {
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "k",
            body = buildJsonObject { put("description", "fox") },
        )
        val rows = decodeRows(runNodesQuery(project(listOf(a)), input()).data)
        // Pin: with no contentSubstring, every row has null
        // snippet + matchOffset. A regression populating these
        // would confuse "did this match?" branches.
        assertNull(rows.single().snippet)
        assertNull(rows.single().matchOffset)
    }

    @Test fun snippetAndMatchOffsetPopulatedWhenContentSubstringMatches() {
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "k",
            body = buildJsonObject {
                put("description", "the quick brown fox jumps over the lazy dog")
            },
        )
        val rows = decodeRows(
            runNodesQuery(project(listOf(a)), input(contentSubstring = "fox")).data,
        )
        val row = rows.single()
        assertNotNull(row.snippet, "snippet must be set on match")
        assertNotNull(row.matchOffset, "matchOffset must be set on match")
        assertTrue("fox" in row.snippet!!, "snippet contains match; got: ${row.snippet}")
    }

    @Test fun contentSubstringMissExcludesNode() {
        // Pin: nodes that don't contain the substring don't surface
        // (filtered, not included with snippet=null).
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "k",
            body = buildJsonObject { put("description", "no match here") },
        )
        val result = runNodesQuery(project(listOf(a)), input(contentSubstring = "fox"))
        assertEquals(0, decodeRows(result.data).size)
    }

    @Test fun caseSensitiveFalseMatchesUpperCaseInBody() {
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "k",
            body = buildJsonObject { put("description", "FOX") },
        )
        val rows = decodeRows(
            runNodesQuery(project(listOf(a)), input(contentSubstring = "fox", caseSensitive = false)).data,
        )
        assertEquals(1, rows.size)
    }

    @Test fun caseSensitiveTrueRespectsCase() {
        val a = SourceNode.create(
            id = SourceNodeId("a"),
            kind = "k",
            body = buildJsonObject { put("description", "FOX") },
        )
        val rows = decodeRows(
            runNodesQuery(project(listOf(a)), input(contentSubstring = "fox", caseSensitive = true)).data,
        )
        assertEquals(0, rows.size, "case-sensitive 'fox' doesn't match 'FOX'")
    }

    // ── filters ───────────────────────────────────────────────────

    @Test fun kindFilterRestrictsToExactMatch() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "narrative.scene")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "narrative.shot")
        val rows = decodeRows(
            runNodesQuery(project(listOf(a, b)), input(kind = "narrative.scene")).data,
        )
        assertEquals(listOf("a"), rows.map { it.id })
    }

    @Test fun kindPrefixFilterRestrictsToPrefixedKinds() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "narrative.scene")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "narrative.shot")
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "vlog.raw_footage")
        val rows = decodeRows(
            runNodesQuery(project(listOf(a, b, c)), input(kindPrefix = "narrative")).data,
        )
        assertEquals(setOf("a", "b"), rows.map { it.id }.toSet())
    }

    @Test fun idFilterRestrictsToExactNodeId() {
        val a = SourceNode.create(id = SourceNodeId("target"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("other"), kind = "k")
        val rows = decodeRows(
            runNodesQuery(project(listOf(a, b)), input(nodeId = "target")).data,
        )
        assertEquals(listOf("target"), rows.map { it.id })
    }

    @Test fun hasParentTrueRestrictsToChildrenOnly() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val rows = decodeRows(
            runNodesQuery(project(listOf(a, b)), input(hasParent = true)).data,
        )
        assertEquals(listOf("b"), rows.map { it.id })
    }

    @Test fun hasParentFalseRestrictsToRootsOnly() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(SourceRef(SourceNodeId("a"))),
        )
        val rows = decodeRows(
            runNodesQuery(project(listOf(a, b)), input(hasParent = false)).data,
        )
        assertEquals(listOf("a"), rows.map { it.id })
    }

    // ── sort modes ────────────────────────────────────────────────

    @Test fun defaultSortIsById() {
        // No sortBy → SORT_BY_ID. Pin alphabetic order.
        val z = SourceNode.create(id = SourceNodeId("z"), kind = "k")
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val m = SourceNode.create(id = SourceNodeId("m"), kind = "k")
        val rows = decodeRows(runNodesQuery(project(listOf(z, a, m)), input()).data)
        assertEquals(listOf("a", "m", "z"), rows.map { it.id })
    }

    @Test fun sortByKindUsesIdAsTiebreaker() {
        // Same kind across multiple nodes → primary sort by kind
        // (single value here), secondary by id.
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val rows = decodeRows(
            runNodesQuery(project(listOf(b, a)), input(sortBy = "kind")).data,
        )
        // Both kind=k → tiebreak on id: a before b.
        assertEquals(listOf("a", "b"), rows.map { it.id })
    }

    @Test fun sortByRevisionDescPutsHighestFirst() {
        val high = SourceNode.create(id = SourceNodeId("high"), kind = "k", revision = 10)
        val mid = SourceNode.create(id = SourceNodeId("mid"), kind = "k", revision = 5)
        val low = SourceNode.create(id = SourceNodeId("low"), kind = "k", revision = 1)
        val rows = decodeRows(
            runNodesQuery(project(listOf(low, high, mid)), input(sortBy = "revision-desc")).data,
        )
        assertEquals(listOf("high", "mid", "low"), rows.map { it.id })
    }

    @Test fun invalidSortByErrorsLoudWithValidValues() {
        val ex = assertFailsWith<IllegalArgumentException> {
            runNodesQuery(project(emptyList()), input(sortBy = "popularity"))
        }
        val msg = ex.message ?: ""
        assertTrue("Invalid sortBy" in msg, "got: $msg")
        assertTrue("id" in msg && "kind" in msg && "revision-desc" in msg, "lists valid values; got: $msg")
    }

    @Test fun blankSortByFallsBackToDefault() {
        // Per code: `input.sortBy?.trim()?.lowercase()?.ifBlank { null } ?: SORT_BY_ID`
        // Blank string, whitespace-only, etc., all default to "id".
        val z = SourceNode.create(id = SourceNodeId("z"), kind = "k")
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(runNodesQuery(project(listOf(z, a)), input(sortBy = "  ")).data)
        assertEquals(listOf("a", "z"), rows.map { it.id }, "blank sortBy falls back to id sort")
    }

    @Test fun caseInsensitiveSortByValueAccepted() {
        // The function lower-cases sortBy → "ID" or "Id" should
        // resolve to SORT_BY_ID.
        val z = SourceNode.create(id = SourceNodeId("z"), kind = "k")
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(runNodesQuery(project(listOf(z, a)), input(sortBy = "ID")).data)
        assertEquals(listOf("a", "z"), rows.map { it.id })
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitClampsToOneAtMinimum() {
        val nodes = (1..3).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }
        val result = runNodesQuery(project(nodes), input(limit = 0))
        assertEquals(1, decodeRows(result.data).size)
        assertEquals(3, result.data.total)
    }

    @Test fun limitClampsToFiveHundredAtMaximum() {
        val nodes = (1..3).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }
        val result = runNodesQuery(project(nodes), input(limit = 1_000))
        // 1000 → 500 → file has 3 → returns 3.
        assertEquals(3, decodeRows(result.data).size)
    }

    @Test fun offsetSkipsFirstNRowsOfSorted() {
        val nodes = (1..5).map {
            SourceNode.create(id = SourceNodeId("n${it.toString().padStart(2, '0')}"), kind = "k")
        }
        val result = runNodesQuery(project(nodes), input(offset = 2, limit = 100))
        val rows = decodeRows(result.data)
        // Sort by id → n01..n05; offset=2 → start at n03.
        assertEquals(3, rows.size)
        assertEquals("n03", rows[0].id)
    }

    @Test fun negativeOffsetCoercesToZero() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(runNodesQuery(project(listOf(a)), input(offset = -10)).data)
        assertEquals(1, rows.size)
    }

    // ── includeBody ───────────────────────────────────────────────

    @Test fun includeBodyTruePopulatesBodyField() {
        val body = buildJsonObject { put("name", "Mei") }
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k", body = body)
        val rows = decodeRows(runNodesQuery(project(listOf(a)), input(includeBody = true)).data)
        assertEquals(body, rows.single().body)
    }

    @Test fun includeBodyFalseLeavesBodyNull() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val rows = decodeRows(runNodesQuery(project(listOf(a)), input(includeBody = false)).data)
        assertNull(rows.single().body)
    }

    // ── outputForLlm + title ──────────────────────────────────────

    @Test fun outputForLlmIncludesProjectRevisionAndCounts() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val out = runNodesQuery(project(listOf(a)), input()).outputForLlm
        assertTrue("Source revision 31" in out, "revision; got: $out")
        assertTrue("1 total node(s)" in out, "total count; got: $out")
        assertTrue("1 match(es)" in out, "match count; got: $out")
        assertTrue("1 returned" in out, "returned; got: $out")
    }

    @Test fun outputForLlmIncludesScopeLabelWhenFiltersApplied() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "narrative.scene")
        val out = runNodesQuery(
            project(listOf(a)),
            input(kind = "narrative.scene"),
        ).outputForLlm
        assertTrue("(kind=narrative.scene)" in out, "kind label; got: $out")
    }

    @Test fun outputForLlmHasParentLabelTagsRootsVsChildren() {
        // hasParent=true label says "(children)"; false says "(roots)".
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val outChildren = runNodesQuery(project(listOf(a)), input(hasParent = true)).outputForLlm
        assertTrue(
            "hasParent=true (children)" in outChildren,
            "children label; got: $outChildren",
        )
        val outRoots = runNodesQuery(project(listOf(a)), input(hasParent = false)).outputForLlm
        assertTrue(
            "hasParent=false (roots)" in outRoots,
            "roots label; got: $outRoots",
        )
    }

    @Test fun outputForLlmTailUsesIdKindSummaryFormat() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "narrative.scene")
        val out = runNodesQuery(project(listOf(a)), input()).outputForLlm
        // Pin tail format: "- <id> (<kind>): <summary>".
        assertTrue("- a (narrative.scene):" in out, "tail format; got: $out")
    }

    @Test fun outputForLlmShowsCapNoteWhenTrimmed() {
        val nodes = (1..5).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }
        val out = runNodesQuery(project(nodes), input(limit = 2)).outputForLlm
        assertTrue("showing 2 of 5" in out, "cap note; got: $out")
        assertTrue("raise limit or use offset" in out, "pagination hint; got: $out")
    }

    @Test fun titleFormatIncludesReturnedSlashTotal() {
        val nodes = (1..5).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }
        val result = runNodesQuery(project(nodes), input(limit = 2))
        assertTrue("(2/5)" in (result.title ?: ""), "title format; got: ${result.title}")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputCarriesSelect() {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val result = runNodesQuery(project(listOf(a)), input())
        assertEquals(SourceQueryTool.SELECT_NODES, result.data.select)
    }
}
