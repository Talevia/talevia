package io.talevia.core.tool.builtin.source

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.BodyRevision
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.source.query.BodyRevisionRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers `select=history` on [SourceQueryTool] — the per-node body audit
 * trail surfaced to the agent so "how did this body evolve?" stops
 * depending on git log access to the bundle.
 *
 * Semantic edges (§3a #9):
 *  - newest-first ordering over an append-order (oldest-first) JSONL file
 *  - limit cap shorter than the persisted revision count
 *  - unknown node id → empty rows + a narrative that flags "no history"
 *  - existing node with zero revisions → narrative distinguishes from
 *    "unknown node"
 *  - filter-on-wrong-select rejection (incompatible `kind` on history)
 *  - root required
 */
class SourceQueryHistoryTest {

    private suspend fun fixture(): Triple<FileProjectStore, ToolContext, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-history")
        val seedNode = SourceNode(
            id = SourceNodeId("shot-1"),
            kind = "narrative.shot",
            body = buildJsonObject { put("framing", JsonPrimitive("wide")) },
        )
        store.upsert(
            "demo",
            Project(id = pid, timeline = Timeline(), source = Source(nodes = listOf(seedNode))),
        )
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Triple(store, ctx, pid)
    }

    @Test fun returnsRevisionsNewestFirst() = runTest {
        val (store, ctx, pid) = fixture()
        // Simulate 3 update_source_node_body calls by appending 3 revisions
        // directly to the history store. epoch-ms increases → newest-first
        // at read time is the one with the largest timestamp.
        store.appendSourceNodeHistory(
            pid,
            SourceNodeId("shot-1"),
            BodyRevision(
                body = buildJsonObject { put("framing", JsonPrimitive("v1-oldest")) },
                overwrittenAtEpochMs = 1_000L,
            ),
        )
        store.appendSourceNodeHistory(
            pid,
            SourceNodeId("shot-1"),
            BodyRevision(
                body = buildJsonObject { put("framing", JsonPrimitive("v2-mid")) },
                overwrittenAtEpochMs = 2_000L,
            ),
        )
        store.appendSourceNodeHistory(
            pid,
            SourceNodeId("shot-1"),
            BodyRevision(
                body = buildJsonObject { put("framing", JsonPrimitive("v3-newest")) },
                overwrittenAtEpochMs = 3_000L,
            ),
        )

        val out = SourceQueryTool(store).execute(
            SourceQueryTool.Input(
                select = "history",
                projectId = pid.value,
                root = "shot-1",
            ),
            ctx,
        ).data
        assertEquals(3, out.total, "three revisions appended")
        val rows = out.rows.decodeRowsAs(BodyRevisionRow.serializer())
        assertEquals(
            listOf(3_000L, 2_000L, 1_000L),
            rows.map { it.overwrittenAtEpochMs },
            "rows must be newest-first",
        )
        val newest = rows.first().body as JsonObject
        assertEquals("v3-newest", newest["framing"]!!.toString().trim('"'))
    }

    @Test fun limitCapsTheWindowShorterThanPersistedCount() = runTest {
        val (store, ctx, pid) = fixture()
        repeat(5) { i ->
            store.appendSourceNodeHistory(
                pid,
                SourceNodeId("shot-1"),
                BodyRevision(
                    body = buildJsonObject { put("v", JsonPrimitive(i)) },
                    overwrittenAtEpochMs = (i + 1) * 100L,
                ),
            )
        }
        val out = SourceQueryTool(store).execute(
            SourceQueryTool.Input(
                select = "history",
                projectId = pid.value,
                root = "shot-1",
                limit = 2,
            ),
            ctx,
        ).data
        val rows = out.rows.decodeRowsAs(BodyRevisionRow.serializer())
        assertEquals(2, rows.size, "limit 2 caps the window")
        assertEquals(
            listOf(500L, 400L),
            rows.map { it.overwrittenAtEpochMs },
            "newest 2 revisions retained",
        )
        // `total` echoes the returned count (the JSONL isn't cheaply
        // countable without reading + counting lines twice; returning
        // `total==returned` matches the contract other selects use when
        // there's no distinction between "matched" and "returned").
    }

    @Test fun unknownNodeReturnsEmptyAndAnnotatesNarrative() = runTest {
        val (store, ctx, pid) = fixture()
        val result = SourceQueryTool(store).execute(
            SourceQueryTool.Input(
                select = "history",
                projectId = pid.value,
                root = "never-existed",
            ),
            ctx,
        )
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.rows.size)
        // Narrative should flag the node's absence — the history query is
        // intentionally lenient (no throw), but the agent needs to see that
        // the empty rows mean "no node" as opposed to "node has no history".
        assertTrue(
            "not on the current DAG" in result.outputForLlm,
            "narrative must distinguish unknown node: <${result.outputForLlm}>",
        )
    }

    @Test fun existingNodeWithZeroRevisionsHasDistinctNarrative() = runTest {
        val (store, ctx, pid) = fixture()
        val result = SourceQueryTool(store).execute(
            SourceQueryTool.Input(
                select = "history",
                projectId = pid.value,
                root = "shot-1", // exists but never had its body updated
            ),
            ctx,
        )
        assertEquals(0, result.data.total)
        assertTrue(
            "no body-history entries" in result.outputForLlm,
            "narrative must flag zero-history distinctly from unknown: <${result.outputForLlm}>",
        )
    }

    @Test fun rootIsRequiredForHistorySelect() = runTest {
        val (store, ctx, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(store).execute(
                SourceQueryTool.Input(
                    select = "history",
                    projectId = pid.value,
                    // root = null
                ),
                ctx,
            )
        }
        assertTrue(
            ex.message!!.contains("root"),
            "error must name the missing param: ${ex.message}",
        )
    }

    @Test fun incompatibleKindFilterRejectedOnHistorySelect() = runTest {
        val (store, ctx, pid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(store).execute(
                SourceQueryTool.Input(
                    select = "history",
                    projectId = pid.value,
                    root = "shot-1",
                    kind = "narrative.shot", // kind only applies to select=nodes
                ),
                ctx,
            )
        }
        assertTrue(
            ex.message!!.contains("kind"),
            "reject matrix must name the offending filter: ${ex.message}",
        )
    }
}
