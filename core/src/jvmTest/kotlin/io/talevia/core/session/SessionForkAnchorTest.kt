package io.talevia.core.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * Branching a session "from here" — fork with an explicit anchor — keeps only
 * the history up-to-and-including the anchor in the new branch, leaving the
 * parent untouched. Companion to `SessionRevert` (same concept, different
 * destructive / non-destructive side).
 */
class SessionForkAnchorTest {

    @Test
    fun forkAtAnchorCopiesOnlyUpToAndIncludingAnchor() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)

        val parentId = SessionId("parent")
        val t0 = Clock.System.now()
        store.createSession(Session(parentId, ProjectId("p"), title = "p", createdAt = t0, updatedAt = t0))

        fun user(id: String, dt: Long) = Message.User(
            id = MessageId(id),
            sessionId = parentId,
            createdAt = t0.plus(dt.milliseconds),
            agent = "default",
            model = ModelRef("anthropic", "claude-opus-4-7"),
        )
        fun asst(id: String, parent: String, dt: Long) = Message.Assistant(
            id = MessageId(id),
            sessionId = parentId,
            createdAt = t0.plus(dt.milliseconds),
            parentId = MessageId(parent),
            model = ModelRef("anthropic", "claude-opus-4-7"),
        )

        listOf(user("u1", 1), asst("a1", "u1", 2), user("u2", 3), asst("a2", "u2", 4))
            .forEach { store.appendMessage(it) }
        store.upsertPart(Part.Text(PartId("p-u1"), MessageId("u1"), parentId, t0.plus(1.milliseconds), text = "hi"))
        store.upsertPart(Part.Text(PartId("p-a1"), MessageId("a1"), parentId, t0.plus(2.milliseconds), text = "reply1"))
        store.upsertPart(Part.Text(PartId("p-u2"), MessageId("u2"), parentId, t0.plus(3.milliseconds), text = "more"))
        store.upsertPart(Part.Text(PartId("p-a2"), MessageId("a2"), parentId, t0.plus(4.milliseconds), text = "reply2"))

        // Fork at a1 — should carry u1, a1 only.
        val branchId = store.fork(parentId, newTitle = "branch", anchorMessageId = MessageId("a1"))
        val branchMessages = store.listMessagesWithParts(branchId)
        assertEquals(2, branchMessages.size)
        val branchTexts = branchMessages.flatMap { it.parts }.filterIsInstance<Part.Text>().map { it.text }
        assertEquals(listOf("hi", "reply1"), branchTexts)

        // Parent still intact.
        val parentMessages = store.listMessagesWithParts(parentId)
        assertEquals(4, parentMessages.size)

        // Every id in the branch is fresh — no shared MessageId / PartId with the parent.
        val parentMsgIds = parentMessages.map { it.message.id.value }.toSet()
        val branchMsgIds = branchMessages.map { it.message.id.value }.toSet()
        assertEquals(emptySet(), parentMsgIds.intersect(branchMsgIds))
        val parentPartIds = parentMessages.flatMap { it.parts }.map { it.id.value }.toSet()
        val branchPartIds = branchMessages.flatMap { it.parts }.map { it.id.value }.toSet()
        assertEquals(emptySet(), parentPartIds.intersect(branchPartIds))

        driver.close()
    }

    @Test
    fun forkWithoutAnchorStillCopiesWholeHistory() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)

        val parentId = SessionId("parent")
        val t0 = Clock.System.now()
        store.createSession(Session(parentId, ProjectId("p"), title = "p", createdAt = t0, updatedAt = t0))
        store.appendMessage(
            Message.User(
                id = MessageId("m"),
                sessionId = parentId,
                createdAt = t0.plus(1.milliseconds),
                agent = "default",
                model = ModelRef("anthropic", "claude-opus-4-7"),
            ),
        )
        val branchId = store.fork(parentId, newTitle = null)
        assertEquals(1, store.listMessages(branchId).size)
        driver.close()
    }

    @Test
    fun forkWithForeignAnchorThrows() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)

        val parentId = SessionId("parent")
        val t0 = Clock.System.now()
        store.createSession(Session(parentId, ProjectId("p"), title = "p", createdAt = t0, updatedAt = t0))
        assertFailsWith<IllegalArgumentException> {
            store.fork(parentId, anchorMessageId = MessageId("not-there"))
        }
        driver.close()
    }
}
