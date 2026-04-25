package io.talevia.core.tool.builtin.source

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
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
import io.talevia.core.tool.builtin.source.query.NodeDetailRow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Cycle 137: `describe_source_node` was folded into
 * `source_query(select=node_detail)`. This test continues to cover the
 * single-node deep-zoom contract — typed body, parents-with-kinds,
 * direct DAG children, bound clips with `directly` flag, humanised
 * summary — but exercises it through the unified query dispatcher.
 */
class DescribeSourceNodeToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: SourceQueryTool,
        val ctx: ToolContext,
        val pid: ProjectId,
    )

    private suspend fun rig(project: Project): Rig {
        val store = ProjectStoreTestKit.create()
        store.upsert("demo", project)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, SourceQueryTool(store), ctx, project.id)
    }

    private fun nodeDetailInput(projectId: String, nodeId: String) = SourceQueryTool.Input(
        select = SourceQueryTool.SELECT_NODE_DETAIL,
        projectId = projectId,
        id = nodeId,
    )

    private fun decodeRow(out: SourceQueryTool.Output): NodeDetailRow {
        assertEquals(SourceQueryTool.SELECT_NODE_DETAIL, out.select)
        assertEquals(1, out.total)
        assertEquals(1, out.returned)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(NodeDetailRow.serializer()),
            out.rows,
        )
        return rows.single()
    }

    @Test fun describesCharacterRefWithResolvedParents() = runTest {
        val pid = ProjectId("p")
        val store = ProjectStoreTestKit.create()
        store.upsert("demo", Project(id = pid, timeline = Timeline()))
        store.mutateSource(pid) { source ->
            val withStyle = source.addStyleBible(
                SourceNodeId("style-warm"),
                StyleBibleBody(name = "warm", description = "cozy"),
            )
            withStyle.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            ).let { with2 ->
                with2.copy(
                    nodes = with2.nodes.map { node ->
                        if (node.id == SourceNodeId("mei")) {
                            node.copy(
                                parents = listOf(SourceRef(SourceNodeId("style-warm"))),
                            )
                        } else {
                            node
                        }
                    },
                )
            }
        }

        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        val out = SourceQueryTool(store).execute(
            nodeDetailInput(projectId = pid.value, nodeId = "mei"),
            ctx,
        )
        val row = decodeRow(out.data)
        assertEquals("mei", row.nodeId)
        assertEquals("core.consistency.character_ref", row.kind)
        val parents = row.parentRefs
        assertEquals(1, parents.size)
        assertEquals("style-warm", parents.single().nodeId)
        assertEquals("core.consistency.style_bible", parents.single().kind)
        assertTrue(row.summary.contains("Mei"))
    }

    @Test fun describesStyleBibleWithDirectChildren() = runTest {
        val pid = ProjectId("p")
        val store = ProjectStoreTestKit.create()
        store.upsert("demo", Project(id = pid, timeline = Timeline()))
        store.mutateSource(pid) { source ->
            source.addStyleBible(
                SourceNodeId("style-warm"),
                StyleBibleBody(name = "warm", description = "cozy"),
            )
        }
        store.mutateSource(pid) { source ->
            source.addNode(
                SourceNode.create(
                    id = SourceNodeId("mei"),
                    kind = "core.consistency.character_ref",
                    body = buildJsonObject {
                        put("name", JsonPrimitive("Mei"))
                        put("visualDescription", JsonPrimitive("teal hair"))
                    },
                    parents = listOf(SourceRef(SourceNodeId("style-warm"))),
                ),
            )
        }
        store.mutateSource(pid) { source ->
            source.addNode(
                SourceNode.create(
                    id = SourceNodeId("kai"),
                    kind = "core.consistency.character_ref",
                    body = buildJsonObject {
                        put("name", JsonPrimitive("Kai"))
                        put("visualDescription", JsonPrimitive("silver hair"))
                    },
                    parents = listOf(SourceRef(SourceNodeId("style-warm"))),
                ),
            )
        }

        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        val out = SourceQueryTool(store).execute(
            nodeDetailInput(projectId = pid.value, nodeId = "style-warm"),
            ctx,
        )
        val row = decodeRow(out.data)
        val kids = row.children.map { it.nodeId }.toSet()
        assertEquals(setOf("mei", "kai"), kids)
        assertTrue(row.children.all { it.kind == "core.consistency.character_ref" })
    }

    @Test fun describesBoundClipsDirectAndTransitive() = runTest {
        val pid = ProjectId("p")
        val store = ProjectStoreTestKit.create()
        val videoTrack = Track.Video(
            id = TrackId("vt"),
            clips = listOf(
                Clip.Video(
                    id = ClipId("c1"),
                    timeRange = TimeRange(0.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("asset-c1"),
                    sourceBinding = setOf(SourceNodeId("mei")),
                ),
                Clip.Video(
                    id = ClipId("c2"),
                    timeRange = TimeRange(5.seconds, 5.seconds),
                    sourceRange = TimeRange(0.seconds, 5.seconds),
                    assetId = AssetId("asset-c2"),
                    sourceBinding = setOf(SourceNodeId("style-warm")),
                ),
            ),
        )
        store.upsert(
            "demo",
            Project(
                id = pid,
                timeline = Timeline(tracks = listOf(videoTrack), duration = 10.seconds),
            ),
        )
        store.mutateSource(pid) { source ->
            source.addStyleBible(
                SourceNodeId("style-warm"),
                StyleBibleBody(name = "warm", description = "cozy"),
            )
        }
        store.mutateSource(pid) { source ->
            source.addNode(
                SourceNode.create(
                    id = SourceNodeId("mei"),
                    kind = "core.consistency.character_ref",
                    body = buildJsonObject {
                        put("name", JsonPrimitive("Mei"))
                        put("visualDescription", JsonPrimitive("teal hair"))
                    },
                    parents = listOf(SourceRef(SourceNodeId("style-warm"))),
                ),
            )
        }

        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        val tool = SourceQueryTool(store)

        val mei = decodeRow(
            tool.execute(nodeDetailInput(projectId = pid.value, nodeId = "mei"), ctx).data,
        )
        assertEquals(1, mei.boundClips.size)
        assertEquals("c1", mei.boundClips.single().clipId)
        assertTrue(mei.boundClips.single().directly)

        val style = decodeRow(
            tool.execute(nodeDetailInput(projectId = pid.value, nodeId = "style-warm"), ctx).data,
        )
        assertEquals(2, style.boundClips.size)
        val byId = style.boundClips.associateBy { it.clipId }
        assertTrue(byId["c2"]!!.directly)
        assertTrue(!byId["c1"]!!.directly)
        assertEquals(listOf("mei"), byId["c1"]!!.boundViaNodeIds)
    }

    @Test fun rejectsMissingProject() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                nodeDetailInput(projectId = "nope", nodeId = "x"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun rejectsMissingNode() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                nodeDetailInput(projectId = rig.pid.value, nodeId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun rejectsMissingId() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SourceQueryTool.Input(
                    select = SourceQueryTool.SELECT_NODE_DETAIL,
                    projectId = rig.pid.value,
                ),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("requires id"), ex.message)
    }

    @Test fun emptyNodeHasEmptyParentsAndChildren() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        rig.store.mutateSource(rig.pid) { source ->
            source.addCharacterRef(
                SourceNodeId("lonely"),
                CharacterRefBody(name = "L", visualDescription = "v"),
            )
        }
        val row = decodeRow(
            rig.tool.execute(
                nodeDetailInput(projectId = rig.pid.value, nodeId = "lonely"),
                rig.ctx,
            ).data,
        )
        assertTrue(row.parentRefs.isEmpty())
        assertTrue(row.children.isEmpty())
        assertTrue(row.boundClips.isEmpty())
    }

    @Test fun parentMarkedMissingWhenAncestorAbsent() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        rig.store.mutateSource(rig.pid) { source ->
            source.copy(
                revision = source.revision + 1,
                nodes = source.nodes + SourceNode.create(
                    id = SourceNodeId("orphan"),
                    kind = "narrative.shot",
                    body = buildJsonObject { put("framing", JsonPrimitive("medium")) },
                    parents = listOf(SourceRef(SourceNodeId("ghost-parent"))),
                ),
            )
        }
        val row = decodeRow(
            rig.tool.execute(
                nodeDetailInput(projectId = rig.pid.value, nodeId = "orphan"),
                rig.ctx,
            ).data,
        )
        val parent = row.parentRefs.single()
        assertEquals("ghost-parent", parent.nodeId)
        assertEquals("(missing)", parent.kind)
    }

    @Test fun outputContentHashMatchesStoredNode() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        rig.store.mutateSource(rig.pid) { source ->
            source.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            )
        }
        val row = decodeRow(
            rig.tool.execute(
                nodeDetailInput(projectId = rig.pid.value, nodeId = "mei"),
                rig.ctx,
            ).data,
        )
        val stored = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("mei")]!!
        assertEquals(stored.contentHash, row.contentHash)
        assertEquals(stored.revision, row.revision)
    }

    @Test fun summaryUsesHumanisedCharacterRef() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        rig.store.mutateSource(rig.pid) { source ->
            source.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair, warrior garb"),
            )
        }
        val row = decodeRow(
            rig.tool.execute(
                nodeDetailInput(projectId = rig.pid.value, nodeId = "mei"),
                rig.ctx,
            ).data,
        )
        assertTrue(row.summary.contains("Mei"))
        assertTrue(row.summary.contains("teal hair"))
    }
}
