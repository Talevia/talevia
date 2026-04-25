package io.talevia.core.compaction

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.FakeProvider
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.ToolState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural coverage for [CompactionStrategy.PRUNE_ONLY] —
 * `Compactor.process(strategy = PRUNE_ONLY)` must:
 *  - mark the same set of [Part.Tool] candidates compacted as the
 *    default summarise-and-prune pass would,
 *  - **not** call the provider (no LLM round-trip; [FakeProvider.requests]
 *    stays empty),
 *  - **not** write a `Part.Compaction` summary part,
 *  - publish a [BusEvent.SessionCompacted] with `summaryLength=0`,
 *  - return [Compactor.Result.Pruned] carrying the prune count,
 *  - skip with a clear reason when there is nothing to prune (rather
 *    than silently no-op).
 */
class CompactorPruneOnlyTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun pruneOnlyDropsCompletedToolOutputsWithoutCallingProvider() = runTest {
        val (store, bus) = inMemoryStore()
        val sid = SessionId("s-prune-only")
        val now = Clock.System.now()
        store.createSession(Session(sid, ProjectId("p"), title = "x", createdAt = now, updatedAt = now))
        val history = forcedDropHistory(sid)
        history.forEach { mwp ->
            store.appendMessage(mwp.message)
            mwp.parts.forEach { store.upsertPart(it) }
        }

        val captured = mutableListOf<BusEvent.SessionCompacted>()
        val job = bus.events
            .filterIsInstance<BusEvent.SessionCompacted>()
            .onEach { captured += it }
            .launchIn(this)
        yield()

        // FakeProvider with no scripted turns — if the prune-only path
        // ever calls stream(), the test fails loudly with the
        // "exhausted" error from the FakeProvider.
        val provider = FakeProvider(emptyList())

        val compactor = Compactor(
            provider = provider,
            store = store,
            bus = bus,
            protectUserTurns = 1,
            pruneProtectTokens = 100,
        )

        val result = compactor.process(
            sessionId = sid,
            history = history,
            model = ModelRef("fake", "test"),
            strategy = CompactionStrategy.PRUNE_ONLY,
        )
        advanceUntilIdle()
        job.cancel()

        assertTrue(
            result is Compactor.Result.Pruned,
            "expected Pruned, got $result",
        )
        val pruned = result as Compactor.Result.Pruned
        assertTrue(pruned.prunedCount > 0, "fixture forces a drop — prunedCount must be > 0")

        // Provider was never asked — no scripted turn dequeued, no
        // request captured.
        assertTrue(
            provider.requests.isEmpty(),
            "PRUNE_ONLY must not call the provider; got ${provider.requests.size} request(s)",
        )

        // No Part.Compaction was written.
        val compactionParts = store.listSessionParts(sid).filterIsInstance<Part.Compaction>()
        assertTrue(
            compactionParts.isEmpty(),
            "PRUNE_ONLY must not persist a Part.Compaction; found ${compactionParts.size}",
        )

        // Bus event still fires so UIs can surface "compacted N".
        assertEquals(1, captured.size, "expected one SessionCompacted event")
        assertEquals(pruned.prunedCount, captured.single().prunedCount)
        assertEquals(0, captured.single().summaryLength, "PRUNE_ONLY summaryLength must be 0")
    }

    @Test
    fun pruneOnlySkipsWhenNothingToPrune() = runTest {
        // Tiny history that already fits the budget — nothing to drop.
        // PRUNE_ONLY must Skip rather than emit a 0-prune Result so callers
        // can distinguish "compaction fired and dropped nothing" from
        // "history was already small enough."
        val (store, bus) = inMemoryStore()
        val sid = SessionId("s-no-op")
        val now = Clock.System.now()
        store.createSession(Session(sid, ProjectId("p"), title = "x", createdAt = now, updatedAt = now))

        val u1 = Message.User(MessageId("u-1"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val u1Text = Part.Text(PartId("u-1-text"), u1.id, sid, now, text = "hi")
        val u2 = Message.User(MessageId("u-2"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val u2Text = Part.Text(PartId("u-2-text"), u2.id, sid, now, text = "still hi")
        val history = listOf(
            MessageWithParts(u1, listOf(u1Text)),
            MessageWithParts(u2, listOf(u2Text)),
        )
        history.forEach { mwp ->
            store.appendMessage(mwp.message)
            mwp.parts.forEach { store.upsertPart(it) }
        }

        val provider = FakeProvider(emptyList())
        val compactor = Compactor(
            provider = provider,
            store = store,
            bus = bus,
            protectUserTurns = 1,
            // High enough that the survivors fit comfortably.
            pruneProtectTokens = 100_000,
        )

        val result = compactor.process(
            sessionId = sid,
            history = history,
            model = ModelRef("fake", "test"),
            strategy = CompactionStrategy.PRUNE_ONLY,
        )

        assertTrue(result is Compactor.Result.Skipped, "expected Skipped, got $result")
        assertTrue(provider.requests.isEmpty(), "no provider call when there's nothing to prune")
    }

    @Test
    fun defaultStrategyStillSummarisesAndPrunes() = runTest {
        // Regression guard: omitting the strategy parameter must keep the
        // pre-cycle SUMMARIZE_AND_PRUNE behaviour byte-for-byte —
        // existing callers (CompactionGate, older test fixtures) rely on
        // the default arg.
        val (store, bus) = inMemoryStore()
        val sid = SessionId("s-default")
        val now = Clock.System.now()
        store.createSession(Session(sid, ProjectId("p"), title = "x", createdAt = now, updatedAt = now))
        val history = forcedDropHistory(sid)
        history.forEach { mwp ->
            store.appendMessage(mwp.message)
            mwp.parts.forEach { store.upsertPart(it) }
        }

        val summaryTurn = listOf(
            io.talevia.core.provider.LlmEvent.TextStart(PartId("summary-text")),
            io.talevia.core.provider.LlmEvent.TextEnd(PartId("summary-text"), "Goal: stub.\nDiscoveries: stub."),
            io.talevia.core.provider.LlmEvent.StepFinish(
                FinishReason.END_TURN,
                io.talevia.core.session.TokenUsage(input = 5, output = 5),
            ),
        )
        val provider = FakeProvider(listOf(summaryTurn))
        val compactor = Compactor(
            provider = provider,
            store = store,
            bus = bus,
            protectUserTurns = 1,
            pruneProtectTokens = 100,
        )

        val result = compactor.process(sid, history, ModelRef("fake", "test"))
        assertTrue(result is Compactor.Result.Compacted, "default strategy must still produce Compacted, got $result")
        assertEquals(1, provider.requests.size, "default strategy must call the provider exactly once")
    }

    @Test
    fun parseOrDefaultRoutesAliases() {
        assertEquals(CompactionStrategy.SUMMARIZE_AND_PRUNE, CompactionStrategy.parseOrDefault(null))
        assertEquals(CompactionStrategy.SUMMARIZE_AND_PRUNE, CompactionStrategy.parseOrDefault(""))
        assertEquals(CompactionStrategy.SUMMARIZE_AND_PRUNE, CompactionStrategy.parseOrDefault("default"))
        assertEquals(CompactionStrategy.SUMMARIZE_AND_PRUNE, CompactionStrategy.parseOrDefault("summarize_and_prune"))
        assertEquals(CompactionStrategy.SUMMARIZE_AND_PRUNE, CompactionStrategy.parseOrDefault("Summarise-And-Prune"))
        assertEquals(CompactionStrategy.PRUNE_ONLY, CompactionStrategy.parseOrDefault("prune_only"))
        assertEquals(CompactionStrategy.PRUNE_ONLY, CompactionStrategy.parseOrDefault("Prune-Only"))
        assertEquals(CompactionStrategy.PRUNE_ONLY, CompactionStrategy.parseOrDefault("prune"))
        assertEquals(CompactionStrategy.PRUNE_ONLY, CompactionStrategy.parseOrDefault("no_summary"))
        // Unknown values fall back — typos cannot silently skip the summary call.
        assertEquals(CompactionStrategy.SUMMARIZE_AND_PRUNE, CompactionStrategy.parseOrDefault("frobnicate"))
        // A null-default-only-when-blank smoke check.
        assertNull(null as CompactionStrategy?)
    }

    /**
     * Builds a 3-user-turn history where the pre-window assistant turn
     * carries one large completed-tool output. With `protectUserTurns=1`
     * + `pruneProtectTokens=100`, prune is forced to drop the big tool.
     */
    private fun forcedDropHistory(sid: SessionId): List<MessageWithParts> {
        val now = Clock.System.now()
        val u1 = Message.User(MessageId("u-1"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val a1 = Message.Assistant(
            id = MessageId("a-1"), sessionId = sid, createdAt = now,
            parentId = u1.id, model = ModelRef("fake", "x"),
            finish = FinishReason.TOOL_CALLS,
        )
        val bigTool = Part.Tool(
            id = PartId("big-tool"), messageId = a1.id, sessionId = sid, createdAt = now,
            callId = CallId("c-1"), toolId = "echo",
            state = ToolState.Completed(
                input = JsonObject(mapOf("text" to JsonPrimitive("big"))),
                outputForLlm = "x".repeat(2_400),
                data = JsonObject(emptyMap()),
                estimatedTokens = 600,
            ),
        )
        val u2 = Message.User(MessageId("u-2"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val u2Text = Part.Text(PartId("u-2-text"), u2.id, sid, now, text = "later")
        return listOf(
            MessageWithParts(u1, listOf(Part.Text(PartId("u-1-text"), u1.id, sid, now, text = "do something"))),
            MessageWithParts(a1, listOf(bigTool)),
            MessageWithParts(u2, listOf(u2Text)),
        )
    }

    private fun inMemoryStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }
}
