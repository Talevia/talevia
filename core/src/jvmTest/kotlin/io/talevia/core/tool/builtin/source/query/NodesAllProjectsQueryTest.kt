package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.coroutines.test.runTest
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
 * Direct tests for [runNodesAllProjectsQuery] —
 * `source_query(scope=all_projects, select=nodes)`. Closes the
 * VISION §5.1 "跨 project 复用" axis: enumerate source nodes
 * across every project in the store with `projectId` carried on
 * each row. Cycle 114 audit: 179 LOC, **zero** transitive test
 * references; the query has substantial filter / sort / paging
 * surface that was previously unprotected.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`projectId` carried on every cross-project row.** The
 *    marquee differentiator from `select=nodes` (single-project).
 *    Without `projectId`, the cross-project caller can't tell
 *    "this character_ref lives on project X" from "...on project
 *    Y" — the whole point of the scope. A regression dropping
 *    the field would silently collapse the cross-project view
 *    into a flat "where do these come from?" mess.
 *
 * 2. **Stable sort with projectId tiebreaker.** All three
 *    sortBy modes (id / kind / revision-desc) thenBy projectId
 *    so the page is byte-stable across calls. UI consumers
 *    expect re-running the query on the same store to produce
 *    identical pagination. A regression dropping the tiebreaker
 *    would shuffle equal-key rows nondeterministically.
 *
 * 3. **Output `sourceRevision` is 0L (deliberately).** Per the
 *    kdoc: "not meaningful across multiple projects; per-project
 *    revision lands on each row's projectId context instead."
 *    A regression returning a real revision number would
 *    invite cache-invalidation bugs in UI consumers that
 *    expect single-project stable revision semantics.
 */
class NodesAllProjectsQueryTest {

    private suspend fun setupStore(
        projects: List<Pair<String, List<SourceNode>>>,
    ): io.talevia.core.domain.FileProjectStore {
        val store = ProjectStoreTestKit.create()
        for ((id, nodes) in projects) {
            val proj = Project(
                id = ProjectId(id),
                timeline = Timeline(),
                source = Source(nodes = nodes, revision = 99),
            )
            store.upsert("test-$id", proj)
        }
        return store
    }

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
        scope = SourceQueryTool.SCOPE_ALL_PROJECTS,
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

    // ── empty / single-project / multi-project shape ──────────────

    @Test fun emptyStoreReturnsZeroMatches() = runTest {
        val store = setupStore(emptyList())
        val result = runNodesAllProjectsQuery(store, input())
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertEquals(emptyList(), decodeRows(result.data))
    }

    @Test fun rowsCarryOwningProjectId() = runTest {
        // The marquee pin. A node from project p1 must surface
        // with `projectId="p1"` so cross-project audits can
        // pinpoint hits.
        val n1 = SourceNode.create(id = SourceNodeId("n1"), kind = "k")
        val n2 = SourceNode.create(id = SourceNodeId("n2"), kind = "k")
        val store = setupStore(
            listOf("p1" to listOf(n1), "p2" to listOf(n2)),
        )
        val rows = decodeRows(runNodesAllProjectsQuery(store, input()).data)
        val byId = rows.associateBy { it.id }
        assertEquals("p1", byId.getValue("n1").projectId)
        assertEquals("p2", byId.getValue("n2").projectId)
    }

    @Test fun multiProjectUnionReturnsAllNodesFromEveryProject() = runTest {
        val store = setupStore(
            listOf(
                "p1" to listOf(
                    SourceNode.create(id = SourceNodeId("n1"), kind = "k"),
                    SourceNode.create(id = SourceNodeId("n2"), kind = "k"),
                ),
                "p2" to listOf(
                    SourceNode.create(id = SourceNodeId("n3"), kind = "k"),
                ),
            ),
        )
        val result = runNodesAllProjectsQuery(store, input())
        assertEquals(3, result.data.total, "3 nodes across 2 projects")
    }

    // ── stable sort with projectId tiebreaker ─────────────────────

    @Test fun sortByIdUsesProjectIdAsTiebreakerForEqualNodeIds() {
        // Same node id "shared" present in both p1 and p2 (legal
        // — ids are project-scoped). Pin: sort by id then by
        // projectId — the row from p1 comes before the row from
        // p2 even though node id collides.
        runTest {
            val n = SourceNode.create(id = SourceNodeId("shared"), kind = "k")
            val store = setupStore(
                // Insertion order p2-then-p1 to ensure the tiebreaker
                // ACTIVELY sorts (not coincidentally aligned).
                listOf("p2" to listOf(n), "p1" to listOf(n)),
            )
            val rows = decodeRows(runNodesAllProjectsQuery(store, input()).data)
            assertEquals(2, rows.size)
            // Pin: p1 row before p2 row (alphabetic projectId tiebreaker).
            assertEquals("p1", rows[0].projectId, "p1 first; got: ${rows.map { it.projectId }}")
            assertEquals("p2", rows[1].projectId)
        }
    }

    @Test fun sortByKindUsesIdThenProjectIdAsTiebreakers() = runTest {
        // Same kind across multiple projects → primary sort by
        // kind (single value here), secondary by id, tertiary by
        // projectId.
        val n1 = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val n2 = SourceNode.create(id = SourceNodeId("b"), kind = "k")
        val store = setupStore(listOf("p1" to listOf(n2), "p2" to listOf(n1)))
        val rows = decodeRows(
            runNodesAllProjectsQuery(store, input(sortBy = "kind")).data,
        )
        // Both kind=k → tiebreak on id: a (in p2) before b (in p1).
        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertEquals("p2", rows[0].projectId)
        assertEquals("p1", rows[1].projectId)
    }

    @Test fun sortByRevisionDescPutsHighestRevisionFirst() = runTest {
        val high = SourceNode.create(id = SourceNodeId("high"), kind = "k", revision = 10)
        val mid = SourceNode.create(id = SourceNodeId("mid"), kind = "k", revision = 5)
        val low = SourceNode.create(id = SourceNodeId("low"), kind = "k", revision = 1)
        val store = setupStore(listOf("p1" to listOf(low, high, mid)))
        val rows = decodeRows(
            runNodesAllProjectsQuery(store, input(sortBy = "revision-desc")).data,
        )
        assertEquals(listOf("high", "mid", "low"), rows.map { it.id })
    }

    @Test fun invalidSortByErrorsLoudWithValidValues() = runTest {
        val store = setupStore(listOf("p1" to emptyList()))
        val ex = assertFailsWith<IllegalArgumentException> {
            runNodesAllProjectsQuery(store, input(sortBy = "popularity"))
        }
        val msg = ex.message ?: ""
        assertTrue("Invalid sortBy" in msg, "must mention invalid input; got: $msg")
        // Pin: error names the valid alternatives so LLM can self-recover.
        assertTrue("id" in msg && "kind" in msg && "revision-desc" in msg, "must list valid; got: $msg")
    }

    // ── filters: kind / kindPrefix / id / hasParent ───────────────

    @Test fun kindFilterRestrictsToExactMatch() = runTest {
        // Use generic kinds (not the consistency primitives) so the
        // `humanSummaryAllProjects` reflection branches stay on the
        // opaque-body fallback — empty bodies on consistency kinds
        // would fail deserialization in the summary helper.
        val char = SourceNode.create(id = SourceNodeId("c"), kind = "narrative.scene")
        val style = SourceNode.create(id = SourceNodeId("s"), kind = "narrative.shot")
        val store = setupStore(listOf("p1" to listOf(char, style)))
        val rows = decodeRows(
            runNodesAllProjectsQuery(store, input(kind = "narrative.scene")).data,
        )
        assertEquals(listOf("c"), rows.map { it.id }, "only narrative.scene kind matches")
    }

    @Test fun kindPrefixFilterRestrictsToPrefixedKinds() = runTest {
        // Generic kinds with a shared prefix so humanSummary stays on
        // the opaque-body branch (consistency kinds need typed bodies
        // their summary helper deserializes).
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "narrative.scene")
        val b = SourceNode.create(id = SourceNodeId("b"), kind = "narrative.shot")
        val c = SourceNode.create(id = SourceNodeId("c"), kind = "vlog.raw_footage")
        val store = setupStore(listOf("p1" to listOf(a, b, c)))
        val rows = decodeRows(
            runNodesAllProjectsQuery(store, input(kindPrefix = "narrative")).data,
        )
        // Only narrative.* nodes match.
        assertEquals(setOf("a", "b"), rows.map { it.id }.toSet())
    }

    @Test fun idFilterRestrictsToExactNodeId() = runTest {
        val n1 = SourceNode.create(id = SourceNodeId("target"), kind = "k")
        val n2 = SourceNode.create(id = SourceNodeId("other"), kind = "k")
        // Same id "target" exists in both p1 and p2 → both rows.
        val store = setupStore(
            listOf(
                "p1" to listOf(n1, n2),
                "p2" to listOf(SourceNode.create(id = SourceNodeId("target"), kind = "k")),
            ),
        )
        val rows = decodeRows(
            runNodesAllProjectsQuery(store, input(nodeId = "target")).data,
        )
        assertEquals(2, rows.size, "both projects' 'target' surface")
        assertEquals(setOf("p1", "p2"), rows.map { it.projectId }.toSet())
    }

    @Test fun hasParentFilterRestrictsToWithOrWithoutParents() = runTest {
        val a = SourceNode.create(id = SourceNodeId("a"), kind = "k")
        val b = SourceNode.create(
            id = SourceNodeId("b"),
            kind = "k",
            parents = listOf(io.talevia.core.domain.source.SourceRef(SourceNodeId("a"))),
        )
        val store = setupStore(listOf("p1" to listOf(a, b)))
        // hasParent=true → only b.
        val withParent = decodeRows(
            runNodesAllProjectsQuery(store, input(hasParent = true)).data,
        )
        assertEquals(listOf("b"), withParent.map { it.id })
        // hasParent=false → only a.
        val withoutParent = decodeRows(
            runNodesAllProjectsQuery(store, input(hasParent = false)).data,
        )
        assertEquals(listOf("a"), withoutParent.map { it.id })
    }

    // ── content substring search ──────────────────────────────────

    @Test fun contentSubstringMatchPopulatesSnippetAndOffset() = runTest {
        val n = SourceNode.create(
            id = SourceNodeId("n"),
            kind = "k",
            body = buildJsonObject {
                put("description", "the quick brown fox jumps over the lazy dog")
            },
        )
        val store = setupStore(listOf("p1" to listOf(n)))
        val result = runNodesAllProjectsQuery(
            store,
            input(contentSubstring = "fox"),
        )
        val rows = decodeRows(result.data)
        assertEquals(1, rows.size)
        // Pin: snippet + matchOffset populated when contentSubstring matches.
        assertNotNull(rows[0].snippet, "snippet must be set")
        assertNotNull(rows[0].matchOffset, "matchOffset must be set")
        assertTrue("fox" in rows[0].snippet!!, "snippet contains the match; got: ${rows[0].snippet}")
    }

    @Test fun contentSubstringMissNotIncluded() = runTest {
        // Pin: a node without the substring DOES NOT surface
        // (filtered, not included with snippet=null).
        val n = SourceNode.create(
            id = SourceNodeId("n"),
            kind = "k",
            body = buildJsonObject { put("description", "no match here") },
        )
        val store = setupStore(listOf("p1" to listOf(n)))
        val result = runNodesAllProjectsQuery(store, input(contentSubstring = "fox"))
        assertEquals(0, decodeRows(result.data).size)
    }

    @Test fun caseInsensitiveSearchMatchesUpperCaseInBody() = runTest {
        // Pin: caseSensitive=false (or null default) lower-cases
        // both haystack and needle for matching.
        val n = SourceNode.create(
            id = SourceNodeId("n"),
            kind = "k",
            body = buildJsonObject { put("description", "FOX") },
        )
        val store = setupStore(listOf("p1" to listOf(n)))
        val result = runNodesAllProjectsQuery(
            store,
            input(contentSubstring = "fox", caseSensitive = false),
        )
        assertEquals(1, decodeRows(result.data).size)
    }

    @Test fun caseSensitiveSearchRespectsCase() = runTest {
        val n = SourceNode.create(
            id = SourceNodeId("n"),
            kind = "k",
            body = buildJsonObject { put("description", "FOX") },
        )
        val store = setupStore(listOf("p1" to listOf(n)))
        // caseSensitive=true → "fox" doesn't match "FOX".
        val result = runNodesAllProjectsQuery(
            store,
            input(contentSubstring = "fox", caseSensitive = true),
        )
        assertEquals(0, decodeRows(result.data).size)
    }

    // ── includeBody ───────────────────────────────────────────────

    @Test fun includeBodyTruePopulatesRowBody() = runTest {
        val body = buildJsonObject { put("name", "Mei") }
        val n = SourceNode.create(id = SourceNodeId("n"), kind = "k", body = body)
        val store = setupStore(listOf("p1" to listOf(n)))
        val rows = decodeRows(
            runNodesAllProjectsQuery(store, input(includeBody = true)).data,
        )
        assertEquals(body, rows[0].body, "body round-trips when includeBody=true")
    }

    @Test fun includeBodyFalseLeavesRowBodyNull() = runTest {
        val n = SourceNode.create(id = SourceNodeId("n"), kind = "k")
        val store = setupStore(listOf("p1" to listOf(n)))
        val rows = decodeRows(
            runNodesAllProjectsQuery(store, input(includeBody = false)).data,
        )
        assertNull(rows[0].body, "body null when includeBody=false")
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitClampsToOneAtMinimum() = runTest {
        val nodes = (1..3).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }
        val store = setupStore(listOf("p1" to nodes))
        // limit=0 → coerceIn(1, 500) → 1.
        val result = runNodesAllProjectsQuery(store, input(limit = 0))
        assertEquals(1, decodeRows(result.data).size)
        // total still reflects unfiltered count.
        assertEquals(3, result.data.total)
    }

    @Test fun limitClampsToFiveHundredAtMaximum() = runTest {
        val nodes = (1..10).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }
        val store = setupStore(listOf("p1" to nodes))
        val result = runNodesAllProjectsQuery(store, input(limit = 1_000))
        // 1000 → 500 → file has 10 → returns 10.
        assertEquals(10, decodeRows(result.data).size)
    }

    @Test fun offsetSkipsFirstNRowsOfSorted() = runTest {
        val nodes = (1..5).map {
            SourceNode.create(id = SourceNodeId("n${it.toString().padStart(2, '0')}"), kind = "k")
        }
        val store = setupStore(listOf("p1" to nodes))
        val result = runNodesAllProjectsQuery(store, input(offset = 2, limit = 100))
        val rows = decodeRows(result.data)
        assertEquals(3, rows.size, "3 of 5 after offset=2")
        // Sorted by id: n01 / n02 / n03 / n04 / n05; offset=2 → n03 first.
        assertEquals("n03", rows[0].id)
    }

    @Test fun negativeOffsetCoercesToZero() = runTest {
        val nodes = listOf(SourceNode.create(id = SourceNodeId("a"), kind = "k"))
        val store = setupStore(listOf("p1" to nodes))
        val result = runNodesAllProjectsQuery(store, input(offset = -5))
        assertEquals(1, decodeRows(result.data).size, "negative offset → 0; full result")
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun outputSourceRevisionIsZeroForCrossProjectScope() = runTest {
        // Marquee pin: per kdoc, `sourceRevision = 0L` is
        // deliberate — cross-project union has no single
        // meaningful source.revision. UI consumers expecting
        // monotonically-increasing revisions will recognize 0L
        // as the cross-project sentinel.
        val n = SourceNode.create(id = SourceNodeId("n"), kind = "k")
        val store = setupStore(listOf("p1" to listOf(n)))
        val result = runNodesAllProjectsQuery(store, input())
        assertEquals(0L, result.data.sourceRevision, "cross-project sourceRevision is 0 sentinel")
        assertEquals(SourceQueryTool.SELECT_NODES, result.data.select)
    }

    @Test fun outputForLlmIncludesProjectCountAndCapNoteWhenTrimmed() = runTest {
        val nodes = (1..5).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }
        val store = setupStore(listOf("p1" to nodes))
        val result = runNodesAllProjectsQuery(store, input(limit = 2))
        val out = result.outputForLlm
        assertTrue("Searched 1 project(s)" in out, "project count; got: $out")
        assertTrue("5 match(es)" in out, "total count; got: $out")
        assertTrue("2 returned" in out, "returned count; got: $out")
        assertTrue("showing 2 of 5" in out, "cap note when trimmed; got: $out")
        assertTrue("raise limit or use offset" in out, "pagination hint; got: $out")
    }

    @Test fun outputForLlmTailUsesProjectIdSlashIdSlashKindFormat() = runTest {
        // Pin: tail format "- <projectId>/<id> (<kind>): <summary>"
        // — UI consumers parse this for compact display.
        val n = SourceNode.create(id = SourceNodeId("n"), kind = "k")
        val store = setupStore(listOf("p1" to listOf(n)))
        val result = runNodesAllProjectsQuery(store, input())
        val out = result.outputForLlm
        assertTrue("- p1/n (k):" in out, "tail format; got: $out")
    }

    @Test fun outputForLlmEmptyPathSaysNoMatches() = runTest {
        val store = setupStore(listOf("p1" to emptyList()))
        val result = runNodesAllProjectsQuery(store, input())
        assertTrue("no matches" in result.outputForLlm, "empty marker; got: ${result.outputForLlm}")
    }

    @Test fun titleIncludesReturnedSlashTotal() = runTest {
        val nodes = (1..5).map { SourceNode.create(id = SourceNodeId("n$it"), kind = "k") }
        val store = setupStore(listOf("p1" to nodes))
        val result = runNodesAllProjectsQuery(store, input(limit = 2))
        // Pin: title format "(returned/total)".
        assertTrue(
            "(2/5)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }
}
