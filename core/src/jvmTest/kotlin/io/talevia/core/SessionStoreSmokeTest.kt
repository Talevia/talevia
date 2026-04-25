package io.talevia.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.bus.publishAndAwait
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
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/**
 * M0 smoke test: build a session, append a user message + a text part, and verify
 *  - the row round-trips through SQLDelight (assertEquals on hydrated model)
 *  - subscribers on the EventBus see PartUpdated for newly inserted parts
 */
class SessionStoreSmokeTest {

    @Test
    fun roundtripAndStream() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)

        val now = Clock.System.now()
        val sessionId = SessionId("s-1")
        val messageId = MessageId("m-1")
        val partId = PartId("p-1")

        val session = Session(
            id = sessionId,
            projectId = ProjectId("proj-1"),
            title = "smoke",
            createdAt = now,
            updatedAt = now,
        )
        val message = Message.User(
            id = messageId,
            sessionId = sessionId,
            createdAt = now,
            agent = "default",
            model = ModelRef("anthropic", "claude-opus-4-7"),
        )
        val part = Part.Text(
            id = partId,
            messageId = messageId,
            sessionId = sessionId,
            createdAt = now,
            text = "hello timeline",
        )

        bus.publishAndAwait<BusEvent.PartUpdated>(
            trigger = {
                store.createSession(session)
                store.appendMessage(message)
                store.upsertPart(part)
            },
        ) { event ->
            assertEquals(sessionId, event.sessionId)
            assertEquals(partId, event.partId)
            val streamed = event.part as Part.Text
            assertEquals("hello timeline", streamed.text)
        }

        val hydrated = store.listMessagesWithParts(sessionId)
        assertEquals(1, hydrated.size)
        val (m, parts) = hydrated.single()
        assertNotNull(m as? Message.User)
        assertEquals(messageId, m.id)
        assertEquals(1, parts.size)
        assertEquals("hello timeline", (parts.single() as Part.Text).text)

        driver.close()
    }

    @Test
    fun sessionUpdatedAtMovesForwardWhenMessagesArrive() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)

        val t0 = Clock.System.now()
        val t1 = t0.plus(1.seconds)
        val t2 = t1.plus(1.seconds)

        val first = Session(SessionId("s-1"), ProjectId("p"), title = "first", createdAt = t0, updatedAt = t0)
        val second = Session(SessionId("s-2"), ProjectId("p"), title = "second", createdAt = t1, updatedAt = t1)
        store.createSession(first)
        store.createSession(second)

        val initialOrder = store.listSessions(ProjectId("p")).map { it.id.value }
        assertEquals(listOf("s-2", "s-1"), initialOrder)

        store.appendMessage(
            Message.User(
                id = MessageId("m-1"),
                sessionId = SessionId("s-1"),
                createdAt = t2,
                agent = "default",
                model = ModelRef("anthropic", "claude-opus-4-7"),
            ),
        )

        val refreshed = store.getSession(SessionId("s-1"))!!
        assertEquals(t2, refreshed.updatedAt)
        val reordered = store.listSessions(ProjectId("p")).map { it.id.value }
        assertEquals(listOf("s-1", "s-2"), reordered)

        driver.close()
    }
}
