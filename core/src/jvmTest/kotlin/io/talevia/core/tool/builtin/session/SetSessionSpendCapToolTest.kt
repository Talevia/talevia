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

/**
 * Cycle 143 folded `set_session_spend_cap` into
 * `session_action(action="set_spend_cap")`. This suite continues to
 * pin the three-state cap (null / 0 / positive cents), the no-op
 * semantics, the negative-cap rejection, and the missing-session
 * error — but routes through the unified action dispatcher.
 */
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

    private fun setSpendCapInput(capCents: Long?, sessionId: String? = null) =
        SessionActionTool.Input(
            action = "set_spend_cap",
            sessionId = sessionId,
            capCents = capCents,
        )

    @Test fun setsCapFromNullToValue() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-set")
        val tool = SessionActionTool(sessions)

        val out = tool.execute(
            setSpendCapInput(capCents = 500L),
            ctxFor(sid),
        ).data

        assertNull(out.previousSpendCapCents)
        assertEquals(500L, out.spendCapCents)
        assertEquals(500L, sessions.getSession(sid)!!.spendCapCents)
    }

    @Test fun clearsCapWhenCapCentsIsNull() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-clear")
        sessions.updateSession(
            sessions.getSession(sid)!!.copy(spendCapCents = 100L, updatedAt = now),
        )
        val tool = SessionActionTool(sessions)

        val out = tool.execute(
            setSpendCapInput(capCents = null),
            ctxFor(sid),
        ).data

        assertEquals(100L, out.previousSpendCapCents)
        assertNull(out.spendCapCents)
        assertNull(sessions.getSession(sid)!!.spendCapCents)
    }

    @Test fun zeroCapIsDistinctFromClearedCap() = runTest {
        // Three-state: null (no cap) vs 0 (block all) vs positive. Test the
        // boundary between null and 0 — a bug here would silently lose the
        // "block everything" intent.
        val sessions = freshSessions()
        val sid = sessions.seed("s-zero")
        val tool = SessionActionTool(sessions)

        val out = tool.execute(
            setSpendCapInput(capCents = 0L),
            ctxFor(sid),
        ).data

        assertEquals(0L, out.spendCapCents)
        assertEquals(0L, sessions.getSession(sid)!!.spendCapCents)
    }

    @Test fun noOpWhenCapUnchanged() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-noop")
        sessions.updateSession(
            sessions.getSession(sid)!!.copy(spendCapCents = 250L, updatedAt = now),
        )
        val before = sessions.getSession(sid)!!.updatedAt
        val tool = SessionActionTool(sessions)

        val out = tool.execute(
            setSpendCapInput(capCents = 250L),
            ctxFor(sid),
        ).data

        assertEquals(250L, out.previousSpendCapCents)
        assertEquals(250L, out.spendCapCents)
        assertEquals(before, sessions.getSession(sid)!!.updatedAt, "no-op must not bump updatedAt")
    }

    @Test fun negativeCapIsRejected() = runTest {
        val sessions = freshSessions()
        val sid = sessions.seed("s-neg")
        val tool = SessionActionTool(sessions)

        assertFailsWith<IllegalArgumentException> {
            tool.execute(setSpendCapInput(capCents = -1L), ctxFor(sid))
        }
    }

    @Test fun missingSessionErrorsCleanly() = runTest {
        val sessions = freshSessions()
        val tool = SessionActionTool(sessions)

        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                setSpendCapInput(sessionId = "does-not-exist", capCents = 1L),
                ctxFor(SessionId("does-not-exist")),
            )
        }
        assert("not found" in ex.message.orEmpty())
    }
}
