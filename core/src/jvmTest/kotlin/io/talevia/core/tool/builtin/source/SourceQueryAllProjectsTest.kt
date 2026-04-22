package io.talevia.core.tool.builtin.source

import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers `scope=all_projects` on `source_query(select=nodes)` — the
 * cross-project-source-similarity cycle. Verifies the cross-project
 * enumerate path, per-row projectId tagging, and the loud-fail guards
 * against mixing scope with dag_summary / projectId.
 */
class SourceQueryAllProjectsTest {

    private suspend fun multiProjectStore(
        projectToNodes: Map<String, List<SourceNode>>,
    ): FileProjectStore {
        val store = ProjectStoreTestKit.create()
        for ((pid, nodes) in projectToNodes) {
            store.upsert(
                pid,
                Project(
                    id = ProjectId(pid),
                    timeline = Timeline(),
                    source = Source(nodes = nodes),
                ),
            )
        }
        return store
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun node(
        id: String,
        kind: String = "core.consistency.character_ref",
        body: JsonObject = buildJsonObject {
            put("name", id)
            put("visualDescription", "cyberpunk look for $id")
        },
    ): SourceNode = SourceNode(id = SourceNodeId(id), kind = kind, body = body)

    @Test fun allProjectsEnumeratesAcrossEveryProject() = runTest {
        val store = multiProjectStore(
            mapOf(
                "proj-a" to listOf(node("mei"), node("taro", kind = "test.generic")),
                "proj-b" to listOf(node("lin")),
                "proj-c" to emptyList(),
            ),
        )
        val out = SourceQueryTool(store).execute(
            SourceQueryTool.Input(select = "nodes", scope = "all_projects"),
            ctx(),
        ).data

        assertEquals(3, out.total)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            out.rows,
        )
        // Every row must carry its owning projectId so callers can pinpoint hits.
        assertTrue(rows.all { it.projectId != null })
        assertEquals(
            setOf("proj-a" to "mei", "proj-a" to "taro", "proj-b" to "lin"),
            rows.map { it.projectId!! to it.id }.toSet(),
        )
    }

    @Test fun allProjectsKindFilterWorksAcrossProjects() = runTest {
        val store = multiProjectStore(
            mapOf(
                "proj-a" to listOf(
                    node("mei", kind = "core.consistency.character_ref"),
                    node("palette-a", kind = "core.consistency.brand_palette"),
                ),
                "proj-b" to listOf(
                    node("lin", kind = "core.consistency.character_ref"),
                ),
            ),
        )
        val out = SourceQueryTool(store).execute(
            SourceQueryTool.Input(
                select = "nodes",
                scope = "all_projects",
                kind = "core.consistency.character_ref",
            ),
            ctx(),
        ).data

        assertEquals(2, out.total)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            out.rows,
        )
        assertEquals(setOf("mei", "lin"), rows.map { it.id }.toSet())
    }

    @Test fun allProjectsContentSubstringSurfacesHitsWithSnippet() = runTest {
        val store = multiProjectStore(
            mapOf(
                "proj-a" to listOf(
                    node(
                        "mei",
                        body = buildJsonObject {
                            put("name", "Mei")
                            put("visualDescription", "cyberpunk teal hair")
                        },
                    ),
                ),
                "proj-b" to listOf(
                    node(
                        "lin",
                        body = buildJsonObject {
                            put("name", "Lin")
                            put("visualDescription", "medieval knight")
                        },
                    ),
                ),
            ),
        )
        val out = SourceQueryTool(store).execute(
            SourceQueryTool.Input(
                select = "nodes",
                scope = "all_projects",
                contentSubstring = "cyberpunk",
            ),
            ctx(),
        ).data

        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            out.rows,
        )
        assertEquals(1, rows.size, "Only mei should match cyberpunk")
        val hit = rows.single()
        assertEquals("mei", hit.id)
        assertEquals("proj-a", hit.projectId)
        val snippet = assertNotNull(hit.snippet, "content-substring hit must carry a snippet")
        assertTrue("cyberpunk" in snippet, "snippet should include matched text: $snippet")
    }

    @Test fun allProjectsEmptyStoreReturnsEmpty() = runTest {
        val store = multiProjectStore(emptyMap())
        val out = SourceQueryTool(store).execute(
            SourceQueryTool.Input(select = "nodes", scope = "all_projects"),
            ctx(),
        ).data
        assertEquals(0, out.total)
        assertEquals(0, out.returned)
    }

    @Test fun allProjectsRejectsProjectIdFilter() = runTest {
        val store = multiProjectStore(
            mapOf("proj-a" to listOf(node("mei"))),
        )
        val e = assertFailsWith<IllegalArgumentException> {
            SourceQueryTool(store).execute(
                SourceQueryTool.Input(
                    select = "nodes",
                    scope = "all_projects",
                    projectId = "proj-a",
                ),
                ctx(),
            )
        }
        assertTrue(
            e.message?.contains("mutually exclusive") == true,
            "expected 'mutually exclusive' hint; got: ${e.message}",
        )
    }

    @Test fun allProjectsRejectedForDagSummary() = runTest {
        val store = multiProjectStore(emptyMap())
        assertFailsWith<IllegalArgumentException> {
            SourceQueryTool(store).execute(
                SourceQueryTool.Input(select = "dag_summary", scope = "all_projects"),
                ctx(),
            )
        }
    }

    @Test fun scopeProjectStillRequiresProjectId() = runTest {
        val store = multiProjectStore(mapOf("proj-a" to listOf(node("mei"))))
        val e = assertFailsWith<IllegalStateException> {
            SourceQueryTool(store).execute(
                SourceQueryTool.Input(select = "nodes", scope = "project"),
                ctx(),
            )
        }
        assertTrue(
            e.message?.contains("projectId is required") == true,
            "expected projectId-required hint; got: ${e.message}",
        )
    }

    @Test fun unknownScopeValueFailsLoud() = runTest {
        val store = multiProjectStore(mapOf("proj-a" to listOf(node("mei"))))
        assertFailsWith<IllegalStateException> {
            SourceQueryTool(store).execute(
                SourceQueryTool.Input(select = "nodes", scope = "global"),
                ctx(),
            )
        }
    }

    @Test fun singleProjectScopeStillWorks() = runTest {
        val store = multiProjectStore(
            mapOf(
                "proj-a" to listOf(node("mei")),
                "proj-b" to listOf(node("lin")),
            ),
        )
        val out = SourceQueryTool(store).execute(
            SourceQueryTool.Input(select = "nodes", projectId = "proj-a"),
            ctx(),
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SourceQueryTool.NodeRow.serializer()),
            out.rows,
        )
        assertEquals(listOf("mei"), rows.map { it.id })
        // Single-project scope leaves projectId null on the row (the owning id is already in Input).
        assertTrue(rows.all { it.projectId == null })
    }
}
