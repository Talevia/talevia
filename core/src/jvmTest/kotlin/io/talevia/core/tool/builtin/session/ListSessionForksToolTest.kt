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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ListSessionForksToolTest {

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

    private suspend fun newSession(
        store: SqlDelightSessionStore,
        id: String,
        parentId: String? = null,
        createdAtMs: Long = 1_700_000_000_000L,
        archived: Boolean = false,
    ): Session {
        val now = Instant.fromEpochMilliseconds(createdAtMs)
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId("p"),
            title = id,
            parentId = parentId?.let { SessionId(it) },
            createdAt = now,
            updatedAt = now,
            archived = archived,
        )
        store.createSession(s)
        return s
    }

    @Test fun returnsImmediateChildrenOnly() = runTest {
        val rig = rig()
        newSession(rig.store, "s-root")
        newSession(rig.store, "s-child-1", parentId = "s-root", createdAtMs = 2_000L)
        newSession(rig.store, "s-child-2", parentId = "s-root", createdAtMs = 3_000L)
        // Grandchild — not returned by list_session_forks on the root.
        newSession(rig.store, "s-grandchild", parentId = "s-child-1", createdAtMs = 4_000L)

        val out = ListSessionForksTool(rig.store).execute(
            ListSessionForksTool.Input(sessionId = "s-root"),
            rig.ctx,
        ).data

        assertEquals("s-root", out.parentSessionId)
        assertEquals(2, out.forkCount)
        assertEquals(listOf("s-child-1", "s-child-2"), out.forks.map { it.id })
    }

    @Test fun emptyFamilyReturnsZero() = runTest {
        val rig = rig()
        newSession(rig.store, "s-root")

        val out = ListSessionForksTool(rig.store).execute(
            ListSessionForksTool.Input(sessionId = "s-root"),
            rig.ctx,
        ).data

        assertEquals(0, out.forkCount)
        assertTrue(out.forks.isEmpty())
    }

    @Test fun archivedChildrenAreIncluded() = runTest {
        val rig = rig()
        newSession(rig.store, "s-root")
        newSession(rig.store, "s-live", parentId = "s-root", createdAtMs = 2_000L)
        newSession(rig.store, "s-archived", parentId = "s-root", createdAtMs = 3_000L, archived = true)

        val out = ListSessionForksTool(rig.store).execute(
            ListSessionForksTool.Input(sessionId = "s-root"),
            rig.ctx,
        ).data

        assertEquals(2, out.forkCount)
        val byId = out.forks.associateBy { it.id }
        assertTrue(byId.getValue("s-archived").archived)
        assertTrue(!byId.getValue("s-live").archived)
    }

    @Test fun oldestFirstOrdering() = runTest {
        val rig = rig()
        newSession(rig.store, "s-root")
        newSession(rig.store, "s-c", parentId = "s-root", createdAtMs = 3_000L)
        newSession(rig.store, "s-a", parentId = "s-root", createdAtMs = 1_000L)
        newSession(rig.store, "s-b", parentId = "s-root", createdAtMs = 2_000L)

        val out = ListSessionForksTool(rig.store).execute(
            ListSessionForksTool.Input(sessionId = "s-root"),
            rig.ctx,
        ).data
        assertEquals(listOf("s-a", "s-b", "s-c"), out.forks.map { it.id })
    }

    @Test fun missingParentFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ListSessionForksTool(rig.store).execute(
                ListSessionForksTool.Input(sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("list_sessions"), ex.message)
    }
}
