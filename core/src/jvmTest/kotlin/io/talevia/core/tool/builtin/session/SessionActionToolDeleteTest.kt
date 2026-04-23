package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionActionToolDeleteTest {

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

    private suspend fun seedSession(
        store: SqlDelightSessionStore,
        id: String = "s-1",
        title: String = "t",
        archived: Boolean = false,
    ): Session {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId("p"),
            title = title,
            createdAt = now,
            updatedAt = now,
            archived = archived,
        )
        store.createSession(s)
        return s
    }

    private suspend fun appendUser(store: SqlDelightSessionStore, sessionId: String, messageId: String) {
        store.appendMessage(
            Message.User(
                id = MessageId(messageId),
                sessionId = SessionId(sessionId),
                createdAt = Instant.fromEpochMilliseconds(1_000L),
                agent = "default",
                model = ModelRef("anthropic", "claude"),
            ),
        )
    }

    @Test fun deletesSessionAndCascadesMessages() = runTest {
        val rig = rig()
        seedSession(rig.store)
        appendUser(rig.store, "s-1", "u-1")
        appendUser(rig.store, "s-1", "u-2")

        val out = SessionActionTool(rig.store).execute(
            SessionActionTool.Input(action = "delete", sessionId = "s-1"),
            rig.ctx,
        ).data

        assertEquals("s-1", out.sessionId)
        assertEquals("t", out.title)
        assertFalse(out.archived)

        assertNull(rig.store.getSession(SessionId("s-1")))
        // Messages cascade via FK (Messages.session_id ON DELETE CASCADE).
        assertTrue(rig.store.listMessages(SessionId("s-1")).isEmpty())
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionActionTool(rig.store).execute(
                SessionActionTool.Input(action = "delete", sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("session_query(select=sessions)"), ex.message)
    }

    @Test fun deletingArchivedSessionReportsArchivedFlag() = runTest {
        val rig = rig()
        seedSession(rig.store, archived = true)

        val out = SessionActionTool(rig.store).execute(
            SessionActionTool.Input(action = "delete", sessionId = "s-1"),
            rig.ctx,
        ).data
        assertTrue(out.archived)
        assertNull(rig.store.getSession(SessionId("s-1")))
    }

    @Test fun otherSessionsUnaffected() = runTest {
        val rig = rig()
        seedSession(rig.store, id = "s-victim")
        seedSession(rig.store, id = "s-keeper", title = "keeper")

        SessionActionTool(rig.store).execute(
            SessionActionTool.Input(action = "delete", sessionId = "s-victim"),
            rig.ctx,
        )

        assertNull(rig.store.getSession(SessionId("s-victim")))
        val survivor = rig.store.getSession(SessionId("s-keeper"))
        assertTrue(survivor != null)
        assertEquals("keeper", survivor!!.title)
    }
}
