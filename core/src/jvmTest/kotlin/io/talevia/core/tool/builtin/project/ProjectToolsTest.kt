package io.talevia.core.tool.builtin.project

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectToolsTest {

    private fun rig(): Rig {
        val store = ProjectStoreTestKit.create()
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

    private data class Rig(val store: FileProjectStore, val ctx: ToolContext)

    @Test fun createProjectSlugsTitleAndPersistsDefaults() = runTest {
        val rig = rig()
        val tool = CreateProjectTool(rig.store)
        val out = tool.execute(
            CreateProjectTool.Input(title = "Graduation Vlog 2026"),
            rig.ctx,
        )
        assertEquals("proj-graduation-vlog-2026", out.data.projectId)
        assertEquals(1920, out.data.resolutionWidth)
        assertEquals(1080, out.data.resolutionHeight)
        assertEquals(30, out.data.fps)
        val project = rig.store.get(ProjectId(out.data.projectId))
        assertNotNull(project)
        assertEquals(1920, project.outputProfile.resolution.width)
        assertEquals(0, project.timeline.tracks.size)
    }

    @Test fun createProjectAcceptsResolutionAndFpsOverrides() = runTest {
        val rig = rig()
        val tool = CreateProjectTool(rig.store)
        val out = tool.execute(
            CreateProjectTool.Input(title = "promo", resolutionPreset = "4k", fps = 60),
            rig.ctx,
        )
        assertEquals(3840, out.data.resolutionWidth)
        assertEquals(2160, out.data.resolutionHeight)
        assertEquals(60, out.data.fps)
    }

    @Test fun createProjectRejectsUnknownPreset() = runTest {
        val rig = rig()
        val tool = CreateProjectTool(rig.store)
        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CreateProjectTool.Input(title = "x", resolutionPreset = "8k"),
                rig.ctx,
            )
        }
    }

    @Test fun createProjectFailsLoudOnDuplicateId() = runTest {
        val rig = rig()
        val tool = CreateProjectTool(rig.store)
        tool.execute(CreateProjectTool.Input(title = "Mei", projectId = "proj-mei"), rig.ctx)
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                CreateProjectTool.Input(title = "Mei v2", projectId = "proj-mei"),
                rig.ctx,
            )
        }
        assertTrue("already exists" in ex.message.orEmpty())
    }

    @Test fun listProjectsReturnsCatalogMetadata() = runTest {
        val rig = rig()
        val create = CreateProjectTool(rig.store)
        create.execute(CreateProjectTool.Input(title = "Alpha"), rig.ctx)
        create.execute(CreateProjectTool.Input(title = "Beta"), rig.ctx)

        val tool = ListProjectsTool(rig.store)
        val out = tool.execute(ListProjectsTool.Input(), rig.ctx)
        assertEquals(2, out.data.totalCount)
        val titles = out.data.projects.map { it.title }.toSet()
        assertEquals(setOf("Alpha", "Beta"), titles)
    }

    @Test fun listProjectsEmptyHasFriendlyMessage() = runTest {
        val rig = rig()
        val out = ListProjectsTool(rig.store).execute(ListProjectsTool.Input(), rig.ctx)
        assertEquals(0, out.data.totalCount)
        assertTrue("create_project" in out.outputForLlm)
    }

    @Test fun getProjectStateReportsCountsAndProfile() = runTest {
        val rig = rig()
        CreateProjectTool(rig.store).execute(
            CreateProjectTool.Input(title = "snap", resolutionPreset = "720p", fps = 24),
            rig.ctx,
        )
        val out = GetProjectStateTool(rig.store).execute(
            GetProjectStateTool.Input(projectId = "proj-snap"),
            rig.ctx,
        )
        assertEquals("snap", out.data.title)
        assertEquals(1280, out.data.resolutionWidth)
        assertEquals(720, out.data.resolutionHeight)
        assertEquals(24, out.data.fps)
        assertEquals(0, out.data.assetCount)
        assertEquals(0, out.data.sourceNodeCount)
        assertEquals(0, out.data.lockfileEntryCount)
        assertEquals(0, out.data.renderCacheEntryCount)
        assertEquals(0, out.data.trackCount)
    }

    @Test fun getProjectStateFailsForMissingProject() = runTest {
        val rig = rig()
        assertFailsWith<IllegalStateException> {
            GetProjectStateTool(rig.store).execute(
                GetProjectStateTool.Input(projectId = "proj-nope"),
                rig.ctx,
            )
        }
    }

    @Test fun deleteProjectRemovesRowAndReportsTitle() = runTest {
        val rig = rig()
        CreateProjectTool(rig.store).execute(CreateProjectTool.Input(title = "doomed"), rig.ctx)
        val out = DeleteProjectTool(rig.store).execute(
            DeleteProjectTool.Input(projectId = "proj-doomed"),
            rig.ctx,
        )
        assertEquals("doomed", out.data.title)
        assertNull(rig.store.get(ProjectId("proj-doomed")))
    }

    @Test fun deleteProjectFailsLoudWhenMissing() = runTest {
        val rig = rig()
        assertFailsWith<IllegalStateException> {
            DeleteProjectTool(rig.store).execute(
                DeleteProjectTool.Input(projectId = "proj-ghost"),
                rig.ctx,
            )
        }
    }

    @Test fun slugifierHandlesPunctuationAndCollapses() {
        assertEquals("proj-hello-world", slugifyProjectId("Hello, World!!!"))
        assertEquals("proj-untitled", slugifyProjectId("***"))
        assertEquals("proj-a-b-c", slugifyProjectId("  a---b---c  "))
    }
}
