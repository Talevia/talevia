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
import io.talevia.core.tool.builtin.source.query.DagSummaryRow
import io.talevia.core.tool.builtin.source.query.NodeRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceQueryToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val ctx: ToolContext,
        val pid: ProjectId,
    )

    private suspend fun rig(vararg nodes: SourceNode): Rig {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        val project = Project(
            id = pid,
            timeline = Timeline(),
            source = Source(nodes = nodes.toList()),
        )
        store.upsert("demo", project)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx, pid)
    }

    private fun node(
        id: String,
        kind: String = "test.generic",
        body: JsonObject = buildJsonObject {
            put("label", id)
            put("note", "stub content for $id")
        },
    ): SourceNode = SourceNode(id = SourceNodeId(id), kind = kind, body = body)

    // ---- select=nodes ----

    @Test fun nodesListsAllWithDefaultSort() = runTest {
        val rig = rig(
            node("b-node"),
            node("a-node"),
            node("c-node"),
        )
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(select = "nodes", projectId = rig.pid.value),
            rig.ctx,
        ).data
        assertEquals("nodes", out.select)
        assertEquals(3, out.total)
        val rows = out.rows.decodeRowsAs(NodeRow.serializer())
        // Default sortBy=id ascending.
        assertEquals(listOf("a-node", "b-node", "c-node"), rows.map { it.id })
    }

    @Test fun nodesKindPrefixFilter() = runTest {
        val rig = rig(
            node("alpha-1", kind = "test.alpha"),
            node("alpha-2", kind = "test.alpha"),
            node("beta-1", kind = "test.beta"),
        )
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(
                select = "nodes",
                projectId = rig.pid.value,
                kindPrefix = "test.alpha",
            ),
            rig.ctx,
        ).data
        assertEquals(2, out.total)
    }

    @Test fun nodesIdFilterReturnsSingleRow() = runTest {
        val rig = rig(node("mei"), node("lily"), node("char-3"))
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(select = "nodes", projectId = rig.pid.value, id = "lily"),
            rig.ctx,
        ).data
        assertEquals(1, out.total)
        val rows = out.rows.decodeRowsAs(NodeRow.serializer())
        assertEquals("lily", rows.first().id)
    }

    @Test fun nodesContentSubstringFindsAndSnippets() = runTest {
        val rig = rig(
            node(
                "mei",
                body = buildJsonObject {
                    put("name", "Mei")
                    put("visualDescription", "character with NEON teal hair")
                },
            ),
            node(
                "brand",
                body = buildJsonObject {
                    put("name", "brand")
                    put("visualDescription", "warm amber look")
                },
            ),
        )
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(
                select = "nodes",
                projectId = rig.pid.value,
                contentSubstring = "neon",
            ),
            rig.ctx,
        ).data
        assertEquals(1, out.total)
        val rows = out.rows.decodeRowsAs(NodeRow.serializer())
        assertEquals("mei", rows.first().id)
        assertNotNull(rows.first().snippet)
        assertNotNull(rows.first().matchOffset)
    }

    @Test fun nodesContentSubstringCaseSensitiveMisses() = runTest {
        val rig = rig(
            node(
                "mei",
                body = buildJsonObject {
                    put("name", "Mei")
                    put("visualDescription", "neon teal")
                },
            ),
        )
        val hit = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(
                select = "nodes",
                projectId = rig.pid.value,
                contentSubstring = "Mei",
                caseSensitive = true,
            ),
            rig.ctx,
        ).data
        assertEquals(1, hit.total)

        val miss = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(
                select = "nodes",
                projectId = rig.pid.value,
                contentSubstring = "mei",
                caseSensitive = true,
            ),
            rig.ctx,
        ).data
        assertEquals(0, miss.total)
    }

    @Test fun nodesIncludeBodyTogglesJsonBodyField() = runTest {
        val rig = rig(node("mei"))
        val withoutBody = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(select = "nodes", projectId = rig.pid.value),
            rig.ctx,
        ).data
        val withoutRows = withoutBody.rows.decodeRowsAs(NodeRow.serializer())
        assertNull(withoutRows.first().body)

        val withBody = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(select = "nodes", projectId = rig.pid.value, includeBody = true),
            rig.ctx,
        ).data
        val withRows = withBody.rows.decodeRowsAs(NodeRow.serializer())
        assertNotNull(withRows.first().body)
    }

    @Test fun nodesInvalidSortByFailsLoud() = runTest {
        val rig = rig(node("mei"))
        val ex = assertFailsWith<IllegalArgumentException> {
            SourceQueryTool(rig.store).execute(
                SourceQueryTool.Input(select = "nodes", projectId = rig.pid.value, sortBy = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("Invalid sortBy"), ex.message)
    }

    @Test fun nodesLimitAndOffsetPage() = runTest {
        val rig = rig(node("a"), node("b"), node("c"), node("d"), node("e"))
        val page1 = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(select = "nodes", projectId = rig.pid.value, limit = 2, offset = 0),
            rig.ctx,
        ).data
        assertEquals(5, page1.total)
        assertEquals(2, page1.returned)

        val page2 = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(select = "nodes", projectId = rig.pid.value, limit = 2, offset = 2),
            rig.ctx,
        ).data
        assertEquals(2, page2.returned)

        val r1 = page1.rows.decodeRowsAs(NodeRow.serializer())
        val r2 = page2.rows.decodeRowsAs(NodeRow.serializer())
        assertEquals(emptyList(), r1.map { it.id }.intersect(r2.map { it.id }.toSet()).toList())
    }

    // ---- select=dag_summary ----

    @Test fun dagSummaryEmptyProject() = runTest {
        val rig = rig()
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(select = "dag_summary", projectId = rig.pid.value),
            rig.ctx,
        ).data
        assertEquals(1, out.total)
        val rows = out.rows.decodeRowsAs(DagSummaryRow.serializer())
        assertEquals(0, rows.first().nodeCount)
        assertTrue(rows.first().summaryText.contains("empty graph"))
    }

    @Test fun dagSummaryCountsByKind() = runTest {
        val rig = rig(
            node("alpha-1", kind = "test.alpha"),
            node("alpha-2", kind = "test.alpha"),
            node("beta-1", kind = "test.beta"),
        )
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(select = "dag_summary", projectId = rig.pid.value),
            rig.ctx,
        ).data
        val row = out.rows.decodeRowsAs(DagSummaryRow.serializer()).first()
        assertEquals(3, row.nodeCount)
        assertEquals(2, row.nodesByKind["test.alpha"])
        assertEquals(1, row.nodesByKind["test.beta"])
    }

    // ---- cross-select validation ----

    @Test fun invalidSelectFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(rig.store).execute(
                SourceQueryTool.Input(select = "graph", projectId = rig.pid.value),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("select must be one of"), ex.message)
    }

    @Test fun misappliedFilterFailsLoud() = runTest {
        val rig = rig()
        // kindPrefix applies to nodes only.
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(rig.store).execute(
                SourceQueryTool.Input(
                    select = "dag_summary",
                    projectId = rig.pid.value,
                    kindPrefix = "core.consistency.",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("kindPrefix"), ex.message)
    }

    @Test fun missingProjectFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(rig.store).execute(
                SourceQueryTool.Input(select = "nodes", projectId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    // ---- select=descendants / ancestors ----

    /**
     * Builds a tiny diamond DAG:
     *   root → a → c
     *     ↘ b ↗
     * plus an unrelated island `far`.
     */
    private suspend fun diamondRig(): Rig = rig(
        node("root"),
        SourceNode(
            id = SourceNodeId("a"),
            kind = "test.generic",
            body = buildJsonObject { put("label", "a") },
            parents = listOf(SourceRef(SourceNodeId("root"))),
        ),
        SourceNode(
            id = SourceNodeId("b"),
            kind = "test.generic",
            body = buildJsonObject { put("label", "b") },
            parents = listOf(SourceRef(SourceNodeId("root"))),
        ),
        SourceNode(
            id = SourceNodeId("c"),
            kind = "test.generic",
            body = buildJsonObject { put("label", "c") },
            parents = listOf(SourceRef(SourceNodeId("a")), SourceRef(SourceNodeId("b"))),
        ),
        node("far"),
    )

    private fun decodeRows(out: SourceQueryTool.Output) =
        out.rows.decodeRowsAs(NodeRow.serializer())

    @Test fun descendantsWalksReverseParentIndexBfs() = runTest {
        val rig = diamondRig()
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(
                select = "descendants",
                projectId = rig.pid.value,
                root = "root",
            ),
            rig.ctx,
        ).data
        assertEquals("descendants", out.select)
        val rows = decodeRows(out)
        assertEquals(listOf("root", "a", "b", "c"), rows.map { it.id })
        assertEquals(listOf(0, 1, 1, 2), rows.map { it.depthFromRoot })
        // Unrelated island is not reached.
        assertTrue(rows.none { it.id == "far" })
    }

    @Test fun descendantsDepthZeroReturnsOnlyRoot() = runTest {
        val rig = diamondRig()
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(
                select = "descendants",
                projectId = rig.pid.value,
                root = "root",
                depth = 0,
            ),
            rig.ctx,
        ).data
        val rows = decodeRows(out)
        assertEquals(listOf("root"), rows.map { it.id })
        assertEquals(listOf(0), rows.map { it.depthFromRoot })
    }

    @Test fun descendantsDepthOneStopsAtImmediateChildren() = runTest {
        val rig = diamondRig()
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(
                select = "descendants",
                projectId = rig.pid.value,
                root = "root",
                depth = 1,
            ),
            rig.ctx,
        ).data
        val rows = decodeRows(out)
        // c is depth 2, must not appear.
        assertEquals(setOf("root", "a", "b"), rows.map { it.id }.toSet())
    }

    @Test fun descendantsNegativeDepthMeansUnbounded() = runTest {
        val rig = diamondRig()
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(
                select = "descendants",
                projectId = rig.pid.value,
                root = "root",
                depth = -5,
            ),
            rig.ctx,
        ).data
        assertEquals(4, out.total)
    }

    @Test fun ancestorsWalksParentsUpwardBfs() = runTest {
        val rig = diamondRig()
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(
                select = "ancestors",
                projectId = rig.pid.value,
                root = "c",
            ),
            rig.ctx,
        ).data
        assertEquals("ancestors", out.select)
        val rows = decodeRows(out)
        // c itself is depth 0; a and b are both depth 1; root is depth 2 (dedup across both paths).
        assertEquals(setOf("c", "a", "b", "root"), rows.map { it.id }.toSet())
        val depthByNode = rows.associate { it.id to it.depthFromRoot }
        assertEquals(0, depthByNode["c"])
        assertEquals(1, depthByNode["a"])
        assertEquals(1, depthByNode["b"])
        assertEquals(2, depthByNode["root"])
    }

    @Test fun descendantsUnknownRootFailsLoud() = runTest {
        val rig = diamondRig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(rig.store).execute(
                SourceQueryTool.Input(
                    select = "descendants",
                    projectId = rig.pid.value,
                    root = "ghost",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("source_query"), "error should hint at source_query(select=nodes)")
    }

    @Test fun descendantsRequiresRoot() = runTest {
        val rig = diamondRig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(rig.store).execute(
                SourceQueryTool.Input(select = "descendants", projectId = rig.pid.value),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("root"), ex.message)
    }

    @Test fun rootFieldRejectedOutsideRelativesSelects() = runTest {
        val rig = diamondRig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceQueryTool(rig.store).execute(
                SourceQueryTool.Input(
                    select = "nodes",
                    projectId = rig.pid.value,
                    root = "root",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("root"), ex.message)
    }

    @Test fun descendantsCycleSafe() = runTest {
        // Pathological DAG with a cycle a→b→a. The data layer doesn't forbid
        // cycles (SourceDagValidator's concern); the traversal must still
        // terminate. `stale()` already has this contract; we mirror it here.
        val rig = rig(
            SourceNode(
                id = SourceNodeId("a"),
                kind = "test.generic",
                body = buildJsonObject { put("label", "a") },
                parents = listOf(SourceRef(SourceNodeId("b"))),
            ),
            SourceNode(
                id = SourceNodeId("b"),
                kind = "test.generic",
                body = buildJsonObject { put("label", "b") },
                parents = listOf(SourceRef(SourceNodeId("a"))),
            ),
        )
        val out = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(
                select = "descendants",
                projectId = rig.pid.value,
                root = "a",
            ),
            rig.ctx,
        ).data
        // Visits both nodes exactly once despite the cycle.
        assertEquals(2, out.total)
    }
}
