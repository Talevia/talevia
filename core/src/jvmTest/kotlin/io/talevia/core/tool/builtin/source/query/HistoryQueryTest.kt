package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.BodyRevision
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
import kotlin.test.assertTrue

/**
 * Direct tests for [runHistoryQuery] — `source_query(select=
 * history, root=<nodeId>)`, the past-body-snapshots audit lane
 * for a single source node. Cycle 113 audit: 108 LOC, 1 transitive
 * test ref (only via integration through the SourceQueryTool
 * dispatcher); the four narrative branches that vary on
 * (nodeExists × historyEmpty) were never explicitly pinned.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Four-branch narrative on (nodeExists × historyEmpty).** The
 *    LLM-facing narrative MUST distinguish between four cases:
 *    - !nodeExists + emptyHistory → "not on the DAG and no history"
 *    - !nodeExists + history → "no longer on the DAG, but N past
 *      revision(s) preserved"
 *    - nodeExists + emptyHistory → "exists but has no body-history
 *      entries"
 *    - nodeExists + history → "N past revision(s) ... newest first"
 *    Each branch tells the LLM a different next step. A regression
 *    collapsing them would force the LLM to guess what state the
 *    node is in.
 *
 * 2. **Newest-first ordering** — `rows.first()` is the most-recent
 *    overwritten state, `rows.last()` is the oldest in the window.
 *    The kdoc explicitly commits to this. JSONL is append-order
 *    (oldest-first) on disk; the function reverses it. A regression
 *    dropping the reversal would surface stale draft text as the
 *    "most recent" — confusing for "what did this prompt look like
 *    last week?" audits.
 *
 * 3. **Limit clamping to [1, 100].** Default 20; values < 1 clamp
 *    to 1; values > 100 clamp to 100. A regression dropping the
 *    coerceIn would either (a) crash on negative limits or (b) let
 *    the LLM ask for unbounded history (memory blowup).
 */
class HistoryQueryTest {

    private val nodeId = SourceNodeId("a")

    private suspend fun setupStoreWithProject(
        nodes: List<SourceNode> = emptyList(),
    ): Pair<io.talevia.core.domain.FileProjectStore, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val projectId = ProjectId("p")
        val project = Project(
            id = projectId,
            timeline = Timeline(),
            source = Source(nodes = nodes, revision = 3),
        )
        store.upsert("test", project)
        return store to projectId
    }

    private suspend fun primeRevisions(
        store: io.talevia.core.domain.FileProjectStore,
        projectId: ProjectId,
        nodeId: SourceNodeId,
        revisions: List<BodyRevision>,
    ) {
        // appendSourceNodeHistory writes JSONL append-order — oldest first.
        for (rev in revisions) store.appendSourceNodeHistory(projectId, nodeId, rev)
    }

    private fun input(rootId: String?, limit: Int? = null) = SourceQueryTool.Input(
        select = SourceQueryTool.SELECT_HISTORY,
        projectId = "p",
        root = rootId,
        limit = limit,
    )

    private fun decodeRows(out: SourceQueryTool.Output): List<BodyRevisionRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(BodyRevisionRow.serializer()),
            out.rows,
        )

    private fun rev(epoch: Long, label: String) = BodyRevision(
        body = buildJsonObject { put("label", label) },
        overwrittenAtEpochMs = epoch,
    )

    // ── input validation ──────────────────────────────────────────

    @Test fun missingRootErrorsLoudWithRecoveryHint() = runTest {
        val (store, _) = setupStoreWithProject()
        val proj = store.get(ProjectId("p"))!!
        val ex = assertFailsWith<IllegalStateException> {
            runHistoryQuery(store, proj, input(rootId = null))
        }
        val msg = ex.message ?: ""
        assertTrue("root" in msg, "must mention root parameter; got: $msg")
        // Pin the recovery hint so the LLM can self-recover.
        assertTrue(
            "source_query(select=nodes)" in msg,
            "must point at recovery query; got: $msg",
        )
    }

    // ── 4-branch narrative ────────────────────────────────────────

    @Test fun nodeNotOnDagAndNoHistoryNarrativeMentionsBoth() = runTest {
        // Branch 1: !nodeExists + emptyHistory.
        // Pin: narrative MUST include both signals so the LLM knows
        // it's chasing ghost state from a typo / stale id.
        val (store, _) = setupStoreWithProject()
        val proj = store.get(ProjectId("p"))!!
        val result = runHistoryQuery(store, proj, input("ghost"))
        val out = result.outputForLlm
        assertTrue("ghost" in out, "must name the queried id; got: $out")
        assertTrue("not on the current DAG" in out, "DAG-absent signal; got: $out")
        assertTrue("no history file exists" in out, "history-absent signal; got: $out")
        assertTrue(
            "source_query(select=nodes)" in out,
            "must point at recovery; got: $out",
        )
        assertEquals(0, result.data.total, "no rows on this branch")
    }

    @Test fun deletedNodeButPreservedHistoryNarrativeFlagsAuditTrail() = runTest {
        // Branch 2: !nodeExists + history present. Audit-trail use
        // case — the node was deleted but its history file remains
        // in the bundle. Pin: narrative explicitly notes "deleted
        // nodes keep their audit trail".
        val (store, projectId) = setupStoreWithProject()
        primeRevisions(
            store,
            projectId,
            SourceNodeId("dead"),
            listOf(rev(1L, "v1"), rev(2L, "v2")),
        )
        val proj = store.get(projectId)!!
        val result = runHistoryQuery(store, proj, input("dead"))
        val out = result.outputForLlm
        assertTrue(
            "no longer on the current DAG" in out,
            "absence signal; got: $out",
        )
        assertTrue("audit trail" in out, "audit-trail hint; got: $out")
        // Two revisions preserved.
        assertEquals(2, result.data.total)
    }

    @Test fun existingNodeWithNoHistoryNarrativeMentionsTwoCauses() = runTest {
        // Branch 3: nodeExists + emptyHistory. Either (a) no
        // update_source_node_body called yet, or (b) bundle predates
        // history-tracking. Pin both possibilities so the LLM knows
        // the empty result isn't necessarily a bug.
        val a = SourceNode.create(id = nodeId, kind = "k")
        val (store, projectId) = setupStoreWithProject(listOf(a))
        val proj = store.get(projectId)!!
        val result = runHistoryQuery(store, proj, input("a"))
        val out = result.outputForLlm
        assertTrue("exists but has no body-history" in out, "no-history hint; got: $out")
        assertTrue("update_source_node_body" in out, "cause (a) — never updated; got: $out")
        assertTrue("predates body-history" in out, "cause (b) — old bundle; got: $out")
        assertEquals(0, result.data.total)
    }

    @Test fun existingNodeWithHistoryNarrativeIncludesCountAndEpoch() = runTest {
        // Branch 4: nodeExists + history. Standard happy path.
        val a = SourceNode.create(id = nodeId, kind = "k")
        val (store, projectId) = setupStoreWithProject(listOf(a))
        primeRevisions(
            store,
            projectId,
            nodeId,
            listOf(rev(100L, "first"), rev(200L, "second"), rev(300L, "third")),
        )
        val proj = store.get(projectId)!!
        val result = runHistoryQuery(store, proj, input("a"))
        val out = result.outputForLlm
        assertTrue("3 past revision(s)" in out, "count; got: $out")
        assertTrue("newest first" in out, "ordering hint; got: $out")
        // Most-recent epoch surfaced — that's the third entry on
        // disk (latest write), which becomes rows.first() after the
        // reversal.
        assertTrue("epoch-ms 300" in out, "newest epoch; got: $out")
        assertEquals(3, result.data.total)
    }

    // ── newest-first ordering ─────────────────────────────────────

    @Test fun rowsAreReturnedNewestFirst() = runTest {
        // The marquee pin. JSONL is append-order (oldest-first) on
        // disk; the function reverses it. A regression dropping the
        // reversal would surface stale draft text as "most recent",
        // confusing audit workflows.
        val a = SourceNode.create(id = nodeId, kind = "k")
        val (store, projectId) = setupStoreWithProject(listOf(a))
        primeRevisions(
            store,
            projectId,
            nodeId,
            listOf(rev(100L, "oldest"), rev(200L, "middle"), rev(300L, "newest")),
        )
        val proj = store.get(projectId)!!
        val rows = decodeRows(runHistoryQuery(store, proj, input("a")).data)
        // Pin: rows.first() = newest, rows.last() = oldest.
        assertEquals(300L, rows.first().overwrittenAtEpochMs, "newest first")
        assertEquals(100L, rows.last().overwrittenAtEpochMs, "oldest last")
        // Body content round-trips per row.
        assertEquals(
            "newest",
            (rows.first().body as kotlinx.serialization.json.JsonObject)["label"]!!
                .let { (it as kotlinx.serialization.json.JsonPrimitive).content },
            "newest body content",
        )
    }

    // ── limit clamping ────────────────────────────────────────────

    @Test fun limitClampsToOneWhenZeroOrNegative() = runTest {
        // coerceIn(1, 100) — 0 / negative limits clamp to 1.
        val a = SourceNode.create(id = nodeId, kind = "k")
        val (store, projectId) = setupStoreWithProject(listOf(a))
        primeRevisions(
            store,
            projectId,
            nodeId,
            listOf(rev(100L, "a"), rev(200L, "b"), rev(300L, "c")),
        )
        val proj = store.get(projectId)!!
        // Limit 0 → clamps to 1; only the newest survives.
        val zeroLimit = runHistoryQuery(store, proj, input("a", limit = 0))
        assertEquals(1, decodeRows(zeroLimit.data).size, "limit=0 clamps to 1")
        // Limit -5 → also clamps to 1.
        val negLimit = runHistoryQuery(store, proj, input("a", limit = -5))
        assertEquals(1, decodeRows(negLimit.data).size, "negative limit clamps to 1")
    }

    @Test fun limitClampsToOneHundredWhenAboveCap() = runTest {
        // coerceIn(1, 100) — limits > 100 clamp to 100. Even with
        // only 3 revisions on disk, the "max effective limit" stays
        // at 100; the actual returned count caps at the available
        // history. Pin both: clamping doesn't error, and the file
        // floor (3 revisions) is the binding constraint.
        val a = SourceNode.create(id = nodeId, kind = "k")
        val (store, projectId) = setupStoreWithProject(listOf(a))
        primeRevisions(
            store,
            projectId,
            nodeId,
            listOf(rev(100L, "a"), rev(200L, "b"), rev(300L, "c")),
        )
        val proj = store.get(projectId)!!
        val result = runHistoryQuery(store, proj, input("a", limit = 1000))
        // 1000 → clamps to 100 → file has 3 → returns 3.
        assertEquals(3, decodeRows(result.data).size, "high limit caps at MAX_HISTORY_LIMIT")
    }

    @Test fun limitDefaultsToTwentyWhenAbsent() = runTest {
        // When no limit param is set, DEFAULT_HISTORY_LIMIT (20)
        // applies. Plant 25 revisions and verify only 20 surface.
        val a = SourceNode.create(id = nodeId, kind = "k")
        val (store, projectId) = setupStoreWithProject(listOf(a))
        primeRevisions(
            store,
            projectId,
            nodeId,
            (1L..25L).map { rev(it * 10, "v$it") },
        )
        val proj = store.get(projectId)!!
        val result = runHistoryQuery(store, proj, input("a")) // no limit
        assertEquals(20, decodeRows(result.data).size, "default limit is 20")
        // Newest-first preserved across the limit cut.
        val rows = decodeRows(result.data)
        assertEquals(250L, rows.first().overwrittenAtEpochMs, "newest in window")
    }

    @Test fun explicitLimitRespectedWithinBounds() = runTest {
        val a = SourceNode.create(id = nodeId, kind = "k")
        val (store, projectId) = setupStoreWithProject(listOf(a))
        primeRevisions(
            store,
            projectId,
            nodeId,
            (1L..10L).map { rev(it * 10, "v$it") },
        )
        val proj = store.get(projectId)!!
        val result = runHistoryQuery(store, proj, input("a", limit = 3))
        assertEquals(3, decodeRows(result.data).size)
    }

    // ── output framing ────────────────────────────────────────────

    @Test fun titleIncludesNodeIdAndCount() = runTest {
        val a = SourceNode.create(id = nodeId, kind = "k")
        val (store, projectId) = setupStoreWithProject(listOf(a))
        primeRevisions(store, projectId, nodeId, listOf(rev(100L, "v1"), rev(200L, "v2")))
        val proj = store.get(projectId)!!
        val result = runHistoryQuery(store, proj, input("a"))
        // Pin: title format "source_query history <id> (<count>)".
        assertTrue(
            "source_query history a (2)" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
    }

    @Test fun outputCarriesSelectAndSourceRevision() = runTest {
        val a = SourceNode.create(id = nodeId, kind = "k")
        val (store, projectId) = setupStoreWithProject(listOf(a))
        val proj = store.get(projectId)!!
        val result = runHistoryQuery(store, proj, input("a"))
        assertEquals(SourceQueryTool.SELECT_HISTORY, result.data.select)
        // sourceRevision = 3 (set in the project's Source).
        assertEquals(3, result.data.sourceRevision, "sourceRevision must round-trip")
    }
}
