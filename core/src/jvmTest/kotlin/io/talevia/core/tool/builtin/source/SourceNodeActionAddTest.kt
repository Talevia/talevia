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

/**
 * `action="add"` branch of [SourceNodeActionTool] — reshaped from the
 * pre-consolidation `AddSourceNodeToolTest` (2026-04-24,
 * `debt-source-consolidate-add-remove-fork`). Every semantic case from the
 * original test is preserved plus new ones covering cross-action payload
 * rejection and missing-required-field dispatches.
 */
class SourceNodeActionAddTest {

    private data class Rig(
        val store: FileProjectStore,
        val ctx: ToolContext,
    )

    private suspend fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
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

        val out = SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = "p",
                action = "add",
                nodeId = "scene-1",
                kind = "narrative.scene",
                body = body,
            ),
            rig.ctx,
        ).data

        assertEquals("add", out.action)
        val added = out.added.single()
        assertEquals("scene-1", added.nodeId)
        assertEquals("narrative.scene", added.kind)
        assertTrue(added.contentHash.isNotBlank())

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

        val out = SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = "p",
                action = "add",
                nodeId = "scene-1",
                kind = "narrative.scene",
                body = JsonObject(emptyMap()),
                parentIds = listOf("mei"),
            ),
            rig.ctx,
        ).data

        assertEquals(listOf("mei"), out.added.single().parentIds)
        val scene = rig.store.get(ProjectId("p"))!!.source.byId[SourceNodeId("scene-1")]!!
        assertEquals(listOf(SourceNodeId("mei")), scene.parents.map { it.nodeId })
    }

    @Test fun rejectsDanglingParent() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = "p",
                    action = "add",
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
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = "p",
                    action = "add",
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
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = "p",
                    action = "add",
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
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = "p",
                    action = "add",
                    nodeId = "",
                    kind = "narrative.scene",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("nodeId"), ex.message)
    }

    @Test fun rejectsMissingKindField() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = "p",
                    action = "add",
                    nodeId = "x",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("kind"), ex.message)
    }

    @Test fun rejectsMissingNodeIdField() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = "p",
                    action = "add",
                    kind = "narrative.scene",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("nodeId"), ex.message)
    }

    @Test fun rejectsForkPayloadOnAdd() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = "p",
                    action = "add",
                    nodeId = "scene-1",
                    kind = "narrative.scene",
                    sourceNodeId = "mei",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("sourceNodeId"), ex.message)
    }

    @Test fun defaultsToEmptyBodyAndNoParents() = runTest {
        val rig = rig()
        val out = SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = "p",
                action = "add",
                nodeId = "marker",
                kind = "custom.marker",
            ),
            rig.ctx,
        ).data
        assertTrue(out.added.single().parentIds.isEmpty())

        val node = rig.store.get(ProjectId("p"))!!.source.byId[SourceNodeId("marker")]!!
        assertEquals(JsonObject(emptyMap()), node.body)
        assertTrue(node.parents.isEmpty())
    }

    @Test fun contentHashStableAcrossReads() = runTest {
        val rig = rig()
        val out = SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = "p",
                action = "add",
                nodeId = "stable",
                kind = "custom.marker",
                body = buildJsonObject { put("x", JsonPrimitive(1)) },
            ),
            rig.ctx,
        ).data

        val stored = rig.store.get(ProjectId("p"))!!.source.byId[SourceNodeId("stable")]!!
        assertEquals(out.added.single().contentHash, stored.contentHash)
    }

    @Test fun rejectsUnknownAction() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = "p",
                    action = "mutate",
                    nodeId = "x",
                    kind = "k",
                ),
                rig.ctx,
            )
        }
        assertTrue("unknown action" in ex.message!!, ex.message)
    }
}
