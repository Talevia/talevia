package io.talevia.core.session.projector

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolCallTreeProjectorTest {

    private fun store(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedSession(
        store: SqlDelightSessionStore,
        id: String = "s1",
        epochMs: Long = 1_700_000_000_000L,
    ): Session {
        val now = Instant.fromEpochMilliseconds(epochMs)
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId("p"),
            title = id,
            createdAt = now,
            updatedAt = now,
        )
        store.createSession(s)
        return s
    }

    @Test fun emptySessionYieldsEmptyTree() = runTest {
        val s = store()
        seedSession(s)
        val tree = ToolCallTreeProjector(s).project(SessionId("s1"))
        assertEquals("s1", tree.sessionId)
        assertTrue(tree.turns.isEmpty())
    }

    @Test fun assistantTurnWithMultipleToolCallsFormsTree() = runTest {
        val store = store()
        seedSession(store)

        val userMsg = Message.User(
            id = MessageId("u1"),
            sessionId = SessionId("s1"),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_100_000L),
            agent = "default",
            model = ModelRef("anthropic", "claude"),
        )
        store.appendMessage(userMsg)

        val asstMsg = Message.Assistant(
            id = MessageId("a1"),
            sessionId = SessionId("s1"),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_200_000L),
            parentId = MessageId("u1"),
            model = ModelRef("anthropic", "claude"),
            tokens = TokenUsage(input = 100, output = 50),
            finish = FinishReason.TOOL_CALLS,
        )
        store.appendMessage(asstMsg)

        // Two tool calls — insert out of order to verify projector re-sorts by createdAt.
        store.upsertPart(
            Part.Tool(
                id = PartId("pt-b"),
                messageId = MessageId("a1"),
                sessionId = SessionId("s1"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_210_000L),
                callId = CallId("c-b"),
                toolId = "synthesize_speech",
                state = ToolState.Completed(
                    kotlinx.serialization.json.JsonNull,
                    "ok",
                    kotlinx.serialization.json.JsonNull,
                ),
            ),
        )
        store.upsertPart(
            Part.Tool(
                id = PartId("pt-a"),
                messageId = MessageId("a1"),
                sessionId = SessionId("s1"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_205_000L),
                callId = CallId("c-a"),
                toolId = "generate_image",
                state = ToolState.Pending,
            ),
        )

        val tree = ToolCallTreeProjector(store).project(SessionId("s1"))
        assertEquals(1, tree.turns.size)
        val turn = tree.turns.single()
        assertEquals("u1", turn.userMessageId)
        assertEquals("a1", turn.assistantMessageId)
        assertEquals("tool_calls", turn.finish)
        assertEquals(100, turn.tokensInput)
        assertEquals(50, turn.tokensOutput)
        assertEquals(listOf("generate_image", "synthesize_speech"), turn.toolCalls.map { it.toolId })
        assertEquals(listOf("pending", "completed"), turn.toolCalls.map { it.state })
    }

    @Test fun textOnlyAssistantTurnHasNoToolCalls() = runTest {
        val store = store()
        seedSession(store)
        val userMsg = Message.User(
            id = MessageId("u1"),
            sessionId = SessionId("s1"),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_100_000L),
            agent = "default",
            model = ModelRef("anthropic", "claude"),
        )
        store.appendMessage(userMsg)
        val asstMsg = Message.Assistant(
            id = MessageId("a1"),
            sessionId = SessionId("s1"),
            createdAt = Instant.fromEpochMilliseconds(1_700_000_200_000L),
            parentId = MessageId("u1"),
            model = ModelRef("anthropic", "claude"),
            finish = FinishReason.STOP,
        )
        store.appendMessage(asstMsg)
        store.upsertPart(
            Part.Text(
                id = PartId("pt-text"),
                messageId = MessageId("a1"),
                sessionId = SessionId("s1"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_205_000L),
                text = "hello world",
            ),
        )

        val tree = ToolCallTreeProjector(store).project(SessionId("s1"))
        val turn = tree.turns.single()
        assertTrue(turn.toolCalls.isEmpty())
        assertEquals("stop", turn.finish)
    }

    @Test fun userMessagesWithoutAssistantResponseAreNotTurns() = runTest {
        val store = store()
        seedSession(store)
        // User-only — no answering assistant message yet (in-flight).
        store.appendMessage(
            Message.User(
                id = MessageId("u1"),
                sessionId = SessionId("s1"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_100_000L),
                agent = "default",
                model = ModelRef("anthropic", "claude"),
            ),
        )
        val tree = ToolCallTreeProjector(store).project(SessionId("s1"))
        assertTrue(tree.turns.isEmpty())
    }

    @Test fun multipleTurnsOrderedByAssistantCreatedAt() = runTest {
        val store = store()
        seedSession(store)
        suspend fun mkTurn(prefix: String, userAt: Long, asstAt: Long) {
            store.appendMessage(
                Message.User(
                    id = MessageId("${prefix}u"),
                    sessionId = SessionId("s1"),
                    createdAt = Instant.fromEpochMilliseconds(userAt),
                    agent = "default",
                    model = ModelRef("anthropic", "claude"),
                ),
            )
            store.appendMessage(
                Message.Assistant(
                    id = MessageId("${prefix}a"),
                    sessionId = SessionId("s1"),
                    createdAt = Instant.fromEpochMilliseconds(asstAt),
                    parentId = MessageId("${prefix}u"),
                    model = ModelRef("anthropic", "claude"),
                    finish = FinishReason.STOP,
                ),
            )
        }

        mkTurn("x", userAt = 1_700_000_100_000L, asstAt = 1_700_000_200_000L)
        mkTurn("y", userAt = 1_700_000_300_000L, asstAt = 1_700_000_400_000L)

        val tree = ToolCallTreeProjector(store).project(SessionId("s1"))
        assertEquals(listOf("xa", "ya"), tree.turns.map { it.assistantMessageId })
        tree.turns.first().userMessageId?.let { assertEquals("xu", it) } ?: assertNull(tree.turns.first().userMessageId)
        tree.turns.last().userMessageId?.let { assertEquals("yu", it) } ?: assertNull(tree.turns.last().userMessageId)
    }
}
