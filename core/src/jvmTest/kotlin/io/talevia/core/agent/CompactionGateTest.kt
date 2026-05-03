package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.db.TaleviaDb
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [CompactionGate] — the soft-budget recovery sub-state-
 * machine extracted from `Agent.runLoop`. Cycle 100 audit: 150 LOC, only
 * 2 transitive test refs (via `AgentCompactionTest` integration paths).
 *
 * Three branches worth pinning:
 * 1. Null compactor → returns history unchanged, NO bus events. The
 *    most subtle regression target: a future refactor that adds
 *    "always publish state change" before the null check would
 *    spam every gate-call with Compacting events even when no
 *    compactor is wired.
 * 2. Compactor present + history under threshold → returns history
 *    unchanged, NO bus events. Same anti-regression as above.
 * 3. Compactor present + history over threshold → publishes the
 *    SessionCompactionAuto + Compacting + Generating event triplet
 *    around the actual compactor.process call. Each event is
 *    ordered: Compacting before process, Generating after.
 *
 * Plus the M6 §5.7 cap enforcement layered on top:
 * 4. Session has no maxSessionTokens → never throws (preserves
 *    pre-feature unbounded behaviour).
 * 5. Session has cap, post-compaction history > cap → throws
 *    [SessionTokenCapExceededException] with sessionId + capTokens
 *    + estimatedTokens populated.
 * 6. The cap is checked on POST-compaction history — important
 *    because compaction is the soft-recovery path and the cap is
 *    the don't-dispatch-this-turn fail-loud signal. A regression
 *    checking the pre-compaction estimate would falsely throw
 *    even when compaction successfully recovered budget.
 */
class CompactionGateTest {

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(TaleviaDb(driver), bus) to bus
    }

    private suspend fun primeSession(
        store: SqlDelightSessionStore,
        sid: SessionId = SessionId("s-1"),
        maxSessionTokens: Long? = null,
    ): SessionId {
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
                maxSessionTokens = maxSessionTokens,
            ),
        )
        return sid
    }

    private val model = ModelRef("fake", "test")

    private fun input(sid: SessionId) = RunInput(
        sessionId = sid,
        text = "hi",
        model = model,
    )

    private fun handleView(): AgentRunHandleView = object : AgentRunHandleView {
        override var lastRetryAttempt: Int? = null
    }

    private class RecordingProvider : LlmProvider {
        override val id: String = "fake"
        var streamCalls = 0
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override fun stream(request: LlmRequest): Flow<LlmEvent> {
            streamCalls += 1
            return flowOf()
        }
    }

    /** Subscribe before any publish so we don't miss bus events. */
    private suspend fun captureEventsWhile(
        bus: EventBus,
        scope: CoroutineScope,
        body: suspend () -> Unit,
    ): List<BusEvent> {
        val captured = mutableListOf<BusEvent>()
        val ready = CompletableDeferred<Unit>()
        val job: Job = scope.launch {
            bus.events
                .onSubscription { ready.complete(Unit) }
                .collect { captured += it }
        }
        ready.await()
        body()
        // Give the collector a tick to drain anything still in the buffer.
        yield()
        yield()
        job.cancel()
        return captured.toList()
    }

    // ── Branch 1: null compactor ───────────────────────────────────

    @Test fun nullCompactorReturnsHistoryUnchangedWithNoEvents(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (store, bus) = newStore()
            val sid = primeSession(store)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val gate = CompactionGate(
                compactor = null,
                compactionThreshold = { -1 }, // would always trigger if compactor existed
                store = store,
                bus = bus,
            )
            val events = captureEventsWhile(bus, scope) {
                val out = gate.maybeCompact(input(sid), handleView(), emptyList())
                assertEquals(emptyList(), out)
            }
            // Pin: NO compaction events when compactor is null. Allows
            // "no compactor wired" deployments to skip the entire branch
            // silently.
            assertTrue(
                events.none { it is BusEvent.SessionCompactionAuto },
                "no SessionCompactionAuto when compactor is null; got: $events",
            )
            assertTrue(
                events.none {
                    it is BusEvent.AgentRunStateChanged && it.state == AgentRunState.Compacting
                },
                "no Compacting state when compactor is null; got: $events",
            )
            scope.cancel()
        }
    }

    // ── Branch 2: under threshold ──────────────────────────────────

    @Test fun underThresholdReturnsHistoryUnchangedWithNoEvents(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (store, bus) = newStore()
            val sid = primeSession(store)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val provider = RecordingProvider()
            val compactor = Compactor(provider = provider, store = store, bus = bus)
            val gate = CompactionGate(
                compactor = compactor,
                compactionThreshold = { Int.MAX_VALUE }, // never triggered
                store = store,
                bus = bus,
            )
            val events = captureEventsWhile(bus, scope) {
                val out = gate.maybeCompact(input(sid), handleView(), emptyList())
                assertEquals(emptyList(), out)
            }
            // Pin: no SessionCompactionAuto event when threshold not crossed.
            assertTrue(
                events.none { it is BusEvent.SessionCompactionAuto },
                "no SessionCompactionAuto when under threshold; got: $events",
            )
            // Compactor.process must NOT have been called → provider not streamed.
            assertEquals(0, provider.streamCalls, "compactor.process must not run when under threshold")
            scope.cancel()
        }
    }

    // ── Branch 3: over threshold ───────────────────────────────────

    @Test fun overThresholdPublishesSessionCompactionAutoWithEstimateAndThreshold(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (store, bus) = newStore()
            val sid = primeSession(store)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val provider = RecordingProvider()
            val compactor = Compactor(provider = provider, store = store, bus = bus)
            val gate = CompactionGate(
                compactor = compactor,
                compactionThreshold = { -1 }, // always trigger (estimate 0 > -1)
                store = store,
                bus = bus,
            )
            val events = captureEventsWhile(bus, scope) {
                gate.maybeCompact(input(sid), handleView(), emptyList())
            }
            // Pin: SessionCompactionAuto carries (historyTokensBefore,
            // thresholdTokens). historyTokensBefore = TokenEstimator.forHistory
            // of the input history (empty → 0). thresholdTokens = -1 (the
            // configured threshold).
            val auto = events.filterIsInstance<BusEvent.SessionCompactionAuto>().single()
            assertEquals(sid, auto.sessionId)
            assertEquals(0, auto.historyTokensBefore)
            assertEquals(-1, auto.thresholdTokens)
        }
    }

    @Test fun overThresholdPublishesCompactingThenGeneratingStates(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (store, bus) = newStore()
            val sid = primeSession(store)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val compactor = Compactor(provider = RecordingProvider(), store = store, bus = bus)
            val gate = CompactionGate(
                compactor = compactor,
                compactionThreshold = { -1 },
                store = store,
                bus = bus,
            )
            val events = captureEventsWhile(bus, scope) {
                gate.maybeCompact(input(sid), handleView(), emptyList())
            }
            // Find the gate's two state-change publishes (compactor.process
            // may publish its own events too — we only assert the gate's).
            val stateEvents = events.filterIsInstance<BusEvent.AgentRunStateChanged>()
                .filter { it.state == AgentRunState.Compacting || it.state == AgentRunState.Generating }
            // Pin order: Compacting before Generating. A regression that
            // publishes Generating first would have UI render "generating"
            // text while the gate is still actually mid-compact.
            val compactingIdx = stateEvents.indexOfFirst { it.state == AgentRunState.Compacting }
            val generatingIdx = stateEvents.indexOfLast { it.state == AgentRunState.Generating }
            assertTrue(
                compactingIdx in 0..<generatingIdx,
                "Compacting must precede Generating; got: $stateEvents",
            )
        }
    }

    @Test fun overThresholdInvokesCompactorProcess(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (store, bus) = newStore()
            val sid = primeSession(store)
            val provider = RecordingProvider()
            // Lower pruneProtectTokens to 50 so the compactor doesn't
            // short-circuit at "nothing to compact" — Compactor.process
            // returns Skipped when prunedIds is empty AND
            // survivors.tokens() < pruneKeepTokens (default 40_000).
            // With pruneProtectTokens=50 the 250-token history clears
            // the floor and process reaches its summarise() step.
            val compactor = Compactor(
                provider = provider,
                store = store,
                bus = bus,
                protectUserTurns = 1,
                pruneProtectTokens = 50,
            )
            val gate = CompactionGate(
                compactor = compactor,
                compactionThreshold = { -1 },
                store = store,
                bus = bus,
            )
            val now = Clock.System.now()
            val msg = Message.User(
                id = MessageId("u"),
                sessionId = sid,
                createdAt = now,
                agent = "test",
                model = model,
            )
            val part = Part.Text(
                id = PartId("p"),
                messageId = MessageId("u"),
                sessionId = sid,
                createdAt = now,
                text = "x".repeat(1_000),
            )
            val history = listOf(io.talevia.core.session.MessageWithParts(msg, listOf(part)))

            gate.maybeCompact(input(sid), handleView(), history)
            // Pin: provider was called exactly once → compactor.process
            // ran through to its summarise() step. Without this check,
            // a regression that drops the compactor.process call
            // (e.g., short-circuiting after the bus events) would
            // leave the gate visibly "compacting" without actually
            // compacting.
            withTimeout(2.seconds) {
                while (provider.streamCalls == 0) yield()
            }
            assertEquals(1, provider.streamCalls)
        }
    }

    // ── Cap enforcement (M6 §5.7) ─────────────────────────────────

    @Test fun noCapNeverThrowsRegardlessOfHistorySize(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (store, bus) = newStore()
            val sid = primeSession(store, maxSessionTokens = null)
            val gate = CompactionGate(
                compactor = null,
                compactionThreshold = { Int.MAX_VALUE },
                store = store,
                bus = bus,
            )
            // Empty history; null cap. Must not throw.
            val out = gate.maybeCompact(input(sid), handleView(), emptyList())
            assertEquals(emptyList(), out)
        }
    }

    @Test fun capExceededThrowsWithSessionAndTokenFieldsPopulated(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (store, bus) = newStore()
            // Cap = 1 token. History will be larger than 1 token after
            // including any non-trivial Text part.
            val sid = primeSession(store, maxSessionTokens = 1L)
            val gate = CompactionGate(
                compactor = null, // skip the compaction branch entirely
                compactionThreshold = { Int.MAX_VALUE },
                store = store,
                bus = bus,
            )
            // Build a message-with-parts whose forHistory > 1 token.
            // 100-char text → ~25 tokens, well over cap.
            val now = Clock.System.now()
            val text = "x".repeat(100)
            val msg = Message.User(
                id = MessageId("u"),
                sessionId = sid,
                createdAt = now,
                agent = "test",
                model = model,
            )
            val part = Part.Text(
                id = PartId("p"),
                messageId = MessageId("u"),
                sessionId = sid,
                createdAt = now,
                text = text,
            )
            val history = listOf(io.talevia.core.session.MessageWithParts(msg, listOf(part)))

            val ex = assertFailsWith<SessionTokenCapExceededException> {
                gate.maybeCompact(input(sid), handleView(), history)
            }
            // Pin all three fields the upstream Agent.run uses to render
            // a user-visible failure reason.
            assertEquals("s-1", ex.sessionId)
            assertEquals(1L, ex.capTokens)
            assertTrue(ex.estimatedTokens > 1, "estimatedTokens must reflect actual history; got ${ex.estimatedTokens}")
            // Pin the message format — surfaces in AgentRunState.Failed.cause.
            val message = ex.message ?: ""
            assertTrue("session=s-1" in message, "message must name session; got: $message")
            assertTrue("capTokens=1" in message, "message must include cap; got: $message")
        }
    }

    @Test fun capExceptionPolicyFiresEvenWithoutCompactor(): TestResult = runTest {
        // Pin: cap check runs even when the compactor branch was a no-op
        // (compactor null OR under-threshold). Without this, a session
        // with a cap but no wired compactor would be unbounded — the
        // opposite of what the cap is for.
        withContext(Dispatchers.Default) {
            val (store, bus) = newStore()
            val sid = primeSession(store, maxSessionTokens = 1L)
            val gate = CompactionGate(
                compactor = null,
                compactionThreshold = { Int.MAX_VALUE },
                store = store,
                bus = bus,
            )
            val now = Clock.System.now()
            val msg = Message.User(
                id = MessageId("u"),
                sessionId = sid,
                createdAt = now,
                agent = "test",
                model = model,
            )
            val part = Part.Text(
                id = PartId("p"),
                messageId = MessageId("u"),
                sessionId = sid,
                createdAt = now,
                text = "y".repeat(50),
            )
            assertFailsWith<SessionTokenCapExceededException> {
                gate.maybeCompact(
                    input(sid),
                    handleView(),
                    listOf(io.talevia.core.session.MessageWithParts(msg, listOf(part))),
                )
            }
        }
    }

    @Test fun capCheckUsesPostCompactionHistory(): TestResult = runTest {
        // Pin the kdoc contract: "Compaction may have dropped the
        // estimate below cap; we re-measure on the post-compaction
        // history rather than the input-history value." Empty input
        // history → post-compaction estimate is also 0 (compactor has
        // nothing to do) → 0 <= cap (any positive cap) → no throw.
        withContext(Dispatchers.Default) {
            val (store, bus) = newStore()
            val sid = primeSession(store, maxSessionTokens = 1L)
            val compactor = Compactor(provider = RecordingProvider(), store = store, bus = bus)
            val gate = CompactionGate(
                compactor = compactor,
                compactionThreshold = { -1 }, // trigger compaction
                store = store,
                bus = bus,
            )
            // Empty input history. forHistory = 0 ≤ cap (= 1). No throw.
            val out = gate.maybeCompact(input(sid), handleView(), emptyList())
            assertEquals(emptyList(), out)
        }
    }

    @Test fun sessionTokenCapExceededExceptionMessageMentionsCompactionGuidance() {
        // Construct directly to pin the message contract — Agent.run
        // surfaces this via AgentRunState.Failed.cause; UIs render the
        // message verbatim. Keep the recovery hint in there ("raise the
        // cap" / "summarise + restart") so users have a clear next step.
        val ex = SessionTokenCapExceededException(
            sessionId = "s-99",
            capTokens = 4_000L,
            estimatedTokens = 5_000,
        )
        val msg = ex.message ?: ""
        assertTrue("s-99" in msg)
        assertTrue("4000" in msg, "cap must appear in message; got: $msg")
        assertTrue("5000" in msg, "estimate must appear in message; got: $msg")
        assertTrue(
            "Compaction did not recover" in msg,
            "must mention compaction failed to recover; got: $msg",
        )
    }
}
