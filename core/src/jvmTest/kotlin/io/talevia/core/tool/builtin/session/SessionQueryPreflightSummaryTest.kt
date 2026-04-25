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
import io.talevia.core.permission.PermissionHistoryRecorder
import io.talevia.core.agent.AgentRunState
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.PreflightSummaryRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for `session_query(select=preflight_summary)` — the
 * single-row "what's my situation?" snapshot that consolidates four
 * other selects (context_pressure / fallback_history /
 * cancellation_history / permission_history pending count) into one
 * plan-time read.
 */
class SessionQueryPreflightSummaryTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun newDb(): TaleviaDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return TaleviaDb(driver)
    }

    private suspend fun primeSession(store: SqlDelightSessionStore, sid: SessionId) {
        val now = Clock.System.now()
        store.createSession(
            Session(id = sid, projectId = ProjectId("p"), title = "t", createdAt = now, updatedAt = now),
        )
    }

    @Test fun emptySessionReturnsCleanZeroRow(): TestResult = runTest {
        val db = newDb()
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)
        val sid = SessionId("empty")
        primeSession(store, sid)

        val tool = SessionQueryTool(sessions = store)
        val out = tool.execute(
            SessionQueryTool.Input(select = "preflight_summary", sessionId = sid.value),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(PreflightSummaryRow.serializer()).single()
        assertEquals(0, row.contextEstimate)
        assertEquals(false, row.contextOverThreshold)
        assertNull(row.runState, "no agent has run yet")
        assertEquals(0, row.fallbackHopCount)
        assertNull(row.lastCancelledMessageId)
        assertEquals(0, row.pendingPermissionAskCount)
    }

    @Test fun fallbackHopAndPermissionAskAggregateInRow(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val db = newDb()
            val bus = EventBus()
            val store = SqlDelightSessionStore(db, bus)
            val sid = SessionId("loaded")
            primeSession(store, sid)

            // Long-lived aggregators wired off the same bus the tool reads.
            val agentStates = AgentRunStateTracker(
                bus,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
            val fallbackTracker = AgentProviderFallbackTracker(
                bus,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
            val permissionHistory = PermissionHistoryRecorder(
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
            permissionHistory.awaitReady()

            // Publish: state transition + fallback + pending permission ask.
            bus.publish(BusEvent.AgentRunStateChanged(sid, AgentRunState.Generating, retryAttempt = 2))
            bus.publish(
                BusEvent.AgentProviderFallback(
                    sessionId = sid,
                    fromProviderId = "anthropic",
                    toProviderId = "openai",
                    reason = "503",
                ),
            )
            bus.publish(BusEvent.PermissionAsked(sid, "rid-1", "fs.write", listOf("/tmp/*")))

            withTimeout(5.seconds) {
                while (
                    agentStates.currentState(sid) == null ||
                    fallbackTracker.hops(sid).isEmpty() ||
                    permissionHistory.snapshot(sid.value).isEmpty()
                    ) yield()
            }

            val tool = SessionQueryTool(
                sessions = store,
                agentStates = agentStates,
                fallbackTracker = fallbackTracker,
                permissionHistory = permissionHistory,
            )
            val out = tool.execute(
                SessionQueryTool.Input(select = "preflight_summary", sessionId = sid.value),
                ctx(),
            ).data
            val row = out.rows.decodeRowsAs(PreflightSummaryRow.serializer()).single()
            assertEquals("Generating", row.runState)
            assertEquals(2, row.lastRetryAttempt)
            assertEquals(1, row.fallbackHopCount)
            assertEquals("anthropic", row.lastFallbackFromProviderId)
            assertEquals("openai", row.lastFallbackToProviderId)
            assertNotNull(row.lastFallbackEpochMs)
            assertEquals(1, row.pendingPermissionAskCount, "ask not yet replied — still pending")
        }
    }

    @Test fun lastCancelledTurnPopulated(): TestResult = runTest {
        val db = newDb()
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)
        val sid = SessionId("cancelled")
        primeSession(store, sid)

        val now = Clock.System.now()
        // User parent first so the assistant has a parentId to reference.
        val user = Message.User(
            id = MessageId("usr-1"),
            sessionId = sid,
            createdAt = now,
            agent = "default",
            model = ModelRef("p", "m"),
        )
        store.appendMessage(user)
        val asst = Message.Assistant(
            id = MessageId("ast-1"),
            sessionId = sid,
            createdAt = now,
            parentId = user.id,
            model = ModelRef("p", "m"),
            tokens = TokenUsage.ZERO,
            finish = FinishReason.CANCELLED,
        )
        store.appendMessage(asst)

        val tool = SessionQueryTool(sessions = store)
        val out = tool.execute(
            SessionQueryTool.Input(select = "preflight_summary", sessionId = sid.value),
            ctx(),
        ).data
        val row = out.rows.decodeRowsAs(PreflightSummaryRow.serializer()).single()
        assertEquals("ast-1", row.lastCancelledMessageId)
        assertNotNull(row.lastCancelledEpochMs)
    }

    @Test fun missingSessionFailsLoud(): TestResult = runTest {
        val db = newDb()
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)
        val tool = SessionQueryTool(sessions = store)
        val ex = kotlin.runCatching {
            tool.execute(
                SessionQueryTool.Input(select = "preflight_summary", sessionId = "nope"),
                ctx(),
            )
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue("error must name the session: ${ex.message}") {
            ex.message?.contains("Session nope not found") == true
        }
    }
}
