package io.talevia.core.tool.builtin.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
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
        val store: SqlDelightProjectStore,
        val ctx: ToolContext,
        val pid: ProjectId,
    )

    private suspend fun rig(vararg nodes: SourceNode): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
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
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            out.rows,
        )
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
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            out.rows,
        )
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
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            out.rows,
        )
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
        val withoutRows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            withoutBody.rows,
        )
        assertNull(withoutRows.first().body)

        val withBody = SourceQueryTool(rig.store).execute(
            SourceQueryTool.Input(select = "nodes", projectId = rig.pid.value, includeBody = true),
            rig.ctx,
        ).data
        val withRows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            withBody.rows,
        )
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

        val r1 = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            page1.rows,
        )
        val r2 = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            page2.rows,
        )
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
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.DagSummaryRow.serializer()),
            out.rows,
        )
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
        val row = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.DagSummaryRow.serializer()),
            out.rows,
        ).first()
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
}
