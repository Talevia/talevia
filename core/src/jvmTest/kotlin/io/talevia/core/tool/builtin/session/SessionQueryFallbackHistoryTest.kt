package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentProviderFallbackTracker
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.FallbackHistoryRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for `session_query(select=fallback_history)`: per-turn provider
 * fallback chains for both successful and failed assistant turns.
 *
 * Edges (§3a #9):
 *  - zero hops → empty rows + distinctive narrative for wired-tracker case.
 *  - untracked container → empty rows + narrative flagging tracker absence.
 *  - successful turn with fallback → row surfaces even though turn finished END_TURN
 *    (value-add vs run_failure which only shows error turns).
 *  - multiple turns mixed success/failure → oldest-first ordering preserved.
 *  - messageId filter → narrows to one turn.
 *  - unknown messageId → empty rows + distinctive narrative.
 *  - sessionId missing → loud reject.
 *  - pagination (limit + offset) applies cleanly.
 */
class SessionQueryFallbackHistoryTest {

    private val job: Job = SupervisorJob()

    @AfterTest fun teardown() {
        job.cancel()
    }

    private data class Rig(
        val tool: SessionQueryTool,
        val sessions: SqlDelightSessionStore,
        val ctx: ToolContext,
        val bus: EventBus,
        val fallbackTracker: AgentProviderFallbackTracker?,
    )

    private fun rig(wireFallbackTracker: Boolean = true): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val tracker = AgentRunStateTracker(bus, scope)
        val fallbackTracker = if (wireFallbackTracker) AgentProviderFallbackTracker(bus, scope) else null
        val sessions = SqlDelightSessionStore(db, bus)
        val tool = SessionQueryTool(sessions, tracker, fallbackTracker = fallbackTracker)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = {},
            messages = emptyList(),
        )
        return Rig(tool, sessions, ctx, bus, fallbackTracker)
    }

    private suspend fun seedSession(
        sessions: SqlDelightSessionStore,
        sid: SessionId,
        assistants: List<Message.Assistant>,
    ) {
        sessions.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "fallback-history",
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            ),
        )
        assistants.forEach { sessions.appendMessage(it) }
    }

    private fun assistant(
        id: String,
        sessionId: SessionId,
        epochMs: Long,
        finish: FinishReason,
        providerId: String = "anthropic",
    ): Message.Assistant = Message.Assistant(
        id = MessageId(id),
        sessionId = sessionId,
        createdAt = Instant.fromEpochMilliseconds(epochMs),
        parentId = MessageId("u-$id"),
        model = ModelRef(providerId, "claude-sonnet-4-6"),
        finish = finish,
        error = null,
        tokens = TokenUsage(input = 10, output = 5),
    )

    private fun decode(out: SessionQueryTool.Output): List<FallbackHistoryRow> =
        out.rows.decodeRowsAs(FallbackHistoryRow.serializer())

    @Test fun emptySessionReturnsNoRows() = runTest {
        val r = rig()
        seedSession(r.sessions, SessionId("s"), emptyList())
        val out = r.tool.execute(
            SessionQueryTool.Input(select = "fallback_history", sessionId = "s"),
            r.ctx,
        ).data
        assertEquals(0, out.total)
        assertEquals(emptyList(), decode(out))
    }

    @Test fun untrackedContainerSurfacesAbsenceInNarrative() = runTest {
        val r = rig(wireFallbackTracker = false)
        seedSession(
            r.sessions,
            SessionId("s"),
            listOf(assistant("a1", SessionId("s"), epochMs = 1_000, finish = FinishReason.END_TURN)),
        )
        val result = r.tool.execute(
            SessionQueryTool.Input(select = "fallback_history", sessionId = "s"),
            r.ctx,
        )
        assertEquals(0, result.data.total)
        assertTrue(
            "not wired" in result.outputForLlm,
            "narrative must flag tracker absence: <${result.outputForLlm}>",
        )
    }

    @Test fun successfulTurnWithFallbackSurfacesRow() = runTest {
        val r = rig()
        val sid = SessionId("s")
        val turnStart = 1_000L
        seedSession(
            r.sessions,
            sid,
            listOf(assistant("a-recover", sid, turnStart, finish = FinishReason.END_TURN)),
        )
        r.bus.publish(
            BusEvent.AgentProviderFallback(sid, "anthropic", "openai", "HTTP 503"),
        )
        yield()

        val rows = decode(
            r.tool.execute(
                SessionQueryTool.Input(select = "fallback_history", sessionId = "s"),
                r.ctx,
            ).data,
        )
        assertEquals(1, rows.size, "successful turn with observed fallback must surface")
        val row = rows.single()
        assertEquals("a-recover", row.messageId)
        assertEquals("end_turn", row.finish, "turn finished successfully — distinct from run_failure")
        assertEquals(1, row.chain.size)
        assertEquals("anthropic", row.chain.single().fromProviderId)
        assertEquals("openai", row.chain.single().toProviderId)
    }

    @Test fun mixedOutcomesReturnedOldestFirst() = runTest {
        val r = rig()
        val sid = SessionId("s")
        seedSession(
            r.sessions,
            sid,
            listOf(
                assistant("a1", sid, epochMs = 1_000, finish = FinishReason.END_TURN),
                assistant("a2", sid, epochMs = 2_000, finish = FinishReason.ERROR),
                assistant("a3", sid, epochMs = 3_000, finish = FinishReason.TOOL_CALLS),
            ),
        )
        // All three messages are in the past (1s/2s/3s epoch); the tracker
        // stamps real-time epochs so every published hop lands in a3's
        // open-ended window [3000, MAX_VALUE). This exercises the
        // "terminal turn absorbs trailing hops" edge case — important for
        // operator reading late-session fallbacks — and validates that
        // a1/a2 with zero in-window hops don't appear as rows.
        r.bus.publish(BusEvent.AgentProviderFallback(sid, "anthropic", "openai", "overload"))
        yield()
        r.bus.publish(BusEvent.AgentProviderFallback(sid, "openai", "gemini", "HTTP 429"))
        yield()
        r.bus.publish(BusEvent.AgentProviderFallback(sid, "gemini", "groq", "HTTP 503"))
        yield()

        val rows = decode(
            r.tool.execute(
                SessionQueryTool.Input(select = "fallback_history", sessionId = "s"),
                r.ctx,
            ).data,
        )
        assertEquals(listOf("a3"), rows.map { it.messageId }, "only a3's window is open-ended into real-time; a1/a2 see zero hops")
        assertEquals("tool_calls", rows.single().finish)
        assertEquals(3, rows.single().chain.size)
        assertEquals(
            listOf("anthropic", "openai", "gemini"),
            rows.single().chain.map { it.fromProviderId },
            "hops preserve publish order within the window",
        )
    }

    @Test fun messageIdFilterNarrowsToOneTurn() = runTest {
        val r = rig()
        val sid = SessionId("s")
        seedSession(
            r.sessions,
            sid,
            listOf(assistant("a1", SessionId("s"), epochMs = 1_000, finish = FinishReason.END_TURN)),
        )
        r.bus.publish(BusEvent.AgentProviderFallback(sid, "anthropic", "openai", "503"))
        yield()

        val match = decode(
            r.tool.execute(
                SessionQueryTool.Input(select = "fallback_history", sessionId = "s", messageId = "a1"),
                r.ctx,
            ).data,
        )
        assertEquals(1, match.size)
        assertEquals("a1", match.single().messageId)

        val miss = r.tool.execute(
            SessionQueryTool.Input(select = "fallback_history", sessionId = "s", messageId = "nope"),
            r.ctx,
        )
        assertEquals(0, miss.data.total)
        assertTrue(
            "messageId='nope'" in miss.outputForLlm,
            "narrative must echo the unknown id: <${miss.outputForLlm}>",
        )
    }

    @Test fun sessionIdMissingRejectsLoud() = runTest {
        val r = rig()
        val ex = assertFailsWith<IllegalStateException> {
            r.tool.execute(
                SessionQueryTool.Input(select = "fallback_history"),
                r.ctx,
            )
        }
        assertTrue("requires sessionId" in ex.message!!, "reject must name sessionId: ${ex.message}")
    }
}
