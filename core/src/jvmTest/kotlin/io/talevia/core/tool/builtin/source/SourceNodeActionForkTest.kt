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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `action="fork"` branch of [SourceNodeActionTool] — reshaped from the
 * pre-consolidation `ForkSourceNodeToolTest` (2026-04-24,
 * `debt-source-consolidate-add-remove-fork`). Every semantic case preserved
 * plus a new one covering cross-action payload rejection.
 */
class SourceNodeActionForkTest {

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

    private suspend fun seedCharacter(
        store: FileProjectStore,
        pid: ProjectId,
        nodeId: SourceNodeId,
        body: CharacterRefBody,
    ) {
        store.mutateSource(pid) { it.addCharacterRef(nodeId, body) }
    }

    @Test fun forksCharacterRefUnderNewId() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, rig.pid, SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair", voiceId = "nova"),
        )

        val out = SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = rig.pid.value,
                action = "fork",
                sourceNodeId = "mei",
                newNodeId = "mei-alt",
            ),
            rig.ctx,
        ).data

        val forked = out.forked.single()
        assertEquals("mei", forked.sourceNodeId)
        assertEquals("mei-alt", forked.forkedNodeId)

        val project = rig.store.get(rig.pid)!!
        val original = project.source.byId[SourceNodeId("mei")]!!
        val fork = project.source.byId[SourceNodeId("mei-alt")]!!

        // Body copied verbatim — hashes match.
        assertEquals(original.kind, fork.kind)
        assertEquals(original.contentHash, fork.contentHash)
        assertEquals("Mei", fork.asCharacterRef()?.name)
        assertEquals("nova", fork.asCharacterRef()?.voiceId)
        // Distinct ids.
        assertNotEquals(original.id, fork.id)
    }

    @Test fun autoGeneratesIdWhenNewNodeIdBlank() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, rig.pid, SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        val out = SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = rig.pid.value,
                action = "fork",
                sourceNodeId = "mei",
            ),
            rig.ctx,
        ).data
        val forkedId = out.forked.single().forkedNodeId
        assertNotEquals("mei", forkedId)
        assertTrue(forkedId.isNotBlank())
        val project = rig.store.get(rig.pid)!!
        assertEquals(2, project.source.nodes.size)
    }

    @Test fun treatsBlankNewNodeIdAsUnset() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, rig.pid, SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        val out = SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = rig.pid.value,
                action = "fork",
                sourceNodeId = "mei",
                newNodeId = "   ",
            ),
            rig.ctx,
        ).data
        val forkedId = out.forked.single().forkedNodeId
        assertNotEquals("mei", forkedId)
        assertTrue(forkedId.isNotBlank())
    }

    @Test fun preservesParentRefsOnFork() = runTest {
        val rig = rig()
        // style bible -> scene-shot (a fictional kind) with parents=[style]
        rig.store.mutateSource(rig.pid) { source ->
            val withStyle = source.addStyleBible(
                SourceNodeId("style-warm"),
                StyleBibleBody(name = "warm", description = "cozy"),
            )
            withStyle.addNode(
                SourceNode.create(
                    id = SourceNodeId("shot-1"),
                    kind = "narrative.shot",
                    body = buildJsonObject { put("framing", JsonPrimitive("medium")) },
                    parents = listOf(SourceRef(SourceNodeId("style-warm"))),
                ),
            )
        }

        SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = rig.pid.value,
                action = "fork",
                sourceNodeId = "shot-1",
                newNodeId = "shot-1-alt",
            ),
            rig.ctx,
        )

        val project = rig.store.get(rig.pid)!!
        val original = project.source.byId[SourceNodeId("shot-1")]!!
        val fork = project.source.byId[SourceNodeId("shot-1-alt")]!!
        assertEquals(original.parents, fork.parents)
        // Parents referenced, NOT cloned — style-warm is still a single node.
        assertEquals(1, project.source.nodes.count { it.kind == "core.consistency.style_bible" })
    }

    @Test fun rejectsNewIdEqualToSource() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, rig.pid, SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = rig.pid.value,
                    action = "fork",
                    sourceNodeId = "mei",
                    newNodeId = "mei",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("distinct"))
    }

    @Test fun rejectsCollidingNewId() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, rig.pid, SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        seedCharacter(
            rig.store, rig.pid, SourceNodeId("kai"),
            CharacterRefBody(name = "Kai", visualDescription = "silver hair"),
        )
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = rig.pid.value,
                    action = "fork",
                    sourceNodeId = "mei",
                    newNodeId = "kai",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test fun rejectsUnknownSource() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = rig.pid.value,
                    action = "fork",
                    sourceNodeId = "not-there",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun rejectsMissingProject() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = "nope",
                    action = "fork",
                    sourceNodeId = "mei",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun forkedNodeStartsAtRevisionOne() = runTest {
        val rig = rig()
        // seed, then update several times to bump revision
        seedCharacter(
            rig.store, rig.pid, SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        rig.store.mutateSource(rig.pid) { source ->
            source.addCharacterRef(SourceNodeId("kai"), CharacterRefBody(name = "Kai", visualDescription = "v"))
        }

        val out = SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = rig.pid.value,
                action = "fork",
                sourceNodeId = "mei",
                newNodeId = "mei-fork",
            ),
            rig.ctx,
        ).data

        val fork = rig.store.get(rig.pid)!!.source.byId[SourceNodeId(out.forked.single().forkedNodeId)]!!
        // addNode → bumpedForWrite increments to 1.
        assertEquals(1L, fork.revision)
    }

    @Test fun sourceRevisionBumpsOnFork() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, rig.pid, SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        val before = rig.store.get(rig.pid)!!.source.revision
        SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = rig.pid.value,
                action = "fork",
                sourceNodeId = "mei",
                newNodeId = "mei-fork",
            ),
            rig.ctx,
        )
        val after = rig.store.get(rig.pid)!!.source.revision
        assertEquals(before + 1, after)
    }

    @Test fun outputContainsContentHashAndKind() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, rig.pid, SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        val out = SourceNodeActionTool(rig.store).execute(
            SourceNodeActionTool.Input(
                projectId = rig.pid.value,
                action = "fork",
                sourceNodeId = "mei",
                newNodeId = "mei-fork",
            ),
            rig.ctx,
        ).data
        val forked = out.forked.single()
        assertEquals("core.consistency.character_ref", forked.kind)
        assertTrue(forked.contentHash.isNotBlank())
    }

    @Test fun rejectsAddPayloadOnFork() = runTest {
        val rig = rig()
        seedCharacter(
            rig.store, rig.pid, SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            SourceNodeActionTool(rig.store).execute(
                SourceNodeActionTool.Input(
                    projectId = rig.pid.value,
                    action = "fork",
                    sourceNodeId = "mei",
                    nodeId = "mei-fork",
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("add/remove"), ex.message)
    }
}
