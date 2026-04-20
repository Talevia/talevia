package io.talevia.core.tool.builtin

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TodoInfo
import io.talevia.core.session.TodoPriority
import io.talevia.core.session.TodoStatus
import io.talevia.core.session.currentTodos
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TodoWriteToolTest {

    private fun newStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus(), Clock.System)
    }

    private suspend fun seed(store: SqlDelightSessionStore, sessionId: SessionId, messageId: MessageId) {
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sessionId,
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
        store.appendMessage(
            Message.Assistant(
                id = messageId,
                sessionId = sessionId,
                createdAt = now,
                parentId = MessageId("parent"),
                model = ModelRef(providerId = "p", modelId = "m"),
            ),
        )
    }

    @Test
    fun emitsTodosPartWithProvidedEntries() = runTest {
        val store = newStore()
        val sessionId = SessionId("s")
        val messageId = MessageId("m")
        seed(store, sessionId, messageId)

        val emitted = mutableListOf<Part>()
        val tool = TodoWriteTool(clock = FixedClock(Instant.fromEpochMilliseconds(1_000)))
        val result = tool.execute(
            TodoWriteTool.Input(
                todos = listOf(
                    TodoInfo("plan the thing", TodoStatus.IN_PROGRESS),
                    TodoInfo("ship the thing", TodoStatus.PENDING, TodoPriority.HIGH),
                    TodoInfo("announce the thing", TodoStatus.COMPLETED),
                ),
            ),
            ToolContext(
                sessionId = sessionId,
                messageId = messageId,
                callId = CallId("c"),
                askPermission = { PermissionDecision.Once },
                emitPart = { emitted.add(it); store.upsertPart(it) },
                messages = emptyList(),
            ),
        )

        assertEquals(3, result.data.count)
        assertEquals("2 open · 3 total", result.title)
        assertEquals(1, emitted.size)
        val todosPart = assertIs<Part.Todos>(emitted.single())
        assertEquals(3, todosPart.todos.size)
        assertEquals(TodoStatus.IN_PROGRESS, todosPart.todos[0].status)
    }

    @Test
    fun renderForLlmMatchesExpectedMarkers() {
        val tool = TodoWriteTool()
        val rendered = tool.renderForLlm(
            listOf(
                TodoInfo("one", TodoStatus.PENDING),
                TodoInfo("two", TodoStatus.IN_PROGRESS, TodoPriority.HIGH),
                TodoInfo("three", TodoStatus.COMPLETED),
                TodoInfo("four", TodoStatus.CANCELLED, TodoPriority.LOW),
            ),
        )
        val expected = """
            [ ] one
            [~] two (high)
            [x] three
            [-] four (low)
        """.trimIndent()
        assertEquals(expected, rendered)
        assertEquals("(no todos)", tool.renderForLlm(emptyList()))
    }

    @Test
    fun currentTodosReturnsLatestPart() = runTest {
        val store = newStore()
        val sessionId = SessionId("s")
        val messageId = MessageId("m")
        seed(store, sessionId, messageId)

        val ctx = ToolContext(
            sessionId = sessionId,
            messageId = messageId,
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { store.upsertPart(it) },
            messages = emptyList(),
        )
        val tool = TodoWriteTool()
        tool.execute(
            TodoWriteTool.Input(listOf(TodoInfo("a", TodoStatus.PENDING))),
            ctx,
        )
        tool.execute(
            TodoWriteTool.Input(
                listOf(
                    TodoInfo("a", TodoStatus.COMPLETED),
                    TodoInfo("b", TodoStatus.IN_PROGRESS),
                ),
            ),
            ctx,
        )

        val current = store.currentTodos(sessionId)
        assertEquals(2, current.size)
        assertEquals("a", current[0].content)
        assertEquals(TodoStatus.COMPLETED, current[0].status)
        assertEquals(TodoStatus.IN_PROGRESS, current[1].status)
    }

    @Test
    fun emptyTodosStillEmitsPart() = runTest {
        val store = newStore()
        val sessionId = SessionId("s")
        val messageId = MessageId("m")
        seed(store, sessionId, messageId)

        val emitted = mutableListOf<Part>()
        val result = TodoWriteTool().execute(
            TodoWriteTool.Input(emptyList()),
            ToolContext(
                sessionId = sessionId,
                messageId = messageId,
                callId = CallId("c"),
                askPermission = { PermissionDecision.Once },
                emitPart = { emitted.add(it) },
                messages = emptyList(),
            ),
        )
        assertEquals(0, result.data.count)
        assertEquals("0 open · 0 total", result.title)
        assertTrue(emitted.single() is Part.Todos)
    }

    private class FixedClock(private val now: Instant) : Clock {
        override fun now(): Instant = now
    }
}
