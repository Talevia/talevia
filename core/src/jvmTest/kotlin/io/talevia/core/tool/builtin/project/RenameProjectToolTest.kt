package io.talevia.core.tool.builtin.project

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.Project
import io.talevia.core.domain.SqlDelightProjectStore
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

    private fun fixture(title: String = "Original"): Triple<SqlDelightProjectStore, ProjectId, Project> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightProjectStore(TaleviaDb(driver))
        val pid = ProjectId("p-1")
        val project = Project(id = pid, timeline = Timeline())
        return Triple(store, pid, project).also {
            // upsert happens inside the test body after store+project are constructed
        }
    }

    @Test
    fun renames_existing_project() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("Old Title", project)
        val tool = RenameProjectTool(store)

        val result = tool.execute(RenameProjectTool.Input(pid.value, "New Title"), ctx())

        assertEquals("Old Title", result.data.previousTitle)
        assertEquals("New Title", result.data.title)
        val after = store.summary(pid)
        assertNotNull(after)
        assertEquals("New Title", after.title)
    }

    @Test
    fun project_model_is_untouched() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("Old", project)
        val before = store.get(pid)!!
        val tool = RenameProjectTool(store)

        tool.execute(RenameProjectTool.Input(pid.value, "New"), ctx())

        val after = store.get(pid)!!
        assertEquals(before, after)
    }

    @Test
    fun no_op_when_title_identical() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("Same", project)
        val tool = RenameProjectTool(store)

        val result = tool.execute(RenameProjectTool.Input(pid.value, "Same"), ctx())

        assertEquals("Same", result.data.previousTitle)
        assertEquals("Same", result.data.title)
    }

    @Test
    fun rejects_missing_project() = runTest {
        val (store, _, _) = fixture()
        val tool = RenameProjectTool(store)

        assertFailsWith<IllegalStateException> {
            tool.execute(RenameProjectTool.Input("no-such-project", "X"), ctx())
        }
    }

    @Test
    fun rejects_blank_title() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("Old", project)
        val tool = RenameProjectTool(store)

        assertFailsWith<IllegalArgumentException> {
            tool.execute(RenameProjectTool.Input(pid.value, "  "), ctx())
        }
    }

    @Test
    fun rename_persists_across_list_summaries() = runTest {
        val (store, pid, project) = fixture()
        store.upsert("First", project)
        val tool = RenameProjectTool(store)

        tool.execute(RenameProjectTool.Input(pid.value, "Second"), ctx())

        val summaries = store.listSummaries()
        assertEquals(1, summaries.size)
        assertEquals("Second", summaries.single().title)
        assertEquals(pid.value, summaries.single().id)
    }
}
