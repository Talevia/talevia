package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentCompactionTest {

    @Test
    fun compactorFiresWhenHistoryExceedsThreshold() = runTest {
        val (store, bus) = newStore()
        val sid = primeSession(store)
        val now = Clock.System.now()

        // Seed an old, heavy tool output so the first request crosses the threshold.
        val mUser = Message.User(
            id = MessageId("u-old"), sessionId = sid, createdAt = now,
            agent = "default", model = ModelRef("fake", "x"),
        )
        store.appendMessage(mUser)
        store.upsertPart(Part.Text(PartId("u-old-text"), mUser.id, sid, now, text = "old prompt"))
        val mAsst = Message.Assistant(
            id = MessageId("a-old"), sessionId = sid, createdAt = now,
            parentId = mUser.id, model = ModelRef("fake", "x"),
            finish = FinishReason.TOOL_CALLS,
        )
        store.appendMessage(mAsst)
        store.upsertPart(
            Part.Tool(
                id = PartId("heavy-tool"), messageId = mAsst.id, sessionId = sid, createdAt = now,
                callId = CallId("c-old"), toolId = "echo",
                state = ToolState.Completed(
                    input = JsonObject(emptyMap()),
                    outputForLlm = "x".repeat(2_000),
                    data = JsonObject(emptyMap()),
                ),
            ),
        )

        val agentTurn = listOf(
            LlmEvent.TextStart(PartId("reply")),
            LlmEvent.TextEnd(PartId("reply"), "ok"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 5, output = 1)),
        )
        val summaryTurn = listOf(
            LlmEvent.TextStart(PartId("sum")),
            LlmEvent.TextEnd(PartId("sum"), "Goal: …\nDiscoveries: …"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 10, output = 5)),
        )
        // The Compactor.process call lands first (summariser turn); then the Agent's own turn.
        val provider = FakeProvider(listOf(summaryTurn, agentTurn))

        val compactor = Compactor(
            provider = provider,
            store = store,
            bus = bus,
            protectUserTurns = 1,
            pruneProtectTokens = 50,
        )
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            compactor = compactor,
            // Threshold below the seeded history so compaction definitely fires.
            compactionThreshold = { 100 },
        )

        val asst = agent.run(RunInput(sid, "continue please", ModelRef("fake", "test")))
        assertEquals(FinishReason.END_TURN, asst.finish)

        val all = store.listSessionParts(sid, includeCompacted = true)
        val compactions = all.filterIsInstance<Part.Compaction>()
        assertEquals(1, compactions.size, "a CompactionPart should have been produced")
        assertTrue(compactions.single().summary.startsWith("Goal:"))

        val heavy = all.filterIsInstance<Part.Tool>().single { it.id == PartId("heavy-tool") }
        assertTrue(heavy.compactedAt != null, "heavy tool output should be marked compacted")

        val active = store.listSessionParts(sid, includeCompacted = false)
        assertTrue(active.none { it.id == PartId("heavy-tool") }, "pruned parts excluded from active view")
    }

    /**
     * Overflow-auto trigger visibility: when the Agent notices the history is
     * above the configured token threshold and calls Compactor.process, it
     * must also publish [BusEvent.SessionCompactionAuto] *before* the summarise
     * call kicks off, so UI subscribers can render "compacting…" rather than
     * leaving the next turn looking stuck. Without this event the user has no
     * signal distinguishing "agent is slow" from "agent is compacting".
     */
    @Test
    fun autoCompactionPublishesSessionCompactionAutoEvent() = runTest {
        val (store, bus) = newStore()
        val sid = primeSession(store)
        val now = Clock.System.now()

        // Same seed as the round-trip test — enough heavy history to cross the
        // 100-token threshold TokenEstimator estimates on the surviving parts.
        val mUser = Message.User(
            id = MessageId("u-auto"), sessionId = sid, createdAt = now,
            agent = "default", model = ModelRef("fake", "x"),
        )
        store.appendMessage(mUser)
        store.upsertPart(Part.Text(PartId("u-auto-text"), mUser.id, sid, now, text = "old prompt"))
        val mAsst = Message.Assistant(
            id = MessageId("a-auto"), sessionId = sid, createdAt = now,
            parentId = mUser.id, model = ModelRef("fake", "x"),
            finish = FinishReason.TOOL_CALLS,
        )
        store.appendMessage(mAsst)
        store.upsertPart(
            Part.Tool(
                id = PartId("heavy-auto"), messageId = mAsst.id, sessionId = sid, createdAt = now,
                callId = CallId("c-auto"), toolId = "echo",
                state = ToolState.Completed(
                    input = JsonObject(emptyMap()),
                    outputForLlm = "x".repeat(2_000),
                    data = JsonObject(emptyMap()),
                ),
            ),
        )

        val summaryTurn = listOf(
            LlmEvent.TextStart(PartId("sum-auto")),
            LlmEvent.TextEnd(PartId("sum-auto"), "Goal: …"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 10, output = 5)),
        )
        val agentTurn = listOf(
            LlmEvent.TextStart(PartId("reply-auto")),
            LlmEvent.TextEnd(PartId("reply-auto"), "ok"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 5, output = 1)),
        )
        val provider = FakeProvider(listOf(summaryTurn, agentTurn))

        val compactor = Compactor(
            provider = provider,
            store = store,
            bus = bus,
            protectUserTurns = 1,
            pruneProtectTokens = 50,
        )

        // Capture SessionCompactionAuto events published while the Agent runs.
        // Subscribe BEFORE calling agent.run so the collector is hot — SharedFlow
        // without replay drops events emitted before the collector is active.
        val captured = mutableListOf<BusEvent.SessionCompactionAuto>()
        val job = bus.events
            .filterIsInstance<BusEvent.SessionCompactionAuto>()
            .onEach { captured += it }
            .launchIn(this)
        // Ensure the collector is scheduled and actively collecting before the
        // publisher runs; without this the StandardTestDispatcher can put the
        // agent's publish ahead of the collector's first resumption.
        yield()

        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            compactor = compactor,
            compactionThreshold = { 100 },
        )

        agent.run(RunInput(sid, "continue please", ModelRef("fake", "test")))
        advanceUntilIdle()
        job.cancel()

        assertEquals(
            1, captured.size,
            "auto-compaction must emit exactly one SessionCompactionAuto event per trigger pass",
        )
        val evt = captured.single()
        assertEquals(sid, evt.sessionId)
        assertEquals(100, evt.thresholdTokens)
        assertTrue(
            evt.historyTokensBefore > evt.thresholdTokens,
            "event must report a historyTokensBefore that actually crossed the threshold",
        )
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("compact-session")
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
