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
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompactorTest {

    @Test
    fun pruneDropsOldCompletedToolOutputsButKeepsRecentTurns() {
        val sid = SessionId("s")
        val now = Instant.fromEpochMilliseconds(0)
        val history = buildHistory(sid, now)

        val compactor = Compactor(
            provider = FakeProvider(emptyList()),
            store = noopStore(),
            bus = EventBus(),
            clock = Clock.System,
            protectUserTurns = 1,         // protect only the latest user turn
            pruneProtectTokens = 100,
        )

        val dropped = compactor.prune(history)
        // Old tool part (oldHugeTool) should be dropped; recent tool part should be kept.
        assertTrue(dropped.contains(PartId("old-tool-out")), "old tool output should be pruned")
        assertTrue(!dropped.contains(PartId("recent-tool-out")), "recent tool output should be retained")
    }

    @Test
    fun pruneDropsBiggestToolOutputFirstWhenBudgetAllowsOneDrop() {
        // Two pre-window completed-tool outputs: a small one (older, ~20 tokens)
        // and a big one (newer, ~600 tokens via 2400-char string + estimatedTokens
        // stamp). Budget of 200 forces exactly one drop. The largest-first policy
        // must pick `big-tool` regardless of order — dropping the small one would
        // leave ~600 tokens over budget and miss the goal.
        val sid = SessionId("s")
        val now = Instant.fromEpochMilliseconds(0)

        val u1 = Message.User(MessageId("u-1"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val a1 = Message.Assistant(
            id = MessageId("a-1"), sessionId = sid, createdAt = now,
            parentId = u1.id, model = ModelRef("fake", "x"),
            finish = FinishReason.TOOL_CALLS,
        )
        val smallTool = Part.Tool(
            id = PartId("small-tool"), messageId = a1.id, sessionId = sid, createdAt = now,
            callId = CallId("c-1"), toolId = "echo",
            state = ToolState.Completed(
                input = JsonObject(mapOf("text" to JsonPrimitive("s"))),
                outputForLlm = "x",
                data = JsonObject(emptyMap()),
            ),
        )
        val bigTool = Part.Tool(
            id = PartId("big-tool"), messageId = a1.id, sessionId = sid, createdAt = now,
            callId = CallId("c-2"), toolId = "echo",
            state = ToolState.Completed(
                input = JsonObject(mapOf("text" to JsonPrimitive("b"))),
                outputForLlm = "x".repeat(2_400),
                data = JsonObject(emptyMap()),
                estimatedTokens = 600,
            ),
        )
        val u2 = Message.User(MessageId("u-2"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val u2Text = Part.Text(PartId("u-2-text"), u2.id, sid, now, text = "q")
        val history = listOf(
            MessageWithParts(u1, listOf(Part.Text(PartId("u-1-text"), u1.id, sid, now, text = "q"))),
            MessageWithParts(a1, listOf(smallTool, bigTool)),
            MessageWithParts(u2, listOf(u2Text)),
        )

        val compactor = Compactor(
            provider = FakeProvider(emptyList()),
            store = noopStore(),
            bus = EventBus(),
            clock = Clock.System,
            protectUserTurns = 1,
            pruneProtectTokens = 200,
        )

        val dropped = compactor.prune(history)
        assertTrue(dropped.contains(PartId("big-tool")), "largest tool output should be dropped first")
        assertTrue(!dropped.contains(PartId("small-tool")), "small tool output should survive")
    }

    @Test
    fun pruneHonoursEstimatedTokensOverrideOverByteHeuristic() {
        // A tool whose `outputForLlm` is tiny (40 chars → ~10-token byte estimate)
        // but that stamped estimatedTokens=5000 (e.g. because the `data` JSON it
        // returned was replayed into a provider-specific balloon). The estimator
        // MUST trust the stamp over the byte count so budget math reflects reality.
        val sid = SessionId("s")
        val now = Instant.fromEpochMilliseconds(0)

        val u1 = Message.User(MessageId("u-1"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val a1 = Message.Assistant(
            id = MessageId("a-1"), sessionId = sid, createdAt = now,
            parentId = u1.id, model = ModelRef("fake", "x"),
            finish = FinishReason.TOOL_CALLS,
        )
        val stampedTool = Part.Tool(
            id = PartId("stamped-tool"), messageId = a1.id, sessionId = sid, createdAt = now,
            callId = CallId("c-1"), toolId = "echo",
            state = ToolState.Completed(
                input = JsonObject(mapOf("text" to JsonPrimitive("tiny"))),
                outputForLlm = "tiny output",
                data = JsonObject(emptyMap()),
                estimatedTokens = 5000,
            ),
        )
        val u2 = Message.User(MessageId("u-2"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val u2Text = Part.Text(PartId("u-2-text"), u2.id, sid, now, text = "q")
        val history = listOf(
            MessageWithParts(u1, listOf(Part.Text(PartId("u-1-text"), u1.id, sid, now, text = "q"))),
            MessageWithParts(a1, listOf(stampedTool)),
            MessageWithParts(u2, listOf(u2Text)),
        )

        val compactor = Compactor(
            provider = FakeProvider(emptyList()),
            store = noopStore(),
            bus = EventBus(),
            clock = Clock.System,
            protectUserTurns = 1,
            pruneProtectTokens = 100,
        )

        // With the stamp, 5000 >> 100 → must drop. Without the stamp, ~15 tokens
        // of byte-heuristic would have kept it in.
        val dropped = compactor.prune(history)
        assertTrue(dropped.contains(PartId("stamped-tool")), "estimatedTokens stamp must drive drop decision")
    }

    @Test
    fun pruneDropsAtLeastOnePartWhenHistoryExceedsDefaultBound() = runTest {
        // Bound-guard runtime test (`debt-add-runtime-test-session-compaction-bounds`).
        // The existing pruning tests above all pass a *lowered* pruneProtectTokens
        // (100 / 200) to force drops. None exercise the path at the production
        // default (40_000). A future refactor that accidentally short-circuits
        // the prune branch only at the default threshold — e.g. `if (fixedTokens
        // < 10_000) return emptySet()` — would still pass all the small-bound
        // tests and fail silently in prod. This test pins the invariant:
        // given history estimated above the default bound, process() must
        // produce Result.Compacted with prunedCount > 0.
        val (store, bus) = inMemoryStore()
        val sid = SessionId("s-bound")
        val now = Clock.System.now()
        store.createSession(Session(sid, ProjectId("p"), title = "bound", createdAt = now, updatedAt = now))

        // 3 user turns (protectUserTurns=2 default → pre-window = [u1, a1]).
        // a1 carries a single completed tool output stamped at 55 000 tokens —
        // well above the 40 000 bound, so pruning is mandatory.
        val u1 = Message.User(MessageId("u-1"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val u1Text = Part.Text(PartId("u-1-text"), u1.id, sid, now, text = "plan a cut")
        val a1 = Message.Assistant(
            id = MessageId("a-1"), sessionId = sid, createdAt = now,
            parentId = u1.id, model = ModelRef("fake", "x"),
            finish = FinishReason.TOOL_CALLS,
        )
        val hugeTool = Part.Tool(
            id = PartId("huge-tool-out"), messageId = a1.id, sessionId = sid, createdAt = now,
            callId = CallId("c-huge"), toolId = "echo",
            state = ToolState.Completed(
                input = JsonObject(mapOf("text" to JsonPrimitive("h"))),
                outputForLlm = "x",
                data = JsonObject(emptyMap()),
                estimatedTokens = 55_000,
            ),
        )
        val u2 = Message.User(MessageId("u-2"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val u2Text = Part.Text(PartId("u-2-text"), u2.id, sid, now, text = "another step")
        val u3 = Message.User(MessageId("u-3"), sid, now, agent = "default", model = ModelRef("fake", "x"))
        val u3Text = Part.Text(PartId("u-3-text"), u3.id, sid, now, text = "and another")
        val history = listOf(
            MessageWithParts(u1, listOf(u1Text)),
            MessageWithParts(a1, listOf(hugeTool)),
            MessageWithParts(u2, listOf(u2Text)),
            MessageWithParts(u3, listOf(u3Text)),
        )
        history.forEach { mwp ->
            store.appendMessage(mwp.message)
            mwp.parts.forEach { store.upsertPart(it) }
        }

        val summaryTurn = listOf(
            LlmEvent.TextStart(PartId("bound-summary")),
            LlmEvent.TextEnd(PartId("bound-summary"), "Goal: verify bound.\n"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 10, output = 5)),
        )
        val compactor = Compactor(
            provider = FakeProvider(listOf(summaryTurn)),
            store = store,
            bus = bus,
            // No pruneProtectTokens override — exercise the production default.
        )

        val result = compactor.process(sid, history, ModelRef("fake", "test"))
        assertTrue(result is Compactor.Result.Compacted, "expected Compacted, got $result")
        val compacted = result as Compactor.Result.Compacted
        assertTrue(
            compacted.prunedCount > 0,
            "default pruneProtectTokens=40_000 bound must drop at least one part " +
                "when history carries a 55_000-token tool output in the pre-window " +
                "(prunedCount=${compacted.prunedCount})",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun publishesSessionCompactedEventOnSuccessfulCompaction() = runTest {
        // compaction-drop-telemetry bullet (Rubric §5.4): when `Compactor.process`
        // returns `Result.Compacted`, it must emit exactly one
        // `BusEvent.SessionCompacted` carrying the prunedCount + summaryLength
        // so CLI / Desktop UIs can surface a "just compacted N outputs" notice.
        // Without this event, the user sees a long pause + a shrunk context with
        // no explanation of what happened. Skipped passes (`Result.Skipped`) must
        // NOT emit — this test uses a forced-drop budget to guarantee the
        // Compacted branch.
        val (store, bus) = inMemoryStore()
        val sid = SessionId("s-compacted")
        val now = Clock.System.now()
        store.createSession(Session(sid, ProjectId("p"), title = "x", createdAt = now, updatedAt = now))
        val history = buildHistory(sid, now)
        history.forEach { mwp ->
            store.appendMessage(mwp.message)
            mwp.parts.forEach { store.upsertPart(it) }
        }

        // Subscribe BEFORE process() runs — SharedFlow w/o replay drops events
        // emitted before the collector resumes (same idiom as AgentCompactionTest).
        val captured = mutableListOf<BusEvent.SessionCompacted>()
        val job = bus.events
            .filterIsInstance<BusEvent.SessionCompacted>()
            .onEach { captured += it }
            .launchIn(this)
        yield()

        val summaryBody = "Goal: cut a 30s clip.\nDiscoveries: fake details for test."
        val summaryTurn = listOf(
            LlmEvent.TextStart(PartId("summary-text")),
            LlmEvent.TextEnd(PartId("summary-text"), summaryBody),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 50, output = 20)),
        )
        val compactor = Compactor(
            provider = FakeProvider(listOf(summaryTurn)),
            store = store,
            bus = bus,
            protectUserTurns = 1,
            pruneProtectTokens = 100,
        )

        val result = compactor.process(sid, history, ModelRef("fake", "test"))
        advanceUntilIdle()
        job.cancel()

        assertTrue(result is Compactor.Result.Compacted, "expected Compacted, got $result")
        val r = result as Compactor.Result.Compacted

        assertEquals(1, captured.size, "expected exactly one SessionCompacted event, got ${captured.size}")
        val ev = captured.single()
        assertEquals(sid, ev.sessionId)
        assertEquals(r.prunedCount, ev.prunedCount, "event prunedCount must match Result.Compacted.prunedCount")
        assertEquals(summaryBody.length, ev.summaryLength, "event summaryLength must match summary body character count")
        assertTrue(ev.prunedCount > 0, "this fixture forces a drop — prunedCount must be > 0")
    }

    @Test
    fun summarisesAndPersistsCompactionPart() = runTest {
        val (store, bus) = inMemoryStore()
        val sid = SessionId("s")
        val now = Clock.System.now()
        store.createSession(Session(sid, ProjectId("p"), title = "x", createdAt = now, updatedAt = now))
        val history = buildHistory(sid, now)
        history.forEach { mwp ->
            store.appendMessage(mwp.message)
            mwp.parts.forEach { store.upsertPart(it) }
        }

        val summaryTurn = listOf(
            LlmEvent.TextStart(PartId("summary-text")),
            LlmEvent.TextEnd(PartId("summary-text"), "Goal: cut a 30s clip.\nDiscoveries: ..."),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 50, output = 20)),
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
        assertTrue(result is Compactor.Result.Compacted, "should produce a compaction result")
        val r = result as Compactor.Result.Compacted
        assertTrue(r.summary.startsWith("Goal:"), "summary should start with Goal section")

        // The CompactionPart is in the store.
        val compactionParts = store.listSessionParts(sid).filterIsInstance<Part.Compaction>()
        assertEquals(1, compactionParts.size)
        assertTrue(compactionParts.single().summary.contains("Discoveries"))
    }

    private fun buildHistory(sid: SessionId, baseTime: Instant): List<MessageWithParts> {
        val u1 = Message.User(MessageId("u-1"), sid, baseTime, agent = "default", model = ModelRef("fake", "x"))
        val a1 = Message.Assistant(
            id = MessageId("a-1"), sessionId = sid, createdAt = baseTime,
            parentId = u1.id, model = ModelRef("fake", "x"),
            finish = FinishReason.TOOL_CALLS,
        )
        val oldHugeTool = Part.Tool(
            id = PartId("old-tool-out"), messageId = a1.id, sessionId = sid, createdAt = baseTime,
            callId = CallId("c-1"), toolId = "echo",
            state = ToolState.Completed(
                input = JsonObject(mapOf("text" to JsonPrimitive("hi"))),
                outputForLlm = "x".repeat(2_000),
                data = JsonObject(emptyMap()),
            ),
        )
        val u2 = Message.User(MessageId("u-2"), sid, baseTime, agent = "default", model = ModelRef("fake", "x"))
        val u2Text = Part.Text(PartId("u-2-text"), u2.id, sid, baseTime, text = "do another thing")
        val a2 = Message.Assistant(
            id = MessageId("a-2"), sessionId = sid, createdAt = baseTime,
            parentId = u2.id, model = ModelRef("fake", "x"),
            finish = FinishReason.TOOL_CALLS,
        )
        val recentTool = Part.Tool(
            id = PartId("recent-tool-out"), messageId = a2.id, sessionId = sid, createdAt = baseTime,
            callId = CallId("c-2"), toolId = "echo",
            state = ToolState.Completed(
                input = buildJsonObject { put("text", "later") },
                outputForLlm = "later",
                data = JsonObject(emptyMap()),
            ),
        )
        return listOf(
            MessageWithParts(u1, listOf(Part.Text(PartId("u-1-text"), u1.id, sid, baseTime, text = "do something"))),
            MessageWithParts(a1, listOf(oldHugeTool)),
            MessageWithParts(u2, listOf(u2Text)),
            MessageWithParts(a2, listOf(recentTool)),
        )
    }

    private fun inMemoryStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private fun noopStore(): SqlDelightSessionStore = inMemoryStore().first
}
