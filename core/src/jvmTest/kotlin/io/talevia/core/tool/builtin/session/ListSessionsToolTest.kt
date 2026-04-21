package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListSessionsToolTest {

    private data class Rig(
        val store: SqlDelightSessionStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
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

    private suspend fun session(
        store: SqlDelightSessionStore,
        id: String,
        projectId: String,
        title: String = id,
        updatedAtMs: Long = 1_700_000_000_000L,
        parentId: String? = null,
        archived: Boolean = false,
    ): Session {
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId(projectId),
            title = title,
            parentId = parentId?.let { SessionId(it) },
            createdAt = Instant.fromEpochMilliseconds(updatedAtMs),
            updatedAt = Instant.fromEpochMilliseconds(updatedAtMs),
            archived = archived,
        )
        store.createSession(s)
        return s
    }

    @Test fun listsAllSessionsSortedByUpdatedAtDescending() = runTest {
        val rig = rig()
        session(rig.store, "s-oldest", "p", updatedAtMs = 1_000L)
        session(rig.store, "s-newest", "p", updatedAtMs = 3_000L)
        session(rig.store, "s-mid", "p", updatedAtMs = 2_000L)

        val out = ListSessionsTool(rig.store).execute(
            ListSessionsTool.Input(),
            rig.ctx,
        ).data

        assertEquals(3, out.totalSessions)
        assertEquals(3, out.returnedSessions)
        assertEquals(listOf("s-newest", "s-mid", "s-oldest"), out.sessions.map { it.id })
    }

    @Test fun projectFilterScopesToOneProject() = runTest {
        val rig = rig()
        session(rig.store, "s-a", "p-a")
        session(rig.store, "s-b", "p-b")
        session(rig.store, "s-a2", "p-a")

        val out = ListSessionsTool(rig.store).execute(
            ListSessionsTool.Input(projectId = "p-a"),
            rig.ctx,
        ).data

        assertEquals(2, out.totalSessions)
        assertEquals(setOf("s-a", "s-a2"), out.sessions.map { it.id }.toSet())
    }

    @Test fun archivedSessionsAreFilteredByDefault() = runTest {
        val rig = rig()
        session(rig.store, "s-live", "p")
        session(rig.store, "s-archived", "p", archived = true)

        val out = ListSessionsTool(rig.store).execute(
            ListSessionsTool.Input(),
            rig.ctx,
        ).data

        assertEquals(1, out.totalSessions)
        assertEquals("s-live", out.sessions.single().id)
    }

    @Test fun includeArchivedFlagSurfacesArchivedSessions() = runTest {
        val rig = rig()
        session(rig.store, "s-live", "p", updatedAtMs = 2_000L)
        session(rig.store, "s-archived", "p", updatedAtMs = 1_000L, archived = true)

        val out = ListSessionsTool(rig.store).execute(
            ListSessionsTool.Input(includeArchived = true),
            rig.ctx,
        ).data

        assertEquals(2, out.totalSessions)
        val byId = out.sessions.associateBy { it.id }
        assertTrue(byId.getValue("s-archived").archived)
        assertTrue(!byId.getValue("s-live").archived)
    }

    @Test fun includeArchivedRespectsProjectFilter() = runTest {
        val rig = rig()
        session(rig.store, "s-a", "p-a", archived = true)
        session(rig.store, "s-b", "p-b", archived = true)

        val out = ListSessionsTool(rig.store).execute(
            ListSessionsTool.Input(projectId = "p-a", includeArchived = true),
            rig.ctx,
        ).data

        assertEquals(1, out.totalSessions)
        assertEquals("s-a", out.sessions.single().id)
    }

    @Test fun parentIdIsSurfaced() = runTest {
        val rig = rig()
        session(rig.store, "s-parent", "p")
        session(rig.store, "s-child", "p", parentId = "s-parent")

        val out = ListSessionsTool(rig.store).execute(
            ListSessionsTool.Input(),
            rig.ctx,
        ).data

        val child = out.sessions.single { it.id == "s-child" }
        assertEquals("s-parent", child.parentId)
    }

    @Test fun limitCapsReturnedResults() = runTest {
        val rig = rig()
        repeat(5) { i -> session(rig.store, "s-$i", "p", updatedAtMs = 1_000L + i) }

        val out = ListSessionsTool(rig.store).execute(
            ListSessionsTool.Input(limit = 2),
            rig.ctx,
        ).data

        assertEquals(5, out.totalSessions)
        assertEquals(2, out.returnedSessions)
        // Most-recent first.
        assertEquals(listOf("s-4", "s-3"), out.sessions.map { it.id })
    }

    @Test fun emptyProjectReturnsEmpty() = runTest {
        val rig = rig()
        val out = ListSessionsTool(rig.store).execute(
            ListSessionsTool.Input(projectId = "ghost"),
            rig.ctx,
        ).data
        assertEquals(0, out.totalSessions)
        assertTrue(out.sessions.isEmpty())
    }
}
