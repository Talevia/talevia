package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * M6 §5.7 criterion #2 — `Session.maxSessionTokens` hard cap enforced
 * by [CompactionGate] post-compaction. Three cases pin the contract:
 *
 *   - `null` cap (the default for legacy / unconfigured sessions) →
 *     gate behaves exactly as before; large histories pass through to
 *     the dispatcher unchanged.
 *   - cap + 1 → gate throws [SessionTokenCapExceededException] with
 *     the cap, the estimated post-compaction tokens, and the session
 *     id in the message — fail loud, no provider call goes out.
 *   - cap - 1 → gate returns the history; dispatch proceeds.
 *
 * Tests use a null Compactor so the gate's cap check fires against
 * the input history unchanged. The compaction-then-cap interaction
 * (compactor reduces enough to fit, vs not enough) is the same code
 * path either way: the cap is checked on the post-compaction history,
 * which equals the input when compactor is null.
 */
class CompactionGateTokenCapTest {

    private fun runInput(sessionId: SessionId): RunInput = RunInput(
        sessionId = sessionId,
        text = "go",
        model = ModelRef("fake", "test-model"),
    )

    private object NoOpHandle : AgentRunHandleView {
        override var lastRetryAttempt: Int? = null
    }

    /**
     * Build a SessionStore with a Session + N user-message parts of the
     * given text. Caller picks the text length to control the
     * `TokenEstimator.forHistory` value (`text.length / 4` per part).
     */
    private suspend fun setup(
        cap: Long?,
        textChars: Int = 0,
    ): Triple<SqlDelightSessionStore, EventBus, SessionId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)
        val now = Clock.System.now()
        val sessionId = SessionId("s-cap")
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = "test",
                createdAt = now,
                updatedAt = now,
                maxSessionTokens = cap,
            ),
        )
        if (textChars > 0) {
            val messageId = MessageId("m-1")
            store.appendMessage(
                Message.User(
                    id = messageId,
                    sessionId = sessionId,
                    createdAt = now,
                    agent = "default",
                    model = ModelRef("fake", "test-model"),
                ),
            )
            store.upsertPart(
                Part.Text(
                    id = PartId("p-1"),
                    messageId = messageId,
                    sessionId = sessionId,
                    createdAt = now,
                    text = "x".repeat(textChars),
                ),
            )
        }
        return Triple(store, bus, sessionId)
    }

    private fun gate(store: SqlDelightSessionStore, bus: EventBus): CompactionGate = CompactionGate(
        compactor = null, // null compactor — cap check runs on input history unchanged
        compactionThreshold = { _ -> Int.MAX_VALUE }, // never auto-compact in this test
        store = store,
        bus = bus,
    )

    @Test fun nullCapBypassesEnforcement() = runTest {
        val (store, bus, sid) = setup(cap = null, textChars = 4_000)
        val history = store.listMessagesWithParts(sid, includeCompacted = false)
        // 4000 chars → ~1000 tokens. With cap=null (default for legacy
        // sessions), the gate must not raise regardless of size.
        val out = gate(store, bus).maybeCompact(runInput(sid), NoOpHandle, history)
        assertEquals(history, out, "null cap → gate is a passthrough, no exception thrown")
    }

    @Test fun underCapDispatchesOk() = runTest {
        // Cap 1000, history ~ 25 tokens (100 chars / 4) — well under.
        val (store, bus, sid) = setup(cap = 1_000L, textChars = 100)
        val history = store.listMessagesWithParts(sid, includeCompacted = false)
        val out = gate(store, bus).maybeCompact(runInput(sid), NoOpHandle, history)
        assertEquals(history, out)
    }

    @Test fun overCapFailsLoud() = runTest {
        // Cap 100, history ~ 1000 tokens (4000 chars / 4) — way over.
        // The estimator returns ((4000 + 3) / 4) = 1000 for the part
        // text alone; total estimate at least 1000. Cap 100 < 1000.
        val (store, bus, sid) = setup(cap = 100L, textChars = 4_000)
        val history = store.listMessagesWithParts(sid, includeCompacted = false)
        val ex = assertFailsWith<SessionTokenCapExceededException> {
            gate(store, bus).maybeCompact(runInput(sid), NoOpHandle, history)
        }
        assertEquals(sid.value, ex.sessionId)
        assertEquals(100L, ex.capTokens)
        // The estimate is at least 1000 (forText("x".repeat(4000)) = 1000);
        // could be higher if Part overhead got added but the estimator
        // for Part.Text is just text-tokens.
        assert(ex.estimatedTokens > 100) {
            "expected estimate above cap; got ${ex.estimatedTokens}"
        }
    }
}
