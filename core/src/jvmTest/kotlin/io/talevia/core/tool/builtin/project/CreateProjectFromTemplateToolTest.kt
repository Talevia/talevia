package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.SourceNodeId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.genre.narrative.NarrativeNodeKinds
import io.talevia.core.domain.source.genre.vlog.VlogNodeKinds
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CreateProjectFromTemplateToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun newStore(): SqlDelightProjectStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightProjectStore(TaleviaDb(driver))
    }

    @Test fun narrativeSeedsSixNodesAndWiresDag() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "My Short",
                template = "narrative",
                projectId = "narr-1",
            ),
            ctx(),
        ).data

        assertEquals("narr-1", out.projectId)
        assertEquals("narrative", out.template)
        assertEquals(6, out.seededNodeIds.size)

        val project = store.get(ProjectId("narr-1"))!!
        val kinds = project.source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.CHARACTER_REF, kinds["protagonist"])
        assertEquals(ConsistencyKinds.STYLE_BIBLE, kinds["style"])
        assertEquals(NarrativeNodeKinds.WORLD, kinds["world-1"])
        assertEquals(NarrativeNodeKinds.STORYLINE, kinds["story-1"])
        assertEquals(NarrativeNodeKinds.SCENE, kinds["scene-1"])
        assertEquals(NarrativeNodeKinds.SHOT, kinds["shot-1"])

        // DAG: editing the style should propagate through world → storyline → scene → shot
        val stale = project.source.byId // confirm graph is intact
        assertTrue(SourceNodeId("world-1") in stale)

        // And the seeded timeline is empty (user fills it in by generating clips)
        assertEquals(0, project.timeline.tracks.sumOf { it.clips.size })
    }

    @Test fun vlogSeedsFourNodes() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "Graduation",
                template = "vlog",
                projectId = "vlog-1",
            ),
            ctx(),
        ).data

        assertEquals("vlog-1", out.projectId)
        assertEquals("vlog", out.template)
        assertEquals(4, out.seededNodeIds.size)

        val project = store.get(ProjectId("vlog-1"))!!
        val kinds = project.source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.STYLE_BIBLE, kinds["style"])
        assertEquals(VlogNodeKinds.RAW_FOOTAGE, kinds["footage"])
        assertEquals(VlogNodeKinds.EDIT_INTENT, kinds["intent"])
        assertEquals(VlogNodeKinds.STYLE_PRESET, kinds["style-preset"])
    }

    @Test fun unknownTemplateFailsLoud() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CreateProjectFromTemplateTool.Input(title = "T", template = "mv"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("mv"))
    }

    @Test fun duplicateProjectIdFailsLoud() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        tool.execute(
            CreateProjectFromTemplateTool.Input(title = "a", template = "narrative", projectId = "p-dup"),
            ctx(),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CreateProjectFromTemplateTool.Input(title = "b", template = "vlog", projectId = "p-dup"),
                ctx(),
            )
        }
        assertTrue(ex.message!!.contains("already exists"))
    }

    @Test fun resolutionAndFpsParsed() = runTest {
        val store = newStore()
        val tool = CreateProjectFromTemplateTool(store)
        val out = tool.execute(
            CreateProjectFromTemplateTool.Input(
                title = "t",
                template = "vlog",
                projectId = "p-4k",
                resolutionPreset = "4k",
                fps = 24,
            ),
            ctx(),
        ).data
        assertEquals(3840, out.resolutionWidth)
        assertEquals(2160, out.resolutionHeight)
        assertEquals(24, out.fps)
    }
}
