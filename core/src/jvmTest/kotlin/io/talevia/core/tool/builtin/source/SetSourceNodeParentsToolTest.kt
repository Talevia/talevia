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

class SetSourceNodeParentsToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: SetSourceNodeParentsTool,
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
        return Rig(store, SetSourceNodeParentsTool(store), ctx, pid)
    }

    private suspend fun seedCharacter(
        store: FileProjectStore,
        pid: ProjectId,
        nodeId: String,
    ) {
        store.mutateSource(pid) {
            it.addCharacterRef(
                SourceNodeId(nodeId),
                CharacterRefBody(name = nodeId, visualDescription = "v"),
            )
        }
    }

    private suspend fun seedStyle(
        store: FileProjectStore,
        pid: ProjectId,
        nodeId: String,
    ) {
        store.mutateSource(pid) {
            it.addStyleBible(
                SourceNodeId(nodeId),
                StyleBibleBody(name = nodeId, description = "d"),
            )
        }
    }

    private suspend fun seedShot(
        store: FileProjectStore,
        pid: ProjectId,
        nodeId: String,
        parents: List<String> = emptyList(),
    ) {
        store.mutateSource(pid) { source ->
            source.addNode(
                SourceNode.create(
                    id = SourceNodeId(nodeId),
                    kind = "narrative.shot",
                    body = buildJsonObject { put("framing", JsonPrimitive("medium")) },
                    parents = parents.map { SourceRef(SourceNodeId(it)) },
                ),
            )
        }
    }

    @Test fun replacesParentsWholesale() = runTest {
        val rig = rig()
        seedCharacter(rig.store, rig.pid, "mei")
        seedStyle(rig.store, rig.pid, "style-warm")
        seedShot(rig.store, rig.pid, "shot-1", parents = listOf("mei"))

        val out = rig.tool.execute(
            SetSourceNodeParentsTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                parentIds = listOf("mei", "style-warm"),
            ),
            rig.ctx,
        ).data

        assertEquals(listOf("mei"), out.previousParentIds)
        assertEquals(listOf("mei", "style-warm"), out.newParentIds)
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!
        assertEquals(listOf("mei", "style-warm"), node.parents.map { it.nodeId.value })
    }

    @Test fun clearsParentsWithEmptyList() = runTest {
        val rig = rig()
        seedCharacter(rig.store, rig.pid, "mei")
        seedShot(rig.store, rig.pid, "shot-1", parents = listOf("mei"))

        rig.tool.execute(
            SetSourceNodeParentsTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                parentIds = emptyList(),
            ),
            rig.ctx,
        )
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!
        assertTrue(node.parents.isEmpty())
    }

    @Test fun bumpsContentHash() = runTest {
        val rig = rig()
        seedCharacter(rig.store, rig.pid, "mei")
        seedStyle(rig.store, rig.pid, "style-warm")
        seedShot(rig.store, rig.pid, "shot-1", parents = listOf("mei"))
        val hashBefore = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!.contentHash

        rig.tool.execute(
            SetSourceNodeParentsTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                parentIds = listOf("mei", "style-warm"),
            ),
            rig.ctx,
        )
        val hashAfter = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!.contentHash
        assertNotEquals(hashBefore, hashAfter)
    }

    @Test fun dedupsRepeatedIdsAndPreservesOrder() = runTest {
        val rig = rig()
        seedCharacter(rig.store, rig.pid, "mei")
        seedStyle(rig.store, rig.pid, "style-warm")
        seedShot(rig.store, rig.pid, "shot-1")

        rig.tool.execute(
            SetSourceNodeParentsTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                parentIds = listOf("mei", "style-warm", "mei"), // duplicate
            ),
            rig.ctx,
        )
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("shot-1")]!!
        assertEquals(listOf("mei", "style-warm"), node.parents.map { it.nodeId.value })
    }

    @Test fun rejectsSelfReference() = runTest {
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                SetSourceNodeParentsTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "shot-1",
                    parentIds = listOf("shot-1"),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("cycles") || ex.message!!.contains("self"))
    }

    @Test fun rejectsUnknownParentId() = runTest {
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-1")
        val ex = assertFailsWith<IllegalArgumentException> {
            rig.tool.execute(
                SetSourceNodeParentsTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "shot-1",
                    parentIds = listOf("does-not-exist"),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun rejectsTransitiveCycle() = runTest {
        // Build: shot-a (parent: shot-b), shot-b (parent: shot-c), shot-c (no parent)
        // Then try to set shot-c.parents = [shot-a] → would create cycle a→b→c→a.
        val rig = rig()
        seedShot(rig.store, rig.pid, "shot-c")
        seedShot(rig.store, rig.pid, "shot-b", parents = listOf("shot-c"))
        seedShot(rig.store, rig.pid, "shot-a", parents = listOf("shot-b"))

        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SetSourceNodeParentsTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "shot-c",
                    parentIds = listOf("shot-a"),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("cycle"))
    }

    @Test fun rejectsUnknownNode() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SetSourceNodeParentsTool.Input(
                    projectId = rig.pid.value,
                    nodeId = "not-there",
                    parentIds = emptyList(),
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun worksOnConsistencyKinds() = runTest {
        // Character_refs can't reference each other today, but the DAG allows it —
        // this tool should not discriminate by kind.
        val rig = rig()
        seedCharacter(rig.store, rig.pid, "mei")
        seedStyle(rig.store, rig.pid, "style-warm")

        rig.tool.execute(
            SetSourceNodeParentsTool.Input(
                projectId = rig.pid.value,
                nodeId = "mei",
                parentIds = listOf("style-warm"),
            ),
            rig.ctx,
        )
        val node = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("mei")]!!
        assertEquals(listOf("style-warm"), node.parents.map { it.nodeId.value })
    }

    @Test fun bumpsSourceRevision() = runTest {
        val rig = rig()
        seedCharacter(rig.store, rig.pid, "mei")
        seedShot(rig.store, rig.pid, "shot-1")
        val before = rig.store.get(rig.pid)!!.source.revision
        rig.tool.execute(
            SetSourceNodeParentsTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                parentIds = listOf("mei"),
            ),
            rig.ctx,
        )
        val after = rig.store.get(rig.pid)!!.source.revision
        assertEquals(before + 1, after)
    }

    @Test fun outputEchoesPreviousAndNew() = runTest {
        val rig = rig()
        seedCharacter(rig.store, rig.pid, "mei")
        seedStyle(rig.store, rig.pid, "style-warm")
        seedShot(rig.store, rig.pid, "shot-1", parents = listOf("mei"))

        val out = rig.tool.execute(
            SetSourceNodeParentsTool.Input(
                projectId = rig.pid.value,
                nodeId = "shot-1",
                parentIds = listOf("style-warm"),
            ),
            rig.ctx,
        ).data

        assertEquals("shot-1", out.nodeId)
        assertEquals(listOf("mei"), out.previousParentIds)
        assertEquals(listOf("style-warm"), out.newParentIds)
    }
}
