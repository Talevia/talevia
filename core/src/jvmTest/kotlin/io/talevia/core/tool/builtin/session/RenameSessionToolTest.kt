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
import kotlin.test.assertTrue

class RenameSessionToolTest {

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

    private suspend fun seed(store: SqlDelightSessionStore, id: String = "s-1", title: String = "Untitled"): Session {
        val now = Instant.fromEpochMilliseconds(PAST_MS)
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId("p"),
            title = title,
            createdAt = now,
            updatedAt = now,
        )
        store.createSession(s)
        return s
    }

    @Test fun renamesAndBumpsUpdatedAt() = runTest {
        val rig = rig()
        seed(rig.store)

        val out = RenameSessionTool(rig.store, fixedClock).execute(
            RenameSessionTool.Input(sessionId = "s-1", newTitle = "Mei arc"),
            rig.ctx,
        ).data

        assertEquals("Untitled", out.previousTitle)
        assertEquals("Mei arc", out.newTitle)
        val refreshed = rig.store.getSession(SessionId("s-1"))!!
        assertEquals("Mei arc", refreshed.title)
        assertEquals(NOW_MS, refreshed.updatedAt.toEpochMilliseconds())
    }

    @Test fun sameTitleIsNoOpAndDoesNotBumpUpdatedAt() = runTest {
        val rig = rig()
        seed(rig.store, title = "Same")

        val before = rig.store.getSession(SessionId("s-1"))!!.updatedAt
        val out = RenameSessionTool(rig.store, fixedClock).execute(
            RenameSessionTool.Input(sessionId = "s-1", newTitle = "Same"),
            rig.ctx,
        ).data

        assertEquals("Same", out.previousTitle)
        assertEquals("Same", out.newTitle)
        val refreshed = rig.store.getSession(SessionId("s-1"))!!
        // updatedAt unchanged on a no-op — the store write is skipped.
        assertEquals(before, refreshed.updatedAt)
    }

    @Test fun blankTitleIsRejected() = runTest {
        val rig = rig()
        seed(rig.store)
        assertFailsWith<IllegalArgumentException> {
            RenameSessionTool(rig.store, fixedClock).execute(
                RenameSessionTool.Input(sessionId = "s-1", newTitle = "   "),
                rig.ctx,
            )
        }
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            RenameSessionTool(rig.store, fixedClock).execute(
                RenameSessionTool.Input(sessionId = "ghost", newTitle = "x"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("session_query(select=sessions)"), ex.message)
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val PAST_MS = 1_600_000_000_000L
    }
}
