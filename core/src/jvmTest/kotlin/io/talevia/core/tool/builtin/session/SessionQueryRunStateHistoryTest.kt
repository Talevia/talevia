package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.SqlDelightProjectStore
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Covers `select=run_state_history` on [SessionQueryTool]. Drives
 * transitions through the [EventBus] (the real production signal path)
 * and asserts the tracker's ring buffer + SessionQueryTool dispatcher
 * return them in order, honor `sinceEpochMs`, and cap at the
 * per-session history limit.
 */
class SessionQueryRunStateHistoryTest {

    private val now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun fixture(
        sessionIdValue: String = "s-hist",
        trackerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ): Triple<SqlDelightSessionStore, AgentRunStateTracker, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(TaleviaDb(driver), bus)
        sessions.createSession(
            Session(
                id = SessionId(sessionIdValue),
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val tracker = AgentRunStateTracker(bus, trackerScope)
        // Give the tracker's background collector one tick to subscribe
        // before tests start publishing; otherwise the first event can
        // race and miss the subscription window.
        delay(50.milliseconds)
        return Triple(sessions, tracker, bus)
    }

    private fun tool(
        sessions: SqlDelightSessionStore,
        tracker: AgentRunStateTracker,
    ): SessionQueryTool {
        val projects = SqlDelightProjectStore(
            TaleviaDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }),
        )
        return SessionQueryTool(sessions, tracker, projects)
    }

    private fun rows(out: SessionQueryTool.Output): List<SessionQueryTool.RunStateTransitionRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(SessionQueryTool.RunStateTransitionRow.serializer()),
            out.rows,
        )

    @Test fun missingSessionIdRejected() = runTest {
        val (sessions, tracker) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            tool(sessions, tracker).execute(
                SessionQueryTool.Input(select = "run_state_history"),
                ctx(),
            )
        }
        assertTrue("sessionId" in ex.message.orEmpty())
    }

    @Test fun missingSessionErrors() = runTest {
        val (sessions, tracker) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            tool(sessions, tracker).execute(
                SessionQueryTool.Input(select = "run_state_history", sessionId = "ghost"),
                ctx(),
            )
        }
        assertTrue("not found" in ex.message.orEmpty())
    }

    @Test fun sessionWithNoTransitionsReturnsEmptyRows() = runTest {
        val (sessions, tracker) = fixture()
        val out = tool(sessions, tracker).execute(
            SessionQueryTool.Input(select = "run_state_history", sessionId = "s-hist"),
            ctx(),
        ).data
        assertEquals(0, out.total)
        assertEquals(0, out.returned)
        assertTrue(rows(out).isEmpty())
    }

    @Test fun multipleTransitionsReturnedOldestFirst() = runTest {
        val (sessions, tracker, bus) = fixture()
        val sid = SessionId("s-hist")
        // Publish in sequence. The Idle / Generating / Failed triad is
        // representative of a failed turn. Use runBlocking + await so the
        // async bus-collector runs against wall-clock time (runTest's
        // virtual clock doesn't drive Dispatchers.Default).
        kotlinx.coroutines.runBlocking {
            bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating))
            bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.AwaitingTool))
            bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating))
            bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Failed("boom")))
            awaitHistorySize(tracker, sid, target = 4)
        }

        val out = tool(sessions, tracker).execute(
            SessionQueryTool.Input(select = "run_state_history", sessionId = sid.value),
            ctx(),
        ).data
        val r = rows(out)
        assertEquals(4, r.size)
        assertEquals(listOf("generating", "awaiting_tool", "generating", "failed"), r.map { it.state })
        assertEquals("boom", r.last().cause, "Failed transition must carry the cause string")
        // epochMs monotonically non-decreasing.
        val times = r.map { it.epochMs }
        assertEquals(times.sorted(), times)
    }

    @Test fun sinceEpochMsFiltersOutOlderTransitions() = runTest {
        // Drive a scripted clock — each call to `now()` returns the next
        // planned timestamp. Real-time sleeps between publishes let the
        // async collector drain in order, so timestamps map 1:1 to events.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(TaleviaDb(driver), bus)
        val sid = SessionId("s-since")
        sessions.createSession(Session(sid, ProjectId("p"), "t", createdAt = now, updatedAt = now))
        val plannedTimestamps = ArrayDeque(listOf(1_000L, 2_000L, 3_000L))
        val clock = object : kotlinx.datetime.Clock {
            override fun now(): kotlinx.datetime.Instant =
                kotlinx.datetime.Instant.fromEpochMilliseconds(
                    plannedTimestamps.removeFirstOrNull() ?: 9_999L,
                )
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tracker = AgentRunStateTracker(bus, scope, clock = clock)

        kotlinx.coroutines.runBlocking {
            delay(80.milliseconds) // subscribe.
            bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating))
            // Wait for the first entry to be collected before issuing the next
            // publish — guarantees the scripted clock advances in order.
            awaitHistorySize(tracker, sid, target = 1)
            bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.AwaitingTool))
            awaitHistorySize(tracker, sid, target = 2)
            bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Compacting))
            awaitHistorySize(tracker, sid, target = 3)
        }

        val out = SessionQueryTool(
            sessions,
            tracker,
            SqlDelightProjectStore(
                TaleviaDb(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }),
            ),
        ).execute(
            SessionQueryTool.Input(
                select = "run_state_history",
                sessionId = sid.value,
                sinceEpochMs = 1_500L, // between entry 1 (1000) and entry 2 (2000)
            ),
            ctx(),
        ).data
        val r = rows(out)
        assertEquals(2, r.size, "entries with epochMs < 1500 must be filtered out")
        assertEquals(listOf("awaiting_tool", "compacting"), r.map { it.state })
    }

    private suspend fun awaitHistorySize(
        tracker: AgentRunStateTracker,
        sid: SessionId,
        target: Int,
        timeoutMs: Long = 2_000L,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (tracker.history(sid).size < target && System.currentTimeMillis() < deadline) {
            delay(20.milliseconds)
        }
        check(tracker.history(sid).size >= target) {
            "awaited history size $target, got ${tracker.history(sid).size} after ${timeoutMs}ms"
        }
    }

    @Test fun historyIsCappedPerSession() = runTest {
        // Flood past the cap. Use a real-time runBlocking for the collector to
        // consume — runTest's virtual clock doesn't advance Dispatchers.Default.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(TaleviaDb(driver), bus)
        val sid = SessionId("s-cap")
        sessions.createSession(Session(sid, ProjectId("p"), "t", createdAt = now, updatedAt = now))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tracker = AgentRunStateTracker(bus, scope, historyCap = 4)

        kotlinx.coroutines.runBlocking {
            delay(80.milliseconds) // let the collector attach.
            repeat(10) { bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating)) }
            // Poll for the final state with a generous real-time budget.
            val deadline = System.currentTimeMillis() + 2000L
            while (tracker.history(sid).size < 4 && System.currentTimeMillis() < deadline) {
                delay(50.milliseconds)
            }
        }
        val history = tracker.history(sid)
        assertEquals(4, history.size, "cap=4 must truncate; got ${history.size}")
    }

    @Test fun sinceEpochMsRejectedOnOtherSelects() = runTest {
        val (sessions, tracker) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            tool(sessions, tracker).execute(
                SessionQueryTool.Input(
                    select = "status",
                    sessionId = "s-hist",
                    sinceEpochMs = 0L,
                ),
                ctx(),
            )
        }
        assertTrue("sinceEpochMs" in ex.message.orEmpty())
    }
}
