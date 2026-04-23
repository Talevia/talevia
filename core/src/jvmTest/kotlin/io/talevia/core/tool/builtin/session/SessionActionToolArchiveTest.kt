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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionActionToolArchiveTest {

    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(NOW_MS)
    }

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

    private suspend fun seed(
        store: SqlDelightSessionStore,
        archived: Boolean = false,
    ): Session {
        val now = Instant.fromEpochMilliseconds(1_600_000_000_000L)
        val s = Session(
            id = SessionId("s-1"),
            projectId = ProjectId("p"),
            title = "t",
            createdAt = now,
            updatedAt = now,
            archived = archived,
        )
        store.createSession(s)
        return s
    }

    @Test fun archivesLiveSession() = runTest {
        val rig = rig()
        seed(rig.store)

        val out = SessionActionTool(rig.store, fixedClock).execute(
            SessionActionTool.Input(action = "archive", sessionId = "s-1"),
            rig.ctx,
        ).data

        assertFalse(out.wasAlreadyInTargetState)
        // Store's listSessions filters archived → session vanishes from the list view.
        val visible = rig.store.listSessions(null).map { it.id }
        assertTrue(SessionId("s-1") !in visible)
        // But row exists and is archived.
        val refreshed = rig.store.getSession(SessionId("s-1"))!!
        assertTrue(refreshed.archived)
    }

    @Test fun archivingAlreadyArchivedIsNoOp() = runTest {
        val rig = rig()
        seed(rig.store, archived = true)

        val out = SessionActionTool(rig.store, fixedClock).execute(
            SessionActionTool.Input(action = "archive", sessionId = "s-1"),
            rig.ctx,
        ).data

        assertTrue(out.wasAlreadyInTargetState)
        val refreshed = rig.store.getSession(SessionId("s-1"))!!
        assertTrue(refreshed.archived)
    }

    @Test fun unarchiveRestoresLiveSession() = runTest {
        val rig = rig()
        seed(rig.store, archived = true)

        val out = SessionActionTool(rig.store, fixedClock).execute(
            SessionActionTool.Input(action = "unarchive", sessionId = "s-1"),
            rig.ctx,
        ).data

        assertFalse(out.wasAlreadyInTargetState)
        val visible = rig.store.listSessions(null).map { it.id }
        assertTrue(SessionId("s-1") in visible)
    }

    @Test fun unarchiveAlreadyLiveIsNoOp() = runTest {
        val rig = rig()
        seed(rig.store, archived = false)

        val out = SessionActionTool(rig.store, fixedClock).execute(
            SessionActionTool.Input(action = "unarchive", sessionId = "s-1"),
            rig.ctx,
        ).data

        assertTrue(out.wasAlreadyInTargetState)
    }

    @Test fun archiveMissingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionActionTool(rig.store, fixedClock).execute(
                SessionActionTool.Input(action = "archive", sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun unarchiveMissingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionActionTool(rig.store, fixedClock).execute(
                SessionActionTool.Input(action = "unarchive", sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
    }

    @Test fun archiveBumpsUpdatedAt() = runTest {
        val rig = rig()
        seed(rig.store)
        SessionActionTool(rig.store, fixedClock).execute(
            SessionActionTool.Input(action = "archive", sessionId = "s-1"),
            rig.ctx,
        )
        val refreshed = rig.store.getSession(SessionId("s-1"))!!
        assertEquals(NOW_MS, refreshed.updatedAt.toEpochMilliseconds())
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
    }
}
