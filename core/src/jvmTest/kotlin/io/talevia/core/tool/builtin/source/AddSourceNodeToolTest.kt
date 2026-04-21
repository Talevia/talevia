package io.talevia.core.tool.builtin.source

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AddSourceNodeToolTest {

    private data class Rig(
        val store: SqlDelightProjectStore,
        val ctx: ToolContext,
    )

    private suspend fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        store.upsert("demo", Project(id = ProjectId("p"), timeline = Timeline()))
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx)
    }

    @Test fun createsNodeWithSuppliedKindAndBody() = runTest {
        val rig = rig()
        val body = buildJsonObject {
            put("title", JsonPrimitive("opening"))
            put("action", JsonPrimitive("protagonist enters a neon-lit alley"))
        }

        val out = AddSourceNodeTool(rig.store).execute(
            AddSourceNodeTool.Input(
                projectId = "p",
                nodeId = "scene-1",
                kind = "narrative.scene",
                body = body,
            ),
            rig.ctx,
        ).data

        assertEquals("scene-1", out.nodeId)
        assertEquals("narrative.scene", out.kind)
        assertTrue(out.contentHash.isNotBlank())

        val node = rig.store.get(ProjectId("p"))!!.source.byId[SourceNodeId("scene-1")]
        assertNotNull(node)
        assertEquals("narrative.scene", node.kind)
        assertEquals(body, node.body)
    }

    @Test fun parentsWiredAndValidated() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal hair"))
        }

        val out = AddSourceNodeTool(rig.store).execute(
            AddSourceNodeTool.Input(
                projectId = "p",
                nodeId = "scene-1",
                kind = "narrative.scene",
                body = JsonObject(emptyMap()),
                parentIds = listOf("mei"),
            ),
            rig.ctx,
        ).data

        assertEquals(listOf("mei"), out.parentIds)
        val scene = rig.store.get(ProjectId("p"))!!.source.byId[SourceNodeId("scene-1")]!!
        assertEquals(listOf(SourceNodeId("mei")), scene.parents.map { it.nodeId })
    }

    @Test fun rejectsDanglingParent() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            AddSourceNodeTool(rig.store).execute(
                AddSourceNodeTool.Input(
                    projectId = "p",
                    nodeId = "scene-1",
                    kind = "narrative.scene",
                    parentIds = listOf("ghost"),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        // Project unchanged — no partial node snuck in.
        assertTrue(rig.store.get(ProjectId("p"))!!.source.nodes.isEmpty())
    }

    @Test fun rejectsDuplicateNodeId() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) {
            it.addCharacterRef(SourceNodeId("mei"), CharacterRefBody(name = "Mei", visualDescription = "teal"))
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            AddSourceNodeTool(rig.store).execute(
                AddSourceNodeTool.Input(
                    projectId = "p",
                    nodeId = "mei",
                    kind = "narrative.scene",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("mei"), ex.message)
        // Existing node unchanged.
        val kept = rig.store.get(ProjectId("p"))!!.source.byId[SourceNodeId("mei")]!!
        assertEquals("core.consistency.character_ref", kept.kind)
    }

    @Test fun rejectsBlankKind() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            AddSourceNodeTool(rig.store).execute(
                AddSourceNodeTool.Input(
                    projectId = "p",
                    nodeId = "x",
                    kind = "  ",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("kind"), ex.message)
    }

    @Test fun rejectsBlankNodeId() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            AddSourceNodeTool(rig.store).execute(
                AddSourceNodeTool.Input(
                    projectId = "p",
                    nodeId = "",
                    kind = "narrative.scene",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("nodeId"), ex.message)
    }

    @Test fun defaultsToEmptyBodyAndNoParents() = runTest {
        val rig = rig()
        val out = AddSourceNodeTool(rig.store).execute(
            AddSourceNodeTool.Input(
                projectId = "p",
                nodeId = "marker",
                kind = "custom.marker",
            ),
            rig.ctx,
        ).data
        assertTrue(out.parentIds.isEmpty())

        val node = rig.store.get(ProjectId("p"))!!.source.byId[SourceNodeId("marker")]!!
        assertEquals(JsonObject(emptyMap()), node.body)
        assertTrue(node.parents.isEmpty())
    }

    @Test fun contentHashStableAcrossReads() = runTest {
        val rig = rig()
        val out = AddSourceNodeTool(rig.store).execute(
            AddSourceNodeTool.Input(
                projectId = "p",
                nodeId = "stable",
                kind = "custom.marker",
                body = buildJsonObject { put("x", JsonPrimitive(1)) },
            ),
            rig.ctx,
        ).data

        val stored = rig.store.get(ProjectId("p"))!!.source.byId[SourceNodeId("stable")]!!
        assertEquals(out.contentHash, stored.contentHash)
    }
}
