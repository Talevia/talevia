package io.talevia.core.tool.builtin.source

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.ClipId
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DescribeSourceNodeToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: DescribeSourceNodeTool,
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
        return Rig(store, DescribeSourceNodeTool(store), ctx, project.id)
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
                // Give mei a parent ref onto style-warm.
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
        val tool = DescribeSourceNodeTool(store)
        val out = tool.execute(
            DescribeSourceNodeTool.Input(projectId = pid.value, nodeId = "mei"),
            ctx,
        ).data

        assertEquals("mei", out.node.nodeId)
        assertEquals("core.consistency.character_ref", out.node.kind)
        val parents = out.node.parentRefs
        assertEquals(1, parents.size)
        assertEquals("style-warm", parents.single().nodeId)
        assertEquals("core.consistency.style_bible", parents.single().kind)
        assertTrue(out.summary.contains("Mei"))
    }

    @Test fun describesStyleBibleWithDirectChildren() = runTest {
        // Build: style-warm (parent of both mei and kai)
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
        val out = DescribeSourceNodeTool(store).execute(
            DescribeSourceNodeTool.Input(projectId = pid.value, nodeId = "style-warm"),
            ctx,
        ).data
        val kids = out.children.map { it.nodeId }.toSet()
        assertEquals(setOf("mei", "kai"), kids)
        assertTrue(out.children.all { it.kind == "core.consistency.character_ref" })
    }

    @Test fun describesBoundClipsDirectAndTransitive() = runTest {
        // Graph: style → mei → (nothing). Clip c1 binds mei directly; clip c2 binds
        // style directly. Call describe_source_node on mei → c1 is direct,
        // nothing transitive from above. Call on style → c2 direct + c1 transitive via mei.
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
        val tool = DescribeSourceNodeTool(store)

        val meiOut = tool.execute(
            DescribeSourceNodeTool.Input(projectId = pid.value, nodeId = "mei"),
            ctx,
        ).data
        assertEquals(1, meiOut.boundClips.size)
        assertEquals("c1", meiOut.boundClips.single().clipId)
        assertTrue(meiOut.boundClips.single().directly)

        val styleOut = tool.execute(
            DescribeSourceNodeTool.Input(projectId = pid.value, nodeId = "style-warm"),
            ctx,
        ).data
        assertEquals(2, styleOut.boundClips.size)
        val byId = styleOut.boundClips.associateBy { it.clipId }
        assertTrue(byId["c2"]!!.directly)
        assertTrue(!byId["c1"]!!.directly) // bound via mei
        assertEquals(listOf("mei"), byId["c1"]!!.boundViaNodeIds)
    }

    @Test fun rejectsMissingProject() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                DescribeSourceNodeTool.Input(projectId = "nope", nodeId = "x"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun rejectsMissingNode() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                DescribeSourceNodeTool.Input(projectId = rig.pid.value, nodeId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("not found"))
    }

    @Test fun emptyNodeHasEmptyParentsAndChildren() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        rig.store.mutateSource(rig.pid) { source ->
            source.addCharacterRef(
                SourceNodeId("lonely"),
                CharacterRefBody(name = "L", visualDescription = "v"),
            )
        }
        val out = rig.tool.execute(
            DescribeSourceNodeTool.Input(projectId = rig.pid.value, nodeId = "lonely"),
            rig.ctx,
        ).data
        assertTrue(out.node.parentRefs.isEmpty())
        assertTrue(out.children.isEmpty())
        assertTrue(out.boundClips.isEmpty())
    }

    @Test fun parentMarkedMissingWhenAncestorAbsent() = runTest {
        // Craft a node whose parents include a non-existent id, simulating the
        // "dangling ref" edge case (import partial / remove_source_node bypassed).
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
        val out = rig.tool.execute(
            DescribeSourceNodeTool.Input(projectId = rig.pid.value, nodeId = "orphan"),
            rig.ctx,
        ).data
        val parent = out.node.parentRefs.single()
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
        val out = rig.tool.execute(
            DescribeSourceNodeTool.Input(projectId = rig.pid.value, nodeId = "mei"),
            rig.ctx,
        ).data
        val stored = rig.store.get(rig.pid)!!.source.byId[SourceNodeId("mei")]!!
        assertEquals(stored.contentHash, out.node.contentHash)
        assertEquals(stored.revision, out.node.revision)
    }

    @Test fun summaryUsesHumanisedCharacterRef() = runTest {
        val rig = rig(Project(id = ProjectId("p"), timeline = Timeline()))
        rig.store.mutateSource(rig.pid) { source ->
            source.addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair, warrior garb"),
            )
        }
        val out = rig.tool.execute(
            DescribeSourceNodeTool.Input(projectId = rig.pid.value, nodeId = "mei"),
            rig.ctx,
        ).data
        assertTrue(out.summary.contains("Mei"))
        assertTrue(out.summary.contains("teal hair"))
    }
}
