package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.StatusRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for `session_query(select=status)` + `AgentRunStateTracker`.
 * Covers the reverse-gap cases §3a.9 calls out: no prior run at all
 * (neverRan=true), mid-run Generating, terminal Failed-with-cause.
 */
class SessionQueryStatusTest {

    private data class Rig(
        val bus: EventBus,
        val tracker: AgentRunStateTracker,
        val scopeJob: Job,
        val tool: SessionQueryTool,
        val sessions: SqlDelightSessionStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val tracker = AgentRunStateTracker(bus, scope)
        val sessions = SqlDelightSessionStore(db, bus)
        val tool = SessionQueryTool(sessions, tracker)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = {},
            messages = emptyList(),
        )
        return Rig(bus, tracker, job, tool, sessions, ctx)
    }

    @Test fun neverRanReturnsIdleWithNeverRanFlag() = runTest {
        val rig = rig()
        val out = rig.tool.execute(
            SessionQueryTool.Input(select = "status", sessionId = "s-never"),
            rig.ctx,
        ).data

        assertEquals(SessionQueryTool.SELECT_STATUS, out.select)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(StatusRow.serializer()),
            out.rows,
        )
        assertEquals(1, rows.size)
        assertEquals("s-never", rows[0].sessionId)
        assertEquals("idle", rows[0].state)
        assertTrue(rows[0].neverRan)
        assertNull(rows[0].cause)
        rig.scopeJob.cancel()
    }

    @Test fun generatingStateSnapshotAfterPublish() = runTest {
        val rig = rig()
        rig.bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-1"), AgentRunState.Generating))
        yield() // let the collector run

        val out = rig.tool.execute(
            SessionQueryTool.Input(select = "status", sessionId = "s-1"),
            rig.ctx,
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(StatusRow.serializer()),
            out.rows,
        )
        assertEquals("generating", rows[0].state)
        assertEquals(false, rows[0].neverRan)
        rig.scopeJob.cancel()
    }

    @Test fun failedStateCarriesCause() = runTest {
        val rig = rig()
        rig.bus.publish(
            BusEvent.AgentRunStateChanged(
                SessionId("s-f"),
                AgentRunState.Failed("provider 503"),
            ),
        )
        yield()

        val out = rig.tool.execute(
            SessionQueryTool.Input(select = "status", sessionId = "s-f"),
            rig.ctx,
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(StatusRow.serializer()),
            out.rows,
        )
        assertEquals("failed", rows[0].state)
        assertEquals("provider 503", rows[0].cause)
        rig.scopeJob.cancel()
    }

    @Test fun latestStateOverridesEarlierTransitions() = runTest {
        val rig = rig()
        rig.bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-x"), AgentRunState.Generating))
        yield()
        rig.bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-x"), AgentRunState.AwaitingTool))
        yield()
        rig.bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-x"), AgentRunState.Idle))
        yield()

        val out = rig.tool.execute(
            SessionQueryTool.Input(select = "status", sessionId = "s-x"),
            rig.ctx,
        ).data
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(StatusRow.serializer()),
            out.rows,
        )
        // Post-run Idle: state=idle, neverRan=false (distinguishable from the never-ran case).
        assertEquals("idle", rows[0].state)
        assertEquals(false, rows[0].neverRan)
        rig.scopeJob.cancel()
    }

    @Test fun missingSessionIdFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SessionQueryTool.Input(select = "status"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("requires sessionId"), ex.message)
        rig.scopeJob.cancel()
    }

    @Test fun compactionProgressFieldsPresentWithDefaultThreshold() = runTest {
        // No messages seeded → estimatedTokens = 0, percent = 0.0, but the
        // fields must still be populated so UI knows the threshold contract.
        val rig = rig()
        val out = rig.tool.execute(
            SessionQueryTool.Input(select = "status", sessionId = "s-empty"),
            rig.ctx,
        ).data
        val row = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(StatusRow.serializer()),
            out.rows,
        ).single()
        assertEquals(0, row.estimatedTokens)
        assertEquals(120_000, row.compactionThreshold)
        assertEquals(0f, row.percent)
        rig.scopeJob.cancel()
    }

    @Test fun compactionProgressReflectsRealTokenCount() = runTest {
        // Seed a session with one user + one assistant message and a text part
        // long enough to consume measurable tokens. estimatedTokens > 0, percent > 0,
        // and staying well under the threshold keeps percent < 1 (not clamped).
        val rig = rig()
        val now = kotlinx.datetime.Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val sessionId = SessionId("s-token")
        rig.sessions.createSession(
            io.talevia.core.session.Session(
                id = sessionId,
                projectId = io.talevia.core.ProjectId("p"),
                title = "token-test",
                createdAt = now,
                updatedAt = now,
            ),
        )
        rig.sessions.appendMessage(
            io.talevia.core.session.Message.User(
                id = MessageId("u1"),
                sessionId = sessionId,
                createdAt = now,
                agent = "default",
                model = io.talevia.core.session.ModelRef("anthropic", "claude-opus-4-7"),
            ),
        )
        rig.sessions.appendMessage(
            io.talevia.core.session.Message.Assistant(
                id = MessageId("a1"),
                sessionId = sessionId,
                createdAt = now,
                parentId = MessageId("u1"),
                model = io.talevia.core.session.ModelRef("anthropic", "claude-opus-4-7"),
                tokens = io.talevia.core.session.TokenUsage(input = 10, output = 20),
                finish = io.talevia.core.session.FinishReason.STOP,
            ),
        )
        // A 400-char text payload → TokenEstimator.forText = 100 tokens.
        val longText = "a".repeat(400)
        rig.sessions.upsertPart(
            io.talevia.core.session.Part.Text(
                id = io.talevia.core.PartId("p-text"),
                messageId = MessageId("a1"),
                sessionId = sessionId,
                createdAt = now,
                text = longText,
            ),
        )

        val out = rig.tool.execute(
            SessionQueryTool.Input(select = "status", sessionId = "s-token"),
            rig.ctx,
        ).data
        val row = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(StatusRow.serializer()),
            out.rows,
        ).single()
        // TokenEstimator is a heuristic (~4 chars/token), so we assert shape not exact
        // equality: non-zero, under threshold, percent matches the ratio.
        assertTrue(row.estimatedTokens!! > 0, "expected token count > 0, got ${row.estimatedTokens}")
        assertTrue(row.estimatedTokens!! < 120_000, "test payload should stay well under threshold")
        assertEquals(120_000, row.compactionThreshold)
        val expected = row.estimatedTokens!!.toFloat() / 120_000f
        assertTrue(kotlin.math.abs(row.percent!! - expected) < 0.0001f, "percent=${row.percent} expected=$expected")
        rig.scopeJob.cancel()
    }

    @Test fun noTrackerWiredFailsLoud() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val sessions = SqlDelightSessionStore(db, EventBus())
        val tool = SessionQueryTool(sessions) // agentStates=null
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = {},
            messages = emptyList(),
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(SessionQueryTool.Input(select = "status", sessionId = "s-1"), ctx)
        }
        assertTrue(ex.message!!.contains("AgentRunStateTracker"), ex.message)
    }
}
