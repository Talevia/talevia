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
 * Cross-project source-node import (VISION §3.4 "可组合"). The agent should be
 * able to lift a character_ref / style_bible from one project into another
 * without retyping the body, and content-addressed dedup should make a re-import
 * a no-op.
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

    @Test fun importsLeafCharacterRefIntoEmptyTarget() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, ProjectId("from"), SourceNodeId("character-mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair", voiceId = "nova"),
        )
        val out = ImportSourceNodeTool(rig.store).execute(
            ImportSourceNodeTool.Input(
                fromProjectId = "from",
                fromNodeId = "character-mei",
                toProjectId = "to",
            ),
            rig.ctx,
        )
        assertEquals(1, out.data.nodes.size)
        val leaf = out.data.nodes.single()
        assertEquals("character-mei", leaf.originalId)
        assertEquals("character-mei", leaf.importedId)
        assertFalse(leaf.skippedDuplicate)

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
        val tool = ImportSourceNodeTool(rig.store)
        val firstHash = rig.store.get(ProjectId("from"))!!
            .source.byId[SourceNodeId("character-mei")]!!.contentHash

        tool.execute(
            ImportSourceNodeTool.Input(fromProjectId = "from", fromNodeId = "character-mei", toProjectId = "to"),
            rig.ctx,
        )
        // Second call: target already has the node with same contentHash → skip.
        val second = tool.execute(
            ImportSourceNodeTool.Input(fromProjectId = "from", fromNodeId = "character-mei", toProjectId = "to"),
            rig.ctx,
        )
        assertTrue(second.data.nodes.single().skippedDuplicate, second.outputForLlm)
        // Target ended up with exactly one node — no duplicate insertion.
        val target = rig.store.get(ProjectId("to"))!!
        assertEquals(1, target.source.nodes.size)
        assertEquals(firstHash, target.source.nodes.single().contentHash)
    }

    @Test fun parentChainIsImportedTopologically() = runTest {
        val rig = rig()
        // Style bible (parent) + character_ref that references it.
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
        val out = ImportSourceNodeTool(rig.store).execute(
            ImportSourceNodeTool.Input(fromProjectId = "from", fromNodeId = "scene-opening", toProjectId = "to"),
            rig.ctx,
        )
        // Parent first, then leaf.
        assertEquals(listOf("style-warm", "scene-opening"), out.data.nodes.map { it.originalId })
        val target = rig.store.get(ProjectId("to"))!!
        assertEquals(2, target.source.nodes.size)
        val scene = target.source.byId[SourceNodeId("scene-opening")]!!
        assertEquals(listOf(SourceNodeId("style-warm")), scene.parents.map { it.nodeId })
    }

    @Test fun parentDedupRemapsChildReferences() = runTest {
        val rig = rig()
        // Source side: parent style with id "style-warm".
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
        // Target already has the *same* style under a different id → import
        // should reuse it and remap the child's parent ref.
        rig.store.mutateSource(ProjectId("to")) { src ->
            src.addStyleBible(
                SourceNodeId("style-vibe-1"),
                StyleBibleBody(name = "Warm", description = "warm grain"),
            )
        }
        val out = ImportSourceNodeTool(rig.store).execute(
            ImportSourceNodeTool.Input(fromProjectId = "from", fromNodeId = "scene-opening", toProjectId = "to"),
            rig.ctx,
        )
        val parentReport = out.data.nodes.first { it.originalId == "style-warm" }
        assertTrue(parentReport.skippedDuplicate)
        assertEquals("style-vibe-1", parentReport.importedId)
        val target = rig.store.get(ProjectId("to"))!!
        val scene = target.source.byId[SourceNodeId("scene-opening")]!!
        // Child's parent ref points at the *target's* existing style, not the source id.
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
            ImportSourceNodeTool(rig.store).execute(
                ImportSourceNodeTool.Input(fromProjectId = "from", fromNodeId = "character-mei", toProjectId = "to"),
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
        // Pre-occupy the original id with a *different* character — forces rename.
        seedCharacter(
            rig.store, ProjectId("to"), SourceNodeId("character-mei"),
            CharacterRefBody(name = "Other", visualDescription = "different look"),
        )
        val out = ImportSourceNodeTool(rig.store).execute(
            ImportSourceNodeTool.Input(
                fromProjectId = "from",
                fromNodeId = "character-mei",
                toProjectId = "to",
                newNodeId = "character-mei-narrative",
            ),
            rig.ctx,
        )
        assertEquals("character-mei-narrative", out.data.nodes.single().importedId)
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
            ImportSourceNodeTool(rig.store).execute(
                ImportSourceNodeTool.Input(fromProjectId = "from", fromNodeId = "character-mei", toProjectId = "from"),
                rig.ctx,
            )
        }
        assertTrue("add_source_node" in ex.message!!, ex.message)
    }

    @Test fun missingSourceProjectFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ImportSourceNodeTool(rig.store).execute(
                ImportSourceNodeTool.Input(fromProjectId = "ghost", fromNodeId = "x", toProjectId = "to"),
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
            ImportSourceNodeTool(rig.store).execute(
                ImportSourceNodeTool.Input(fromProjectId = "from", fromNodeId = "character-mei", toProjectId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue("ghost" in ex.message!!)
    }

    @Test fun missingSourceNodeFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ImportSourceNodeTool(rig.store).execute(
                ImportSourceNodeTool.Input(fromProjectId = "from", fromNodeId = "ghost-node", toProjectId = "to"),
                rig.ctx,
            )
        }
        assertTrue("ghost-node" in ex.message!!)
    }
}
