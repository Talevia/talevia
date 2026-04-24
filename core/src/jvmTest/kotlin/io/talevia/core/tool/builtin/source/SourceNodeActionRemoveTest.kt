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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `action="remove"` branch of [SourceNodeActionTool] — covers the cases the
 * pre-consolidation `RemoveSourceNodeTool` used to enforce via
 * `SourceToolsTest.removeSourceNodeRemovesAndErrorsOnMissing`, plus new
 * cases pinning the output shape (single-element `removed` list, null
 * `autoRegenHint` when no clips were staled) and cross-action payload
 * rejection introduced with `debt-source-consolidate-add-remove-fork`
 * (2026-04-24).
 */
class SourceNodeActionRemoveTest {

    private data class Rig(
        val store: FileProjectStore,
        val ctx: ToolContext,
        val pid: ProjectId,
    )

    private suspend fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p")
        store.upsert("demo", Project(id = pid, timeline = Timeline()))
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

    @Test fun removesExistingNodeAndReportsKind() = runTest {
        val rig = rig()
        rig.store.mutateSource(rig.pid) {
            it.addCharacterRef(
                SourceNodeId("character-mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val out = SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = rig.pid.value,
                action = "remove",
                nodeId = "character-mei",
            ),
            rig.ctx,
        ).data
        assertEquals("remove", out.action)
        val removed = out.removed.single()
        assertEquals("character-mei", removed.nodeId)
        assertEquals("core.consistency.character_ref", removed.removedKind)
        // No clips bound → no auto-regen hint.
        assertNull(out.autoRegenHint)
        assertTrue(rig.store.get(rig.pid)!!.source.byId.isEmpty())
    }

    @Test fun errorsWhenNodeMissing() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = rig.pid.value,
                    action = "remove",
                    nodeId = "no-such-node",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("no-such-node"), ex.message)
    }

    @Test fun rejectsMissingNodeIdField() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = rig.pid.value,
                    action = "remove",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("nodeId"), ex.message)
    }

    @Test fun rejectsAddPayloadOnRemove() = runTest {
        val rig = rig()
        rig.store.mutateSource(rig.pid) {
            it.addCharacterRef(
                SourceNodeId("character-mei"),
                CharacterRefBody(name = "Mei", visualDescription = "v"),
            )
        }
        val ex = assertFailsWith<IllegalArgumentException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = rig.pid.value,
                    action = "remove",
                    nodeId = "character-mei",
                    kind = "narrative.scene",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("add/fork"), ex.message)
        // Mutation aborted — node still there.
        assertTrue(
            rig.store.get(rig.pid)!!.source.byId.containsKey(SourceNodeId("character-mei")),
        )
    }
}
