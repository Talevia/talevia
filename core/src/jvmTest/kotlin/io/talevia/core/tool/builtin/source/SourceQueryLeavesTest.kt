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
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.source.query.LeafRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for `select=leaves` on [SourceQueryTool]. A node is a leaf
 * iff no other node lists it as a parent — symmetric companion to
 * roots (`select=nodes&hasParent=false`). Edges (§3a #9):
 *  - empty DAG → empty rows.
 *  - single standalone node → that one node is both root and leaf,
 *    `parentCount=0`.
 *  - single chain `a → b → c` → only `c` is a leaf.
 *  - diamond DAG `a, b → c` (c has two parents) → only `c` is a leaf.
 *  - multi-leaf DAG (two chains under one root) → both tips returned.
 *  - rejects whole-DAG-incompatible filters (kind / pagination).
 */
class SourceQueryLeavesTest {

    private suspend fun fixture(nodes: List<SourceNode>): Triple<FileProjectStore, ToolContext, ProjectId> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-leaves")
        store.upsert("demo", Project(id = pid, timeline = Timeline(), source = Source(nodes = nodes)))
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

    private fun node(
        id: String,
        kind: String = "test.generic",
        parents: List<String> = emptyList(),
        revision: Long = 0,
    ): SourceNode = SourceNode(
        id = SourceNodeId(id),
        kind = kind,
        body = JsonObject(emptyMap()),
        parents = parents.map { SourceRef(SourceNodeId(it)) },
        revision = revision,
    )

    private suspend fun runLeaves(
        store: FileProjectStore,
        ctx: ToolContext,
        pid: ProjectId,
    ): SourceQueryTool.Output = SourceQueryTool(store).execute(
        SourceQueryTool.Input(select = "leaves", projectId = pid.value),
        ctx,
    ).data

    private fun rows(out: SourceQueryTool.Output): List<LeafRow> =
        out.rows.decodeRowsAs(LeafRow.serializer())

    @Test fun emptyDagReturnsNoRows() = runTest {
        val (store, ctx, pid) = fixture(nodes = emptyList())
        val out = runLeaves(store, ctx, pid)
        assertEquals(0, out.total)
        assertEquals(emptyList(), rows(out))
    }

    @Test fun standaloneNodeIsBothRootAndLeaf() = runTest {
        val (store, ctx, pid) = fixture(nodes = listOf(node("lone", kind = "k.lone")))
        val out = runLeaves(store, ctx, pid)
        val got = rows(out)
        assertEquals(1, got.size)
        assertEquals("lone", got.single().id)
        assertEquals(0, got.single().parentCount, "standalone has no parents")
    }

    @Test fun chainOnlyTipIsLeaf() = runTest {
        val (store, ctx, pid) = fixture(
            nodes = listOf(
                node("a"),
                node("b", parents = listOf("a")),
                node("c", parents = listOf("b"), revision = 4),
            ),
        )
        val got = rows(runLeaves(store, ctx, pid))
        assertEquals(listOf("c"), got.map { it.id })
        assertEquals(4L, got.single().revision)
        assertEquals(1, got.single().parentCount)
    }

    @Test fun diamondCnodeIsTheLeaf() = runTest {
        val (store, ctx, pid) = fixture(
            nodes = listOf(
                node("a"),
                node("b"),
                node("c", parents = listOf("a", "b")),
            ),
        )
        val got = rows(runLeaves(store, ctx, pid))
        assertEquals(listOf("c"), got.map { it.id })
        assertEquals(2, got.single().parentCount, "diamond child has two parents")
    }

    @Test fun multiBranchTreeReturnsAllTips() = runTest {
        val (store, ctx, pid) = fixture(
            nodes = listOf(
                node("root"),
                node("c1", parents = listOf("root")),
                node("c2", parents = listOf("root")),
                node("d1", parents = listOf("c1")),
            ),
        )
        val got = rows(runLeaves(store, ctx, pid))
        // Sorted by id — c2 before d1.
        assertEquals(listOf("c2", "d1"), got.map { it.id })
    }

    @Test fun incompatibleFilterRejected() = runTest {
        val (store, ctx, pid) = fixture(nodes = listOf(node("x")))
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(store).execute(
                SourceQueryTool.Input(select = "leaves", projectId = pid.value, kind = "test.generic"),
                ctx,
            )
        }
        assertTrue("kind" in ex.message!!, "kind filter must be rejected on leaves: ${ex.message}")
    }

    @Test fun paginationRejected() = runTest {
        val (store, ctx, pid) = fixture(nodes = listOf(node("x"), node("y")))
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(store).execute(
                SourceQueryTool.Input(select = "leaves", projectId = pid.value, limit = 1),
                ctx,
            )
        }
        assertTrue("limit" in ex.message!!, "limit must be rejected on leaves (no pagination): ${ex.message}")
    }
}
