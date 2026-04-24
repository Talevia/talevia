package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.ActiveRunSummaryRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for `session_query(select=active_run_summary)` — composes
 * running stats for the latest turn from existing state without adding
 * any. Edges exercised (§3a #9): never-ran session, mid-run turn with
 * tool parts + tokens, terminal Failed carrying cause, compactions
 * counted only within the current run arc.
 */
class SessionQueryActiveRunSummaryTest {

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

    private suspend fun seedTurn(
        sessions: SqlDelightSessionStore,
        sid: SessionId,
        createdAt: Instant,
        tokens: TokenUsage = TokenUsage.ZERO,
        finish: FinishReason? = null,
        toolParts: Int = 0,
    ): MessageId {
        sessions.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "t",
                createdAt = createdAt,
                updatedAt = createdAt,
            ),
        )
        sessions.appendMessage(
            Message.User(
                id = MessageId("u-${sid.value}"),
                sessionId = sid,
                createdAt = createdAt,
                agent = "default",
                model = ModelRef("anthropic", "claude-opus-4-7"),
            ),
        )
        val aid = MessageId("a-${sid.value}")
        sessions.appendMessage(
            Message.Assistant(
                id = aid,
                sessionId = sid,
                createdAt = createdAt,
                parentId = MessageId("u-${sid.value}"),
                model = ModelRef("anthropic", "claude-opus-4-7"),
                tokens = tokens,
                finish = finish,
            ),
        )
        repeat(toolParts) { i ->
            sessions.upsertPart(
                Part.Tool(
                    id = PartId("t-${sid.value}-$i"),
                    messageId = aid,
                    sessionId = sid,
                    createdAt = createdAt,
                    callId = CallId("c-${sid.value}-$i"),
                    toolId = "no_op_tool",
                    state = io.talevia.core.session.ToolState.Pending,
                ),
            )
        }
        return aid
    }

    @Test fun neverRanSessionCollapsesToZero() = runTest {
        val rig = rig()
        val out = rig.tool.execute(
            SessionQueryTool.Input(select = "active_run_summary", sessionId = "s-never"),
            rig.ctx,
        ).data
        assertEquals(SessionQueryTool.SELECT_ACTIVE_RUN_SUMMARY, out.select)
        val row = out.rows.decodeRowsAs(ActiveRunSummaryRow.serializer()).single()
        assertEquals("s-never", row.sessionId)
        assertEquals("idle", row.state)
        assertTrue(row.neverRan)
        assertNull(row.runStartedAtEpochMs)
        assertNull(row.elapsedMs)
        assertEquals(0, row.toolCallCount)
        assertEquals(0L, row.tokensIn)
        assertEquals(0L, row.tokensOut)
        assertEquals(0, row.compactionsInRun)
        assertNull(row.latestAssistantMessageId)
        rig.scopeJob.cancel()
    }

    @Test fun midRunReportsTokensAndToolCalls() = runTest {
        val rig = rig()
        val sid = SessionId("s-mid")
        val runStart = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val aid = seedTurn(
            rig.sessions, sid, runStart,
            tokens = TokenUsage(input = 1200, output = 340, reasoning = 80, cacheRead = 900, cacheWrite = 300),
            toolParts = 3,
        )
        rig.bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating))
        yield()

        val out = rig.tool.execute(
            SessionQueryTool.Input(select = "active_run_summary", sessionId = sid.value),
            rig.ctx,
        ).data
        val row = out.rows.decodeRowsAs(ActiveRunSummaryRow.serializer()).single()
        assertEquals("generating", row.state)
        assertEquals(false, row.neverRan)
        assertEquals(runStart.toEpochMilliseconds(), row.runStartedAtEpochMs)
        val elapsed = row.elapsedMs
        assertNotNull(elapsed)
        assertTrue(elapsed >= 0L, "elapsedMs must be non-negative; got $elapsed")
        assertEquals(3, row.toolCallCount)
        assertEquals(1200L, row.tokensIn)
        assertEquals(340L, row.tokensOut)
        assertEquals(80L, row.reasoningTokens)
        assertEquals(900L, row.cacheReadTokens)
        assertEquals(300L, row.cacheWriteTokens)
        assertEquals(aid.value, row.latestAssistantMessageId)
        rig.scopeJob.cancel()
    }

    @Test fun failedStateCarriesCause() = runTest {
        val rig = rig()
        val sid = SessionId("s-fail")
        seedTurn(rig.sessions, sid, Instant.fromEpochMilliseconds(1_700_000_000_000L))
        rig.bus.publish(
            BusEvent.AgentRunStateChanged(sid, AgentRunState.Failed("provider 503")),
        )
        yield()

        val out = rig.tool.execute(
            SessionQueryTool.Input(select = "active_run_summary", sessionId = sid.value),
            rig.ctx,
        ).data
        val row = out.rows.decodeRowsAs(ActiveRunSummaryRow.serializer()).single()
        assertEquals("failed", row.state)
        assertEquals("provider 503", row.cause)
        rig.scopeJob.cancel()
    }

    @Test fun compactionsCountedWithinRunArc() = runTest {
        // Two Compacting transitions published AFTER the run started (runStart
        // = latest assistant message's createdAt) must show up as
        // compactionsInRun = 2. Transitions published BEFORE the run don't
        // count — the filter is `since = runStartedAtEpochMs`.
        val rig = rig()
        val sid = SessionId("s-comp")
        val runStart = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        seedTurn(rig.sessions, sid, runStart)
        rig.bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating))
        yield()
        rig.bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Compacting))
        yield()
        rig.bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating))
        yield()
        rig.bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Compacting))
        yield()

        val out = rig.tool.execute(
            SessionQueryTool.Input(select = "active_run_summary", sessionId = sid.value),
            rig.ctx,
        ).data
        val row = out.rows.decodeRowsAs(ActiveRunSummaryRow.serializer()).single()
        assertEquals(2, row.compactionsInRun)
        rig.scopeJob.cancel()
    }

    @Test fun missingSessionIdFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SessionQueryTool.Input(select = "active_run_summary"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("requires sessionId"), ex.message)
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
            tool.execute(
                SessionQueryTool.Input(select = "active_run_summary", sessionId = "s-1"),
                ctx,
            )
        }
        assertTrue(ex.message!!.contains("AgentRunStateTracker"), ex.message)
    }
}
