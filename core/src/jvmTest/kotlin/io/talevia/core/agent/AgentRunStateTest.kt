package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Agent.run must publish explicit [BusEvent.AgentRunStateChanged] transitions
 * so UI / SSE subscribers can render a coarse status bar without polling.
 *
 * Coverage in this file:
 *  - plain text turn: Generating → Idle
 *  - tool-using turn: Generating → AwaitingTool → Generating → Idle
 *  - terminal Failed state on a thrown error (via a bogus session id)
 *
 * The compaction edge (Generating → Compacting → Generating) is covered by
 * [AgentCompactionTest.autoCompactionPublishesSessionCompactionAutoEvent]
 * which already exercises that path — no need to duplicate the seed.
 */
class AgentRunStateTest {

    @Test
    fun plainTurnEmitsGeneratingThenIdle() = runTest {
        val (store, bus) = newStore()
        val sid = primeSession(store)

        val (captured, collectorJob) = collectRunStates(bus, this)
        yield()

        val turn = listOf(
            LlmEvent.TextStart(PartId("t-1")),
            LlmEvent.TextEnd(PartId("t-1"), "hi"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 5, output = 2)),
        )
        val provider = FakeProvider(listOf(turn))
        val agent = newAgent(provider, store, bus)

        agent.run(RunInput(sid, "hello", ModelRef("fake", "m")))
        advanceUntilIdle()
        collectorJob.cancel()

        val states = captured.map { it.state }
        assertEquals(
            listOf(AgentRunState.Generating, AgentRunState.Idle),
            states,
            "plain-text turn must emit exactly Generating→Idle with no tool/compaction detour",
        )
        assertTrue(captured.all { it.sessionId == sid })
    }

    @Test
    fun toolTurnEmitsGeneratingAwaitingToolGeneratingIdle() = runTest {
        val (store, bus) = newStore()
        val sid = primeSession(store)

        val (captured, collectorJob) = collectRunStates(bus, this)
        yield()

        // First turn: model asks to call echo, then stops. Second turn (after
        // tool result is appended): model replies in plain text.
        val call = LlmEvent.ToolCallReady(
            partId = PartId("tc-1"),
            callId = io.talevia.core.CallId("c-echo"),
            toolId = "echo",
            input = buildJsonObject { put("text", "hi") },
        )
        val turn1 = listOf(
            LlmEvent.ToolCallStart(PartId("tc-1"), io.talevia.core.CallId("c-echo"), "echo"),
            call,
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 10, output = 1)),
        )
        val turn2 = listOf(
            LlmEvent.TextStart(PartId("reply")),
            LlmEvent.TextEnd(PartId("reply"), "done"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 4, output = 2)),
        )
        val provider = FakeProvider(listOf(turn1, turn2))
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val agent = newAgent(provider, store, bus, registry = registry)

        agent.run(RunInput(sid, "please echo", ModelRef("fake", "m")))
        advanceUntilIdle()
        collectorJob.cancel()

        val states = captured.map { it.state }
        // Expected: start (Generating) → AwaitingTool → back to Generating after
        // tool dispatch → terminal Idle. Exact sequence verifies tool-completion
        // didn't skip its "back to Generating" edge.
        assertEquals(
            listOf(
                AgentRunState.Generating,
                AgentRunState.AwaitingTool,
                AgentRunState.Generating,
                AgentRunState.Idle,
            ),
            states,
        )
    }

    @Test
    fun missingSessionProducesFailedTerminalState() = runTest {
        val (store, bus) = newStore()
        // No primeSession — run() will throw because the session doesn't exist.

        val (captured, collectorJob) = collectRunStates(bus, this)
        yield()

        val provider = FakeProvider(emptyList()) // never invoked
        val agent = newAgent(provider, store, bus)

        val ghostSession = SessionId("does-not-exist")
        runCatching {
            agent.run(RunInput(ghostSession, "hi", ModelRef("fake", "m")))
        }
        advanceUntilIdle()
        collectorJob.cancel()

        // The inflight check + runLoop precondition lets run() publish
        // Generating first, then the runLoop throws IllegalStateException
        // (session not found); our new `catch (Throwable)` arm in run()
        // publishes Failed before rethrowing.
        val states = captured.map { it.state }
        assertTrue(
            states.contains(AgentRunState.Generating),
            "run must publish Generating before throwing (got $states)",
        )
        val terminal = states.last()
        assertTrue(
            terminal is AgentRunState.Failed,
            "terminal state must be Failed on a thrown error (got $terminal, full: $states)",
        )
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun collectRunStates(
        bus: EventBus,
        scope: kotlinx.coroutines.CoroutineScope,
    ): Pair<MutableList<BusEvent.AgentRunStateChanged>, kotlinx.coroutines.Job> {
        val list = mutableListOf<BusEvent.AgentRunStateChanged>()
        val job = bus.events
            .filterIsInstance<BusEvent.AgentRunStateChanged>()
            .onEach { list += it }
            .launchIn(scope)
        return list to job
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("runstate-session")
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

    private fun newAgent(
        provider: FakeProvider,
        store: SqlDelightSessionStore,
        bus: EventBus,
        registry: ToolRegistry = ToolRegistry(),
    ): Agent = Agent(
        provider = provider,
        registry = registry,
        store = store,
        permissions = AllowAllPermissionService(),
        bus = bus,
    )
}
