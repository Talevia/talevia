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
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
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

    /**
     * Gated provider: calls to [stream] suspend on [startLatch] until
     * [release] is called, then emit a canned success turn. Lets a test
     * launch a second concurrent `process()` while the first one is
     * parked mid-summary — simulates the "manual `/compact` fires during
     * an auto-compaction pass" race the bullet names.
     */
    private class GatedSummaryProvider(
        private val startLatch: CompletableDeferred<Unit>,
        private val summaryBody: String,
    ) : LlmProvider {
        override val id: String = "gated-fake"
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
            startLatch.await()
            emit(LlmEvent.TextStart(PartId("gated-summary")))
            emit(LlmEvent.TextEnd(PartId("gated-summary"), summaryBody))
            emit(LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 10, output = 10)))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentProcessOnSameSessionWinsOnceAndSkipsOthers() = runTest {
        // Regression guard for debt-compactor-concurrent-process-audit
        // (Rubric §5.6). The prune → summarise → upsertPart triple is not
        // atomic at the SessionStore layer, so two racing passes could
        // double-drop candidates, double-bill the provider summary call, and
        // spawn two `Part.Compaction` entries with overlapping replaced-
        // from/to ranges. The guard: a process() running for the same
        // session serialises the second caller to `Result.Skipped("… already
        // in progress …")` so exactly one summary + part lands.
        val (store, bus) = inMemoryStore()
        val sid = SessionId("s-race")
        val now = Clock.System.now()
        store.createSession(Session(sid, ProjectId("p"), title = "x", createdAt = now, updatedAt = now))
        val history = buildHistory(sid, now)
        history.forEach { mwp ->
            store.appendMessage(mwp.message)
            mwp.parts.forEach { store.upsertPart(it) }
        }

        val gate = CompletableDeferred<Unit>()
        val compactor = Compactor(
            provider = GatedSummaryProvider(gate, "Goal: raced but only one pass landed"),
            store = store,
            bus = bus,
            protectUserTurns = 1,
            pruneProtectTokens = 100,
        )

        // Kick off process1 on the test scope; it will suspend on `gate` in
        // the middle of summarise() because the gated provider parks there.
        val first = async { compactor.process(sid, history, ModelRef("fake", "test")) }
        // Let process1 advance far enough to acquire the inflight guard and
        // hit the provider.stream().collect{} — runTest's single-thread
        // scheduler means one advanceUntilIdle after launch drains every
        // non-suspended step.
        advanceUntilIdle()

        // Now fire the second pass — it must see `sid in inflightSessions`
        // and short-circuit to Skipped without touching the provider.
        val second = async { compactor.process(sid, history, ModelRef("fake", "test")) }
        advanceUntilIdle()

        // Release the gate so first can finish.
        gate.complete(Unit)
        advanceUntilIdle()

        val r1 = first.await()
        val r2 = second.await()

        // Exactly one Compacted result.
        val compactedCount = listOf(r1, r2).count { it is Compactor.Result.Compacted }
        val skippedCount = listOf(r1, r2).count { it is Compactor.Result.Skipped }
        assertEquals(
            1,
            compactedCount,
            "exactly one process() must win Compacted; got r1=$r1, r2=$r2",
        )
        assertEquals(
            1,
            skippedCount,
            "the losing process() must return Skipped; got r1=$r1, r2=$r2",
        )

        // The Skipped reason must name the in-progress state so operators can
        // distinguish "nothing to do" from "another pass holds the lock".
        val loser = listOf(r1, r2).filterIsInstance<Compactor.Result.Skipped>().single()
        assertTrue(
            loser.reason.contains("already in progress"),
            "Skipped reason must name in-progress state; got '${loser.reason}'",
        )

        // Exactly one Part.Compaction persisted — if the guard had failed,
        // both racers would upsertPart two separate Compaction rows.
        val compactionParts = store.listSessionParts(sid).filterIsInstance<Part.Compaction>()
        assertEquals(
            1,
            compactionParts.size,
            "exactly one Part.Compaction must land; got ${compactionParts.size}",
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun inflightGuardReleasesAfterSuccessfulProcess() = runTest {
        // §3a #9 bounded-edge: after a winning pass finishes, the session
        // must clear out of `inflightSessions` so a follow-up compaction
        // (next overflow trigger, some minutes later) isn't permanently
        // skipped as "already in progress". Without the finally-release,
        // the first run would succeed but every subsequent call would skip.
        val (store, bus) = inMemoryStore()
        val sid = SessionId("s-release")
        val now = Clock.System.now()
        store.createSession(Session(sid, ProjectId("p"), title = "x", createdAt = now, updatedAt = now))
        val history = buildHistory(sid, now)
        history.forEach { mwp ->
            store.appendMessage(mwp.message)
            mwp.parts.forEach { store.upsertPart(it) }
        }

        val summaryBody = "Goal: release-after-success check"
        val summaryTurn = listOf(
            LlmEvent.TextStart(PartId("s1")),
            LlmEvent.TextEnd(PartId("s1"), summaryBody),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 10, output = 10)),
        )
        // Second call's provider — the first is already consumed by the
        // first run; the FakeProvider is exhausted-on-reuse so we build a
        // fresh script.
        val summary2 = "Goal: second pass also ran"
        val summaryTurn2 = listOf(
            LlmEvent.TextStart(PartId("s2")),
            LlmEvent.TextEnd(PartId("s2"), summary2),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 10, output = 10)),
        )
        val compactor = Compactor(
            provider = FakeProvider(listOf(summaryTurn, summaryTurn2)),
            store = store,
            bus = bus,
            protectUserTurns = 1,
            pruneProtectTokens = 100,
        )

        val r1 = compactor.process(sid, history, ModelRef("fake", "test"))
        assertTrue(r1 is Compactor.Result.Compacted, "first call must compact; got $r1")

        // Second call after first finishes — guard must be released. Using
        // the same stale `history` is fine for this test: the prune code
        // re-evaluates against the passed-in list, and our fixture has
        // enough drop candidates to produce a second successful pass.
        val r2 = compactor.process(sid, history, ModelRef("fake", "test"))
        assertTrue(
            r2 is Compactor.Result.Compacted || r2 is Compactor.Result.Skipped,
            "second call after release must NOT claim 'already in progress'; got $r2",
        )
        if (r2 is Compactor.Result.Skipped) {
            assertTrue(
                !r2.reason.contains("already in progress"),
                "after release, the in-progress reason must not surface; got '${r2.reason}'",
            )
        }
    }

    private fun inMemoryStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private fun noopStore(): SqlDelightSessionStore = inMemoryStore().first
}
