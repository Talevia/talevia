package io.talevia.core.tool.builtin.project

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class RenameProjectToolTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = {},
        messages = emptyList(),
    )

    private fun fixture(): Triple<FileProjectStore, ProjectId, Project> {
        val store = ProjectStoreTestKit.create()
        val pid = ProjectId("p-1")
        val project = Project(id = pid, timeline = Timeline())
        return Triple(store, pid, project)
    }

    private fun renameInput(projectId: String, title: String) = ProjectLifecycleActionTool.Input(
        action = "rename",
        projectId = projectId,
        title = title,
    )

    @Test
    fun renames_existing_project() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("Old Title", project)
        val tool = ProjectLifecycleActionTool(store)

        val result = tool.execute(renameInput(pid.value, "New Title"), ctx())

        val rename = assertNotNull(result.data.renameResult)
        assertEquals("Old Title", rename.previousTitle)
        assertEquals("New Title", rename.title)
        val after = store.summary(pid)
        assertNotNull(after)
        assertEquals("New Title", after.title)
    }

    @Test
    fun project_model_is_untouched() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("Old", project)
        val before = store.get(pid)!!
        val tool = ProjectLifecycleActionTool(store)

        tool.execute(renameInput(pid.value, "New"), ctx())

        val after = store.get(pid)!!
        assertEquals(before, after)
    }

    @Test
    fun no_op_when_title_identical() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("Same", project)
        val tool = ProjectLifecycleActionTool(store)

        val result = tool.execute(renameInput(pid.value, "Same"), ctx())

        val rename = assertNotNull(result.data.renameResult)
        assertEquals("Same", rename.previousTitle)
        assertEquals("Same", rename.title)
    }

    @Test
    fun rejects_missing_project() = runTest {
        val (store, _, _) = fixture()
        val tool = ProjectLifecycleActionTool(store)

        assertFailsWith<IllegalStateException> {
            tool.execute(renameInput("no-such-project", "X"), ctx())
        }
    }

    @Test
    fun rejects_blank_title() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("Old", project)
        val tool = ProjectLifecycleActionTool(store)

        assertFailsWith<IllegalArgumentException> {
            tool.execute(renameInput(pid.value, "  "), ctx())
        }
    }

    @Test
    fun rename_persists_across_list_summaries() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("First", project)
        val tool = ProjectLifecycleActionTool(store)

        tool.execute(renameInput(pid.value, "Second"), ctx())

        val summaries = store.listSummaries()
        assertEquals(1, summaries.size)
        assertEquals("Second", summaries.single().title)
        assertEquals(pid.value, summaries.single().id)
    }

    @Test
    fun rejects_missing_title() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("Old", project)
        val tool = ProjectLifecycleActionTool(store)

        assertFailsWith<IllegalStateException> {
            tool.execute(
                ProjectLifecycleActionTool.Input(action = "rename", projectId = pid.value),
                ctx(),
            )
        }
    }
}
