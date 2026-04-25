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
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-project source-node import (VISION §3.4 "可组合") via
 * `source_node_action(action="import", …)` — the agent should be
 * able to lift a character_ref / style_bible from one project into
 * another without retyping the body, and content-addressed dedup
 * should make a re-import a no-op.
 *
 * Cycle 136 folded the standalone `ImportSourceNodeTool` into the
 * action dispatcher; tests now exercise the dispatcher's
 * `action="import"` branch.
 */
class ImportSourceNodeToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val ctx: ToolContext,
    )

    private suspend fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
        store.upsert("from", Project(id = ProjectId("from"), timeline = Timeline()))
        store.upsert("to", Project(id = ProjectId("to"), timeline = Timeline()))
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

    private suspend fun seedCharacter(
        store: FileProjectStore,
        pid: ProjectId,
        nodeId: SourceNodeId,
        body: CharacterRefBody,
    ) {
        store.mutateSource(pid) { it.addCharacterRef(nodeId, body) }
    }

    private fun importInput(
        toProjectId: String,
        fromProjectId: String? = null,
        fromNodeId: String? = null,
        envelope: String? = null,
        newNodeId: String? = null,
    ) = SourceNodeActionTool.Input(
        projectId = toProjectId,
        action = "import",
        fromProjectId = fromProjectId,
        fromNodeId = fromNodeId,
        envelope = envelope,
        newNodeId = newNodeId,
    )

    @Test fun importsLeafCharacterRefIntoEmptyTarget() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, ProjectId("from"), SourceNodeId("character-mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair", voiceId = "nova"),
        )
        val out = SourceNodeActionTool(rig.store).execute(
            importInput(toProjectId = "to", fromProjectId = "from", fromNodeId = "character-mei"),
            rig.ctx,
        )
        assertEquals(1, out.data.imported.size)
        val leaf = out.data.imported.single()
        assertEquals("character-mei", leaf.originalId)
        assertEquals("character-mei", leaf.importedId)
        assertFalse(leaf.skippedDuplicate)
        assertEquals("from", out.data.importFromProjectId)

        val target = rig.store.get(ProjectId("to"))!!
        val imported = target.source.byId[SourceNodeId("character-mei")]
        assertEquals("Mei", imported?.asCharacterRef()?.name)
        assertEquals("nova", imported?.asCharacterRef()?.voiceId)
    }

    @Test fun reImportingSameNodeIsIdempotent() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, ProjectId("from"), SourceNodeId("character-mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        val tool = SourceNodeActionTool(rig.store)
        val firstHash = rig.store.get(ProjectId("from"))!!
            .source.byId[SourceNodeId("character-mei")]!!.contentHash

        tool.execute(
            importInput(toProjectId = "to", fromProjectId = "from", fromNodeId = "character-mei"),
            rig.ctx,
        )
        val second = tool.execute(
            importInput(toProjectId = "to", fromProjectId = "from", fromNodeId = "character-mei"),
            rig.ctx,
        )
        assertTrue(second.data.imported.single().skippedDuplicate, second.outputForLlm)
        val target = rig.store.get(ProjectId("to"))!!
        assertEquals(1, target.source.nodes.size)
        assertEquals(firstHash, target.source.nodes.single().contentHash)
    }

    @Test fun parentChainIsImportedTopologically() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("from")) { src ->
            src.addStyleBible(
                SourceNodeId("style-warm"),
                StyleBibleBody(name = "Warm", description = "warm grain"),
            ).addNode(
                SourceNode.create(
                    id = SourceNodeId("scene-opening"),
                    kind = "narrative.scene",
                    body = buildJsonObject { put("title", JsonPrimitive("Opening")) },
                    parents = listOf(SourceRef(SourceNodeId("style-warm"))),
                ),
            )
        }
        val out = SourceNodeActionTool(rig.store).execute(
            importInput(toProjectId = "to", fromProjectId = "from", fromNodeId = "scene-opening"),
            rig.ctx,
        )
        assertEquals(listOf("style-warm", "scene-opening"), out.data.imported.map { it.originalId })
        val target = rig.store.get(ProjectId("to"))!!
        assertEquals(2, target.source.nodes.size)
        val scene = target.source.byId[SourceNodeId("scene-opening")]!!
        assertEquals(listOf(SourceNodeId("style-warm")), scene.parents.map { it.nodeId })
    }

    @Test fun parentDedupRemapsChildReferences() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("from")) { src ->
            src.addStyleBible(
                SourceNodeId("style-warm"),
                StyleBibleBody(name = "Warm", description = "warm grain"),
            ).addNode(
                SourceNode.create(
                    id = SourceNodeId("scene-opening"),
                    kind = "narrative.scene",
                    body = buildJsonObject { put("title", JsonPrimitive("Opening")) },
                    parents = listOf(SourceRef(SourceNodeId("style-warm"))),
                ),
            )
        }
        rig.store.mutateSource(ProjectId("to")) { src ->
            src.addStyleBible(
                SourceNodeId("style-vibe-1"),
                StyleBibleBody(name = "Warm", description = "warm grain"),
            )
        }
        val out = SourceNodeActionTool(rig.store).execute(
            importInput(toProjectId = "to", fromProjectId = "from", fromNodeId = "scene-opening"),
            rig.ctx,
        )
        val parentReport = out.data.imported.first { it.originalId == "style-warm" }
        assertTrue(parentReport.skippedDuplicate)
        assertEquals("style-vibe-1", parentReport.importedId)
        val target = rig.store.get(ProjectId("to"))!!
        val scene = target.source.byId[SourceNodeId("scene-opening")]!!
        assertEquals(listOf(SourceNodeId("style-vibe-1")), scene.parents.map { it.nodeId })
    }

    @Test fun differentContentSameIdFailsLoudly() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, ProjectId("from"), SourceNodeId("character-mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        seedCharacter(
            rig.store, ProjectId("to"), SourceNodeId("character-mei"),
            CharacterRefBody(name = "Mei", visualDescription = "BLONDE hair"),
        )
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                importInput(toProjectId = "to", fromProjectId = "from", fromNodeId = "character-mei"),
                rig.ctx,
            )
        }
        assertTrue("character-mei" in ex.message!!)
        assertTrue("newNodeId" in ex.message!!)
    }

    @Test fun newNodeIdRenamesTheLeaf() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, ProjectId("from"), SourceNodeId("character-mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        seedCharacter(
            rig.store, ProjectId("to"), SourceNodeId("character-mei"),
            CharacterRefBody(name = "Other", visualDescription = "different look"),
        )
        val out = SourceNodeActionTool(rig.store).execute(
            importInput(
                toProjectId = "to",
                fromProjectId = "from",
                fromNodeId = "character-mei",
                newNodeId = "character-mei-narrative",
            ),
            rig.ctx,
        )
        assertEquals("character-mei-narrative", out.data.imported.single().importedId)
        val target = rig.store.get(ProjectId("to"))!!
        assertTrue(SourceNodeId("character-mei-narrative") in target.source.byId)
    }

    @Test fun selfImportFailsLoudly() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, ProjectId("from"), SourceNodeId("character-mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            SourceNodeActionTool(rig.store).execute(
                importInput(toProjectId = "from", fromProjectId = "from", fromNodeId = "character-mei"),
                rig.ctx,
            )
        }
        assertTrue("source_node_action(action=add)" in ex.message!!, ex.message)
    }

    @Test fun missingSourceProjectFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                importInput(toProjectId = "to", fromProjectId = "ghost", fromNodeId = "x"),
                rig.ctx,
            )
        }
        assertTrue("ghost" in ex.message!!)
    }

    @Test fun missingTargetProjectFailsLoudly() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, ProjectId("from"), SourceNodeId("character-mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                importInput(toProjectId = "ghost", fromProjectId = "from", fromNodeId = "character-mei"),
                rig.ctx,
            )
        }
        assertTrue("ghost" in ex.message!!)
    }

    @Test fun missingSourceNodeFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                importInput(toProjectId = "to", fromProjectId = "from", fromNodeId = "ghost-node"),
                rig.ctx,
            )
        }
        assertTrue("ghost-node" in ex.message!!)
    }

    @Test fun rejectsBothShapesSetAtOnce() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            SourceNodeActionTool(rig.store).execute(
                importInput(
                    toProjectId = "to",
                    fromProjectId = "from",
                    fromNodeId = "character-mei",
                    envelope = "{}",
                ),
                rig.ctx,
            )
        }
        assertTrue("exactly one input shape" in ex.message!!, ex.message)
    }

    @Test fun rejectsNeitherShapeSet() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            SourceNodeActionTool(rig.store).execute(
                importInput(toProjectId = "to"),
                rig.ctx,
            )
        }
        assertTrue("exactly one input shape" in ex.message!!, ex.message)
    }
}
