package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.metrics.MetricsRegistry
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentLoopTest {

    @Test
    fun plainTextTurnStops() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val partId = PartId("text-1")
        val turn = listOf(
            LlmEvent.TextStart(partId),
            LlmEvent.TextDelta(partId, "Hello "),
            LlmEvent.TextDelta(partId, "world."),
            LlmEvent.TextEnd(partId, "Hello world."),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 10, output = 3)),
        )
        val provider = FakeProvider(listOf(turn))
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )

        val asst = agent.run(
            RunInput(sessionId, "hi", ModelRef("fake", "test")),
        )

        assertEquals(FinishReason.END_TURN, asst.finish)
        assertEquals(10L, asst.tokens.input)

        val parts = store.listSessionParts(sessionId)
        val texts = parts.filterIsInstance<Part.Text>()
        assertTrue(texts.any { it.text == "hi" }, "user prompt persisted")
        assertTrue(texts.any { it.text == "Hello world." }, "assistant text persisted")
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun toolCallDispatchedAndResultThreadedBack() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val registry = ToolRegistry().apply { register(EchoTool()) }

        val toolPartId = PartId("tool-part")
        val callId = io.talevia.core.CallId("call-1")
        val turn1 = listOf(
            LlmEvent.ToolCallStart(toolPartId, callId, "echo"),
            LlmEvent.ToolCallReady(toolPartId, callId, "echo", buildJsonObject { put("text", "ping") }),
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 8, output = 2)),
        )
        val replyPartId = PartId("text-2")
        val turn2 = listOf(
            LlmEvent.TextStart(replyPartId),
            LlmEvent.TextEnd(replyPartId, "got ping"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 12, output = 5)),
        )
        val provider = FakeProvider(listOf(turn1, turn2))

        val agent = Agent(
            provider = provider,
            registry = registry,
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )

        val asst = agent.run(
            RunInput(sessionId, "say ping", ModelRef("fake", "test")),
        )

        assertEquals(FinishReason.END_TURN, asst.finish)
        assertEquals(2, provider.requests.size, "agent should loop twice when first turn is tool_calls")

        val parts = store.listSessionParts(sessionId)
        val toolPart = parts.filterIsInstance<Part.Tool>().single()
        val state = toolPart.state
        assertTrue(state is ToolState.Completed, "echo tool should complete")
        assertEquals("ping", (state as ToolState.Completed).outputForLlm)

        val finalText = parts.filterIsInstance<Part.Text>().last { it.text.isNotEmpty() }
        assertEquals("got ping", finalText.text)
    }

    @Test
    fun unknownToolFails() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val toolPartId = PartId("tool-x")
        val callId = io.talevia.core.CallId("call-x")
        val turn = listOf(
            LlmEvent.ToolCallStart(toolPartId, callId, "ghost"),
            LlmEvent.ToolCallReady(toolPartId, callId, "ghost", buildJsonObject {}),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage()),
        )
        val provider = FakeProvider(listOf(turn))

        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )

        agent.run(RunInput(sessionId, "ghost call", ModelRef("fake", "test")))

        val toolPart = store.listSessionParts(sessionId).filterIsInstance<Part.Tool>().single()
        val state = toolPart.state
        assertTrue(state is ToolState.Failed, "missing tool should produce a Failed state")
        assertTrue((state as ToolState.Failed).message.contains("Unknown tool"))
    }

    @Test
    fun stepFinishUsageIsRecordedInMetricsPerProvider() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val turn1 = listOf(
            LlmEvent.StepFinish(
                FinishReason.END_TURN,
                TokenUsage(input = 100, output = 20, cacheRead = 80, cacheWrite = 5),
            ),
        )
        val turn2 = listOf(
            LlmEvent.StepFinish(
                FinishReason.END_TURN,
                TokenUsage(input = 50, output = 10, cacheRead = 40),
            ),
        )
        val provider = FakeProvider(listOf(turn1))
        val metrics = MetricsRegistry()
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            metrics = metrics,
        )

        agent.run(RunInput(sessionId, "first", ModelRef("fake", "test")))
        // Second run within the same session — counters should accumulate.
        val provider2 = FakeProvider(listOf(turn2))
        val agent2 = Agent(
            provider = provider2,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            metrics = metrics,
        )
        agent2.run(RunInput(sessionId, "second", ModelRef("fake", "test")))

        assertEquals(150L, metrics.get("provider.fake.tokens.input"))
        assertEquals(30L, metrics.get("provider.fake.tokens.output"))
        assertEquals(120L, metrics.get("provider.fake.tokens.cache_read"))
        assertEquals(5L, metrics.get("provider.fake.tokens.cache_write"))
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("test-session")
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
