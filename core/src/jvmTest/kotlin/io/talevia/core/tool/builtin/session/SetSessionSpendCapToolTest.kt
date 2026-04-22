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
import kotlin.test.assertNull

class SetSessionSpendCapToolTest {

    private val now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun freshSessions(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun SqlDelightSessionStore.seed(id: String): SessionId {
        val sid = SessionId(id)
        createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sid
    }

    private fun ctxFor(sid: SessionId): ToolContext = ToolContext(
        sessionId = sid,
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    @Test fun setsCapFromNullToValue() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-set")
        val tool = SetSessionSpendCapTool(sessions)

        val out = tool.execute(
            SetSessionSpendCapTool.Input(capCents = 500L),
            ctxFor(sid),
        ).data

        assertNull(out.previousCapCents)
        assertEquals(500L, out.capCents)
        assertEquals(500L, sessions.getSession(sid)!!.spendCapCents)
    }

    @Test fun clearsCapWhenCapCentsIsNull() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-clear")
        sessions.updateSession(
            sessions.getSession(sid)!!.copy(spendCapCents = 100L, updatedAt = now),
        )
        val tool = SetSessionSpendCapTool(sessions)

        val out = tool.execute(
            SetSessionSpendCapTool.Input(capCents = null),
            ctxFor(sid),
        ).data

        assertEquals(100L, out.previousCapCents)
        assertNull(out.capCents)
        assertNull(sessions.getSession(sid)!!.spendCapCents)
    }

    @Test fun zeroCapIsDistinctFromClearedCap() = runTest {
        // Three-state: null (no cap) vs 0 (block all) vs positive. Test the
        // boundary between null and 0 — a bug here would silently lose the
        // "block everything" intent.
        val sessions = freshSessions()
        val sid = sessions.seed("s-zero")
        val tool = SetSessionSpendCapTool(sessions)

        val out = tool.execute(
            SetSessionSpendCapTool.Input(capCents = 0L),
            ctxFor(sid),
        ).data

        assertEquals(0L, out.capCents)
        assertEquals(0L, sessions.getSession(sid)!!.spendCapCents)
    }

    @Test fun noOpWhenCapUnchanged() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-noop")
        sessions.updateSession(
            sessions.getSession(sid)!!.copy(spendCapCents = 250L, updatedAt = now),
        )
        val before = sessions.getSession(sid)!!.updatedAt
        val tool = SetSessionSpendCapTool(sessions)

        val out = tool.execute(
            SetSessionSpendCapTool.Input(capCents = 250L),
            ctxFor(sid),
        ).data

        assertEquals(250L, out.previousCapCents)
        assertEquals(250L, out.capCents)
        assertEquals(before, sessions.getSession(sid)!!.updatedAt, "no-op must not bump updatedAt")
    }

    @Test fun negativeCapIsRejected() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-neg")
        val tool = SetSessionSpendCapTool(sessions)

        assertFailsWith<IllegalArgumentException> {
            tool.execute(SetSessionSpendCapTool.Input(capCents = -1L), ctxFor(sid))
        }
    }

    @Test fun missingSessionErrorsCleanly() = runTest {
        val sessions = freshSessions()
        val tool = SetSessionSpendCapTool(sessions)

        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SetSessionSpendCapTool.Input(sessionId = "does-not-exist", capCents = 1L),
                ctxFor(SessionId("does-not-exist")),
            )
        }
        assert("not found" in ex.message.orEmpty())
    }
}
