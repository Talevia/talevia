package io.talevia.core

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import kotlinx.coroutines.async
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

        // Subscribe before any writes so we catch the event.
        bus.subscribe<BusEvent.PartUpdated>().test(timeout = 5.seconds) {
            val writes = async {
                store.createSession(session)
                store.appendMessage(message)
                store.upsertPart(part)
            }

            val event = awaitItem()
            assertEquals(sessionId, event.sessionId)
            assertEquals(partId, event.partId)
            val streamed = event.part as Part.Text
            assertEquals("hello timeline", streamed.text)

            writes.await()
            cancelAndIgnoreRemainingEvents()
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
}
