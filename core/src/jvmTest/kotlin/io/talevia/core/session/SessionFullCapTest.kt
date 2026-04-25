package io.talevia.core.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.bus.publishAndAwait
import io.talevia.core.db.TaleviaDb
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Hard-cap regression for `bound-session-history-hard-cap` (cycle 155):
 * the [SqlDelightSessionStore]'s `maxMessages` ceiling backstops the
 * [io.talevia.core.compaction.Compactor]'s soft-trigger threshold so a
 * pathological session (rapid-fire tool outputs the compactor can't
 * keep up with, or a tiny-context model the compactor's
 * `Skipped("nothing to prune")` branch trips on repeatedly) doesn't
 * silently grow until the process OOMs.
 */
class SessionFullCapTest {

    private fun newStoreAndBus(maxMessages: Int): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(
            db = TaleviaDb(driver),
            bus = bus,
            maxMessages = maxMessages,
        )
        return store to bus
    }

    private suspend fun seedSession(store: SqlDelightSessionStore, sid: SessionId) {
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "cap",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun userMessage(idx: Int, sid: SessionId): Message.User =
        Message.User(
            // Include the session id in the message id so cross-session
            // tests don't collide on the Messages.id PRIMARY KEY.
            id = MessageId("${sid.value}-u-$idx"),
            sessionId = sid,
            createdAt = Instant.fromEpochMilliseconds(1_000L + idx),
            agent = "default",
            model = ModelRef("fake", "fake-model"),
        )

    @Test fun appendUpToCapSucceedsButNthAppendThrowsAndPublishesSessionFull() = runTest {
        val cap = 3
        val (store, bus) = newStoreAndBus(maxMessages = cap)
        val sid = SessionId("s-cap")
        seedSession(store, sid)

        // The first 3 appends slot in cleanly.
        repeat(cap) { i -> store.appendMessage(userMessage(i, sid)) }
        assertEquals(cap, store.listMessages(sid).size)

        // Subscribe BEFORE the over-cap append so the SessionFull event
        // lands in the flow — see `BusEventTestKit.publishAndAwait` for
        // the subscribe-before-publish recipe (cycle 160).
        bus.publishAndAwait<BusEvent.SessionFull>(
            trigger = {
                val ex = assertFailsWith<IllegalStateException> {
                    store.appendMessage(userMessage(cap, sid))
                }
                assertTrue(
                    ex.message!!.contains("hit the appendMessage cap") &&
                        ex.message!!.contains("cap=$cap") &&
                        ex.message!!.contains("messageCount=$cap"),
                    "exception text should name the cap + count; got: ${ex.message}",
                )
                // The rejected message must NOT have been written.
                assertEquals(cap, store.listMessages(sid).size)
            },
        ) { event ->
            assertEquals(sid, event.sessionId)
            assertEquals(cap, event.messageCount)
            assertEquals(cap, event.cap)
        }
    }

    @Test fun rejectedAppendDoesNotMutateOtherSessions() = runTest {
        val cap = 2
        val (store, _) = newStoreAndBus(maxMessages = cap)
        val sidA = SessionId("s-a")
        val sidB = SessionId("s-b")
        seedSession(store, sidA)
        seedSession(store, sidB)

        // Fill A to cap; B stays empty.
        repeat(cap) { i -> store.appendMessage(userMessage(i, sidA)) }
        assertEquals(cap, store.listMessages(sidA).size)
        assertEquals(0, store.listMessages(sidB).size)

        // Over-cap append on A throws.
        assertFailsWith<IllegalStateException> {
            store.appendMessage(userMessage(99, sidA))
        }

        // B is unaffected — cap is per-session, not per-store.
        store.appendMessage(userMessage(0, sidB))
        assertEquals(1, store.listMessages(sidB).size)
        assertEquals(cap, store.listMessages(sidA).size)
    }

    @Test fun defaultMaxMessagesIsGenerous() = runTest {
        // Default cap is 1000 — we don't actually fill it (too slow), but
        // verify the constant + ctor default agree so a future "tightened
        // it" change can't silently regress the default behaviour.
        assertEquals(1000, SqlDelightSessionStore.DEFAULT_MAX_MESSAGES)

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db = TaleviaDb(driver), bus = bus)
        val sid = SessionId("s-default")
        seedSession(store, sid)

        // Trivial append — proves the default path works without overriding.
        store.appendMessage(userMessage(0, sid))
        assertEquals(1, store.listMessages(sid).size)
    }

    @Test fun zeroOrNegativeMaxMessagesRejectedAtCtor() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val bus = EventBus()
        for (badCap in listOf(0, -1, -100)) {
            val ex = assertFailsWith<IllegalArgumentException>("ctor should reject maxMessages=$badCap") {
                SqlDelightSessionStore(db = TaleviaDb(driver), bus = bus, maxMessages = badCap)
            }
            assertTrue(
                ex.message!!.contains("must be positive"),
                "exception should explain the constraint; got: ${ex.message}",
            )
        }
    }
}
