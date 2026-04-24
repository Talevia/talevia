package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
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
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.RunFailureRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for `session_query(select=run_failure)`: post-mortem aggregation
 * of failed assistant turns from persisted Message / Part state.
 *
 * Edges (§3a #9):
 *  - zero failures → empty rows, narrative flags clean session.
 *  - multiple failures → oldest-first, narrative names most recent.
 *  - messageId scope → single row matching filter.
 *  - messageId scope matching successful turn → empty + distinctive narrative.
 *  - sessionId missing → loud reject.
 *  - messageId on unrelated select still rejected.
 *  - step-finish-error aggregation captures per-step token spend.
 */
class SessionQueryRunFailureTest {

    private data class Rig(
        val tool: SessionQueryTool,
        val sessions: SqlDelightSessionStore,
        val ctx: ToolContext,
        val scopeJob: Job,
    )

    private data class RigWithBus(
        val rig: Rig,
        val bus: EventBus,
        val fallbackTracker: AgentProviderFallbackTracker,
    )

    private fun rig(): Rig = rigWithBus().rig

    private fun rigWithBus(): RigWithBus {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Unconfined)
        val tracker = AgentRunStateTracker(bus, scope)
        val fallbackTracker = AgentProviderFallbackTracker(bus, scope)
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
        return RigWithBus(Rig(tool, sessions, ctx, job), bus, fallbackTracker)
    }

    private suspend fun seedSessionWithAssistants(
        sessions: SqlDelightSessionStore,
        sid: SessionId,
        assistants: List<Message.Assistant>,
    ) {
        sessions.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "test",
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            ),
        )
        // User messages aren't required for this query — the query filters
        // to Message.Assistant — so we only seed what's needed.
        assistants.forEach { sessions.appendMessage(it) }
    }

    @Test fun emptySessionReturnsNoFailureRows() = runTest {
        val rig = rig()
        seedSessionWithAssistants(rig.sessions, SessionId("s-empty"), emptyList())
        val result = rig.tool.execute(
            SessionQueryTool.Input(select = "run_failure", sessionId = "s-empty"),
            rig.ctx,
        )
        assertEquals(0, result.data.total)
        assertTrue(
            "no failed assistant turns" in result.outputForLlm,
            "narrative must flag clean session: <${result.outputForLlm}>",
        )
    }

    @Test fun failedTurnSurfacesTerminalCauseAndStepFinishErrors() = runTest {
        val rig = rig()
        val sid = SessionId("s-fail")
        val base = Instant.fromEpochMilliseconds(1000)
        val msg = Message.Assistant(
            id = MessageId("m-fail-1"),
            sessionId = sid,
            createdAt = base,
            parentId = MessageId("user-1"),
            model = ModelRef("anthropic", "claude-sonnet-4-6"),
            finish = FinishReason.ERROR,
            error = "HTTP 500 overloaded_error",
            tokens = TokenUsage(input = 100, output = 20),
        )
        seedSessionWithAssistants(rig.sessions, sid, listOf(msg))
        // Two StepFinish parts under this message, the second with ERROR.
        rig.sessions.upsertPart(
            Part.StepFinish(
                id = PartId("sf-1"),
                messageId = msg.id,
                sessionId = sid,
                createdAt = base,
                tokens = TokenUsage(input = 50, output = 10),
                finish = FinishReason.TOOL_CALLS,
            ),
        )
        rig.sessions.upsertPart(
            Part.StepFinish(
                id = PartId("sf-2"),
                messageId = msg.id,
                sessionId = sid,
                createdAt = base.plus(kotlin.time.Duration.parse("1s")),
                tokens = TokenUsage(input = 100, output = 20),
                finish = FinishReason.ERROR,
            ),
        )

        val result = rig.tool.execute(
            SessionQueryTool.Input(select = "run_failure", sessionId = sid.value),
            rig.ctx,
        )
        assertEquals(1, result.data.total)
        val rows = result.data.rows.decodeRowsAs(RunFailureRow.serializer())
        val row = rows.single()
        assertEquals("m-fail-1", row.messageId)
        assertEquals("anthropic/claude-sonnet-4-6", row.model)
        assertEquals("HTTP 500 overloaded_error", row.terminalCause)
        assertEquals(1, row.stepFinishErrors.size, "only the ERROR step-finish counts")
        val stepErr = row.stepFinishErrors.single()
        assertEquals("sf-2", stepErr.partId)
        assertEquals(100L, stepErr.tokensInput)
        assertEquals(20L, stepErr.tokensOutput)
    }

    @Test fun multipleFailuresReturnOldestFirstInNarrative() = runTest {
        val rig = rig()
        val sid = SessionId("s-multi")
        val a1 = Message.Assistant(
            id = MessageId("m1"),
            sessionId = sid,
            createdAt = Instant.fromEpochMilliseconds(1000),
            parentId = MessageId("u1"),
            model = ModelRef("fake", "x"),
            finish = FinishReason.ERROR,
            error = "first error",
        )
        val a2 = Message.Assistant(
            id = MessageId("m2"),
            sessionId = sid,
            createdAt = Instant.fromEpochMilliseconds(2000),
            parentId = MessageId("u2"),
            model = ModelRef("fake", "x"),
            finish = FinishReason.END_TURN,
            error = null,
        )
        val a3 = Message.Assistant(
            id = MessageId("m3"),
            sessionId = sid,
            createdAt = Instant.fromEpochMilliseconds(3000),
            parentId = MessageId("u3"),
            model = ModelRef("fake", "x"),
            finish = FinishReason.ERROR,
            error = "third error",
        )
        seedSessionWithAssistants(rig.sessions, sid, listOf(a1, a2, a3))

        val result = rig.tool.execute(
            SessionQueryTool.Input(select = "run_failure", sessionId = sid.value),
            rig.ctx,
        )
        assertEquals(2, result.data.total, "only the two ERROR messages, not the successful one")
        val rows = result.data.rows.decodeRowsAs(RunFailureRow.serializer())
        assertEquals(
            listOf("m1", "m3"),
            rows.map { it.messageId },
            "oldest first (m1 before m3)",
        )
        assertTrue(
            "Most recent: m3" in result.outputForLlm,
            "narrative names most recent failure: <${result.outputForLlm}>",
        )
    }

    @Test fun messageIdScopesToSingleFailure() = runTest {
        val rig = rig()
        val sid = SessionId("s-scope")
        val a1 = Message.Assistant(
            id = MessageId("m1"),
            sessionId = sid,
            createdAt = Instant.fromEpochMilliseconds(1000),
            parentId = MessageId("u1"),
            model = ModelRef("fake", "x"),
            finish = FinishReason.ERROR,
            error = "cause-1",
        )
        val a2 = Message.Assistant(
            id = MessageId("m2"),
            sessionId = sid,
            createdAt = Instant.fromEpochMilliseconds(2000),
            parentId = MessageId("u2"),
            model = ModelRef("fake", "x"),
            finish = FinishReason.ERROR,
            error = "cause-2",
        )
        seedSessionWithAssistants(rig.sessions, sid, listOf(a1, a2))

        val result = rig.tool.execute(
            SessionQueryTool.Input(select = "run_failure", sessionId = sid.value, messageId = "m2"),
            rig.ctx,
        )
        assertEquals(1, result.data.total)
        val row = result.data.rows.decodeRowsAs(RunFailureRow.serializer()).single()
        assertEquals("m2", row.messageId)
        assertEquals("cause-2", row.terminalCause)
    }

    @Test fun messageIdOnSuccessfulTurnReturnsEmptyAndAnnotates() = runTest {
        // §3a #9 bounded-edge: messageId filter that points at a successful
        // assistant turn returns no rows — with a narrative that distinguishes
        // "id unknown / not-a-failure" from "session has no failures at all".
        val rig = rig()
        val sid = SessionId("s-okturn")
        val okMsg = Message.Assistant(
            id = MessageId("m-ok"),
            sessionId = sid,
            createdAt = Instant.fromEpochMilliseconds(1000),
            parentId = MessageId("u"),
            model = ModelRef("fake", "x"),
            finish = FinishReason.END_TURN,
            error = null,
        )
        seedSessionWithAssistants(rig.sessions, sid, listOf(okMsg))

        val result = rig.tool.execute(
            SessionQueryTool.Input(select = "run_failure", sessionId = sid.value, messageId = "m-ok"),
            rig.ctx,
        )
        assertEquals(0, result.data.total)
        assertTrue(
            "No failed assistant turn found" in result.outputForLlm,
            "narrative must distinguish scoped-miss: <${result.outputForLlm}>",
        )
    }

    @Test fun runFailureRequiresSessionId() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SessionQueryTool.Input(select = "run_failure"),
                rig.ctx,
            )
        }
        assertTrue(
            ex.message!!.contains("requires sessionId"),
            "error must name the missing filter: ${ex.message}",
        )
    }

    @Test fun messageIdStillRejectedOnIncompatibleSelect() = runTest {
        // rejectIncompatibleFilters relaxation only opened messageId for
        // SELECT_MESSAGE + SELECT_RUN_FAILURE; other selects still reject.
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            rig.tool.execute(
                SessionQueryTool.Input(
                    select = "sessions",
                    messageId = "m-1",
                ),
                rig.ctx,
            )
        }
        assertTrue(
            ex.message!!.contains("messageId"),
            "reject matrix still names messageId: ${ex.message}",
        )
    }

    @Test fun trackerAbsenceLeavesTerminalKindNull() = runTest {
        // §3a #9 bounded-edge: when no tracker is wired, runStateTerminalKind
        // is null rather than throwing. The primary fields (terminalCause +
        // stepFinishErrors) stay filled.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        // Intentionally no tracker wired.
        val tool = SessionQueryTool(sessions, agentStates = null)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = {},
            messages = emptyList(),
        )

        val sid = SessionId("s-no-tracker")
        val msg = Message.Assistant(
            id = MessageId("m-fail"),
            sessionId = sid,
            createdAt = Instant.fromEpochMilliseconds(1000),
            parentId = MessageId("u"),
            model = ModelRef("fake", "x"),
            finish = FinishReason.ERROR,
            error = "boom",
        )
        seedSessionWithAssistants(sessions, sid, listOf(msg))

        val result = tool.execute(
            SessionQueryTool.Input(select = "run_failure", sessionId = sid.value),
            ctx,
        )
        val row = result.data.rows.decodeRowsAs(RunFailureRow.serializer()).single()
        assertEquals("boom", row.terminalCause)
        assertNull(row.runStateTerminalKind, "no tracker → terminalKind null (not thrown)")
    }

    @Test fun fallbackChainPopulatesWhenTrackerWired() = runTest {
        // A failed turn's wall-clock window captures every provider-
        // fallback hop observed by AgentProviderFallbackTracker between
        // this message's createdAt and the next message's createdAt
        // (cycle-57). Two hops in-window + one out-of-window hop in a
        // separate session — only the two in-window show up.
        val rigB = rigWithBus()
        val rig = rigB.rig
        val sid = SessionId("s-chain")
        val base = Instant.fromEpochMilliseconds(1000)
        val msg = Message.Assistant(
            id = MessageId("m-chain"),
            sessionId = sid,
            createdAt = base,
            parentId = MessageId("u-chain"),
            model = ModelRef("anthropic", "claude-opus-4-7"),
            finish = FinishReason.ERROR,
            error = "primary 503",
        )
        seedSessionWithAssistants(rig.sessions, sid, listOf(msg))

        rigB.bus.publish(
            BusEvent.AgentProviderFallback(sid, "anthropic", "openai", "503"),
        )
        kotlinx.coroutines.yield()
        rigB.bus.publish(
            BusEvent.AgentProviderFallback(sid, "openai", "gemini", "429"),
        )
        kotlinx.coroutines.yield()
        // Fallback on a different session must not leak into this query.
        rigB.bus.publish(
            BusEvent.AgentProviderFallback(
                SessionId("other"),
                "p1",
                "p2",
                "should-not-appear",
            ),
        )
        kotlinx.coroutines.yield()

        val result = rig.tool.execute(
            SessionQueryTool.Input(select = "run_failure", sessionId = sid.value),
            rig.ctx,
        )
        val row = result.data.rows.decodeRowsAs(RunFailureRow.serializer()).single()
        assertEquals(2, row.fallbackChain.size, "both in-window hops should populate chain")
        assertEquals("anthropic", row.fallbackChain[0].fromProviderId)
        assertEquals("openai", row.fallbackChain[0].toProviderId)
        assertEquals("503", row.fallbackChain[0].reason)
        assertEquals("openai", row.fallbackChain[1].fromProviderId)
        assertEquals("gemini", row.fallbackChain[1].toProviderId)
        assertEquals("429", row.fallbackChain[1].reason)
    }

    @Test fun fallbackChainDefaultsEmptyWhenTrackerNotWired() = runTest {
        // Explicit guard: container without a fallback tracker still
        // returns a row (with empty chain), no crash, no null. Mirrors
        // the runStateTerminalKind-null path for unwired AgentRunStateTracker.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val sessions = SqlDelightSessionStore(db, EventBus())
        val tool = SessionQueryTool(sessions, AgentRunStateTracker(EventBus(), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)))
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = {},
            messages = emptyList(),
        )
        val sid = SessionId("s-no-fb-tracker")
        val msg = Message.Assistant(
            id = MessageId("m-fail"),
            sessionId = sid,
            createdAt = Instant.fromEpochMilliseconds(1000),
            parentId = MessageId("u"),
            model = ModelRef("fake", "x"),
            finish = FinishReason.ERROR,
            error = "boom",
        )
        seedSessionWithAssistants(sessions, sid, listOf(msg))

        val result = tool.execute(
            SessionQueryTool.Input(select = "run_failure", sessionId = sid.value),
            ctx,
        )
        val row = result.data.rows.decodeRowsAs(RunFailureRow.serializer()).single()
        assertEquals(emptyList(), row.fallbackChain, "unwired tracker → empty chain (not thrown)")
    }
}
