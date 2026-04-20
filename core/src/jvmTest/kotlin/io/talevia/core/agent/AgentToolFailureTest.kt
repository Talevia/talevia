package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Companion to [AgentLoopTest]: when the LLM emits a tool call with an invalid
 * input shape, the registered tool's serializer throws inside
 * [io.talevia.core.tool.RegisteredTool.dispatch]. The Agent must catch that
 * failure, persist it as a [ToolState.Failed] part, and keep the run alive so
 * the assistant message still finalises cleanly — the LLM should be able to
 * see the failure on the next turn and adjust.
 */
class AgentToolFailureTest {

    @Test
    fun missingRequiredFieldPersistedAsFailed() = runTest {
        val toolPart = runWithMalformedInput(
            input = buildJsonObject { /* echo requires "text" */ },
        )
        val state = toolPart.state
        assertTrue(state is ToolState.Failed, "expected Failed, got $state")
        assertTrue(
            (state as ToolState.Failed).message.contains("text", ignoreCase = true),
            "failure message should reference the missing field, got: ${state.message}",
        )
    }

    @Test
    fun wrongTypeForFieldPersistedAsFailed() = runTest {
        val toolPart = runWithMalformedInput(
            input = buildJsonObject { put("text", 1234) },
        )
        assertTrue(toolPart.state is ToolState.Failed)
    }

    @Test
    fun jsonArrayInsteadOfObjectPersistedAsFailed() = runTest {
        val toolPart = runWithMalformedInput(
            input = JsonArray(listOf(JsonPrimitive("ping"))),
        )
        assertTrue(toolPart.state is ToolState.Failed)
    }

    @Test
    fun jsonNullInputPersistedAsFailed() = runTest {
        val toolPart = runWithMalformedInput(input = JsonNull)
        assertTrue(toolPart.state is ToolState.Failed)
    }

    private suspend fun runWithMalformedInput(input: kotlinx.serialization.json.JsonElement): Part.Tool {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)
        val registry = ToolRegistry().apply { register(EchoTool()) }

        val partId = PartId("tool-bad")
        val callId = CallId("call-bad")
        val turn = listOf(
            LlmEvent.ToolCallStart(partId, callId, "echo"),
            LlmEvent.ToolCallReady(partId, callId, "echo", input),
            // No second turn scripted — agent must stop here even though dispatch failed.
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 5, output = 1)),
        )
        val provider = FakeProvider(listOf(turn))

        val agent = Agent(
            provider = provider,
            registry = registry,
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )
        val asst = agent.run(RunInput(sessionId, "echo bad", ModelRef("fake", "test")))

        // Agent run finalised cleanly even though the tool dispatch failed.
        assertEquals(FinishReason.END_TURN, asst.finish)
        return store.listSessionParts(sessionId).filterIsInstance<Part.Tool>().single()
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("test-session-bad-input")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("proj"),
                title = "test",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sid
    }
}
