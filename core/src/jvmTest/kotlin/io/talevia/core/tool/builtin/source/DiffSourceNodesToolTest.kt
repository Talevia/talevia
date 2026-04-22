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
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffSourceNodesToolTest {

    private data class Rig(
        val store: FileProjectStore,
        val tool: DiffSourceNodesTool,
        val ctx: ToolContext,
    )

    private suspend fun rig(projects: List<Project> = listOf(Project(id = ProjectId("p"), timeline = Timeline()))): Rig {
        val store = ProjectStoreTestKit.create()
        projects.forEachIndexed { i, p -> store.upsert("demo-$i", p) }
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, DiffSourceNodesTool(store), ctx)
    }

    private fun node(
        id: String,
        kind: String = "narrative.character",
        body: JsonObject = JsonObject(emptyMap()),
        parents: List<String> = emptyList(),
    ): SourceNode = SourceNode.create(
        id = SourceNodeId(id),
        kind = kind,
        body = body,
        parents = parents.map { SourceRef(SourceNodeId(it)) },
    )

    @Test fun identicalNodesAgainstItself() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source.addNode(node("mei", body = buildJsonObject { put("name", JsonPrimitive("Mei")) }))
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "mei", rightNodeId = "mei"),
            rig.ctx,
        ).data
        assertTrue(out.bothExist)
        assertFalse(out.kindChanged)
        assertFalse(out.contentHashChanged)
        assertTrue(out.bodyFieldDiffs.isEmpty())
        assertTrue(out.parentsAdded.isEmpty())
        assertTrue(out.parentsRemoved.isEmpty())
        assertEquals(out.leftContentHash, out.rightContentHash)
    }

    @Test fun scalarBodyFieldChanged() = runTest {
        val rig = rig()
        val pid = ProjectId("p")
        rig.store.mutateSource(pid) { source ->
            source
                .addNode(node("a", body = buildJsonObject { put("visualDescription", JsonPrimitive("teal hair")) }))
                .addNode(node("b", body = buildJsonObject { put("visualDescription", JsonPrimitive("silver hair")) }))
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "a", rightNodeId = "b"),
            rig.ctx,
        ).data
        assertTrue(out.contentHashChanged)
        assertEquals(1, out.bodyFieldDiffs.size)
        val d = out.bodyFieldDiffs.single()
        assertEquals("visualDescription", d.path)
        assertEquals(JsonPrimitive("teal hair"), d.leftValue)
        assertEquals(JsonPrimitive("silver hair"), d.rightValue)
    }

    @Test fun arrayBodyFieldPerIndexDiff() = runTest {
        val rig = rig()
        val pid = ProjectId("p")
        rig.store.mutateSource(pid) { source ->
            source
                .addNode(
                    node(
                        "s1",
                        kind = "narrative.story",
                        body = buildJsonObject {
                            put(
                                "acts",
                                buildJsonArray {
                                    add(JsonPrimitive("open"))
                                    add(JsonPrimitive("rising"))
                                    add(JsonPrimitive("fall"))
                                },
                            )
                        },
                    ),
                )
                .addNode(
                    node(
                        "s2",
                        kind = "narrative.story",
                        body = buildJsonObject {
                            put(
                                "acts",
                                buildJsonArray {
                                    add(JsonPrimitive("open"))
                                    add(JsonPrimitive("climax")) // index 1 changed
                                    // index 2 dropped
                                },
                            )
                        },
                    ),
                )
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "s1", rightNodeId = "s2"),
            rig.ctx,
        ).data
        val paths = out.bodyFieldDiffs.map { it.path }.toSet()
        assertTrue("acts[1]" in paths, paths.toString())
        assertTrue("acts[2]" in paths, paths.toString())
        val at2 = out.bodyFieldDiffs.single { it.path == "acts[2]" }
        assertEquals(JsonPrimitive("fall"), at2.leftValue)
        assertNull(at2.rightValue)
    }

    @Test fun nestedObjectFieldDottedPath() = runTest {
        val rig = rig()
        val pid = ProjectId("p")
        rig.store.mutateSource(pid) { source ->
            source
                .addNode(
                    node(
                        "a",
                        body = buildJsonObject {
                            put(
                                "style",
                                buildJsonObject {
                                    put("primary", JsonPrimitive("#ff0000"))
                                    put("secondary", JsonPrimitive("#00ff00"))
                                },
                            )
                        },
                    ),
                )
                .addNode(
                    node(
                        "b",
                        body = buildJsonObject {
                            put(
                                "style",
                                buildJsonObject {
                                    put("primary", JsonPrimitive("#ff0000"))
                                    put("secondary", JsonPrimitive("#0000ff")) // changed
                                },
                            )
                        },
                    ),
                )
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "a", rightNodeId = "b"),
            rig.ctx,
        ).data
        assertEquals(1, out.bodyFieldDiffs.size)
        val d = out.bodyFieldDiffs.single()
        assertEquals("style.secondary", d.path)
        assertEquals(JsonPrimitive("#00ff00"), d.leftValue)
        assertEquals(JsonPrimitive("#0000ff"), d.rightValue)
    }

    @Test fun kindChangedFlagSet() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source
                .addNode(node("a", kind = "narrative.character"))
                .addNode(node("b", kind = "narrative.shot"))
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "a", rightNodeId = "b"),
            rig.ctx,
        ).data
        assertTrue(out.kindChanged)
        assertEquals("narrative.character", out.leftKind)
        assertEquals("narrative.shot", out.rightKind)
    }

    @Test fun parentsAddedAndRemovedReported() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source
                .addNode(node("style", kind = "narrative.style"))
                .addNode(node("palette", kind = "narrative.palette"))
                .addNode(node("a", parents = listOf("style"))) // parents {style}
                .addNode(node("b", parents = listOf("palette"))) // parents {palette}
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "a", rightNodeId = "b"),
            rig.ctx,
        ).data
        assertEquals(listOf("palette"), out.parentsAdded)
        assertEquals(listOf("style"), out.parentsRemoved)
    }

    @Test fun missingLeftReportedStructurally() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source.addNode(node("present"))
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "ghost", rightNodeId = "present"),
            rig.ctx,
        ).data
        assertFalse(out.leftExists)
        assertTrue(out.rightExists)
        assertFalse(out.bothExist)
        assertTrue(out.bodyFieldDiffs.isEmpty())
        assertTrue(out.parentsAdded.isEmpty())
        assertTrue(out.parentsRemoved.isEmpty())
    }

    @Test fun missingRightReportedStructurally() = runTest {
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source.addNode(node("present"))
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "present", rightNodeId = "ghost"),
            rig.ctx,
        ).data
        assertTrue(out.leftExists)
        assertFalse(out.rightExists)
        assertFalse(out.bothExist)
    }

    @Test fun crossProjectDiffsNodesInTwoProjects() = runTest {
        val rig = rig(
            projects = listOf(
                Project(id = ProjectId("parent"), timeline = Timeline()),
                Project(id = ProjectId("fork"), timeline = Timeline()),
            ),
        )
        rig.store.mutateSource(ProjectId("parent")) { source ->
            source.addNode(
                node(
                    "mei",
                    body = buildJsonObject {
                        put("name", JsonPrimitive("Mei"))
                        put("visualDescription", JsonPrimitive("teal hair"))
                    },
                ),
            )
        }
        rig.store.mutateSource(ProjectId("fork")) { source ->
            source.addNode(
                node(
                    "mei",
                    body = buildJsonObject {
                        put("name", JsonPrimitive("Mei"))
                        put("visualDescription", JsonPrimitive("red hair")) // variant
                    },
                ),
            )
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(
                projectId = "parent",
                leftNodeId = "mei",
                rightNodeId = "mei",
                rightProjectId = "fork",
            ),
            rig.ctx,
        ).data
        assertTrue(out.bothExist)
        assertEquals("parent", out.leftProjectId)
        assertEquals("fork", out.rightProjectId)
        assertTrue(out.contentHashChanged)
        assertEquals(1, out.bodyFieldDiffs.size)
        assertEquals("visualDescription", out.bodyFieldDiffs.single().path)
    }

    @Test fun missingProjectFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                DiffSourceNodesTool.Input(projectId = "ghost", leftNodeId = "a", rightNodeId = "b"),
                rig.ctx,
            )
        }
        assertTrue("ghost" in ex.message!!, ex.message)
    }

    @Test fun fieldAbsentOnRightEmitsNullRightValue() = runTest {
        // Added field on left (absent on right) — exercises the "one-sided key" branch.
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source
                .addNode(
                    node(
                        "a",
                        body = buildJsonObject {
                            put("name", JsonPrimitive("Mei"))
                            put("loraPin", JsonPrimitive("lora-v1"))
                        },
                    ),
                )
                .addNode(
                    node(
                        "b",
                        body = buildJsonObject { put("name", JsonPrimitive("Mei")) },
                    ),
                )
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "a", rightNodeId = "b"),
            rig.ctx,
        ).data
        val d = out.bodyFieldDiffs.single()
        assertEquals("loraPin", d.path)
        assertEquals(JsonPrimitive("lora-v1"), d.leftValue)
        assertNull(d.rightValue)
    }

    @Test fun revisionOfSameNodeAfterUpdateIsDiffed() = runTest {
        // Simulates "generate → update" history: same id, body mutated in place.
        val rig = rig()
        val pid = ProjectId("p")
        rig.store.mutateSource(pid) { source ->
            source.addNode(node("mei", body = buildJsonObject { put("name", JsonPrimitive("Mei")) }))
        }
        val leftHash = rig.store.get(pid)!!.source.byId[SourceNodeId("mei")]!!.contentHash
        rig.store.mutateSource(pid) { source ->
            source.replaceNode(SourceNodeId("mei")) {
                node("mei", body = buildJsonObject { put("name", JsonPrimitive("Mei v2")) })
            }
        }
        // After the replace, there's only one node — but a diff of the current state
        // against itself should still be all-equal (sanity). Real "past vs present"
        // would use fork_source_node or a snapshot; this verifies that the replace
        // produced a different contentHash than the original capture.
        val currentHash = rig.store.get(pid)!!.source.byId[SourceNodeId("mei")]!!.contentHash
        assertTrue(leftHash != currentHash, "replaceNode should bump contentHash")

        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "mei", rightNodeId = "mei"),
            rig.ctx,
        ).data
        assertFalse(out.contentHashChanged)
    }

    @Test fun arraySymmetryDifferenceAllUnused() = runTest {
        // Sanity: arrays matching element-for-element produce zero diffs.
        val rig = rig()
        rig.store.mutateSource(ProjectId("p")) { source ->
            source
                .addNode(
                    node(
                        "a",
                        body = buildJsonObject {
                            put("tags", buildJsonArray { add(JsonPrimitive("x")); add(JsonPrimitive("y")) })
                        },
                    ),
                )
                .addNode(
                    node(
                        "b",
                        body = buildJsonObject {
                            put("tags", buildJsonArray { add(JsonPrimitive("x")); add(JsonPrimitive("y")) })
                        },
                    ),
                )
        }
        val out = rig.tool.execute(
            DiffSourceNodesTool.Input(projectId = "p", leftNodeId = "a", rightNodeId = "b"),
            rig.ctx,
        ).data
        assertTrue(out.bodyFieldDiffs.isEmpty())
    }
}
