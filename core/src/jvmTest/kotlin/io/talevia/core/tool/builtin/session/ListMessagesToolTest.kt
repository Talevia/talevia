package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListMessagesToolTest {

    private data class Rig(
        val store: SqlDelightSessionStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(store, ctx)
    }

    private suspend fun newSession(
        store: SqlDelightSessionStore,
        id: String = "s-1",
    ): Session {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId("p"),
            title = "t",
            createdAt = now,
            updatedAt = now,
        )
        store.createSession(s)
        return s
    }

    private suspend fun appendUser(
        store: SqlDelightSessionStore,
        sessionId: String,
        messageId: String,
        atMs: Long,
    ) {
        store.appendMessage(
            Message.User(
                id = MessageId(messageId),
                sessionId = SessionId(sessionId),
                createdAt = Instant.fromEpochMilliseconds(atMs),
                agent = "default",
                model = ModelRef("anthropic", "claude"),
            ),
        )
    }

    private suspend fun appendAssistant(
        store: SqlDelightSessionStore,
        sessionId: String,
        messageId: String,
        parentId: String,
        atMs: Long,
        tokens: TokenUsage = TokenUsage.ZERO,
        finish: FinishReason? = FinishReason.STOP,
        error: String? = null,
    ) {
        store.appendMessage(
            Message.Assistant(
                id = MessageId(messageId),
                sessionId = SessionId(sessionId),
                createdAt = Instant.fromEpochMilliseconds(atMs),
                parentId = MessageId(parentId),
                model = ModelRef("anthropic", "claude"),
                tokens = tokens,
                finish = finish,
                error = error,
            ),
        )
    }

    @Test fun emptySessionReturnsZero() = runTest {
        val rig = rig()
        newSession(rig.store)
        val out = ListMessagesTool(rig.store).execute(
            ListMessagesTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data
        assertEquals(0, out.totalMessages)
        assertEquals(0, out.returnedMessages)
        assertTrue(out.messages.isEmpty())
    }

    @Test fun messagesSortedMostRecentFirst() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUser(rig.store, "s-1", "u-1", atMs = 1_000L)
        appendAssistant(rig.store, "s-1", "a-1", parentId = "u-1", atMs = 2_000L)
        appendUser(rig.store, "s-1", "u-2", atMs = 3_000L)
        appendAssistant(rig.store, "s-1", "a-2", parentId = "u-2", atMs = 4_000L)

        val out = ListMessagesTool(rig.store).execute(
            ListMessagesTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        assertEquals(4, out.totalMessages)
        assertEquals(listOf("a-2", "u-2", "a-1", "u-1"), out.messages.map { it.id })
    }

    @Test fun assistantRoleCarriesTokensAndFinish() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUser(rig.store, "s-1", "u-1", atMs = 1_000L)
        appendAssistant(
            rig.store, "s-1", "a-1",
            parentId = "u-1",
            atMs = 2_000L,
            tokens = TokenUsage(input = 100, output = 50),
            finish = FinishReason.TOOL_CALLS,
        )

        val out = ListMessagesTool(rig.store).execute(
            ListMessagesTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        val asst = out.messages.single { it.role == "assistant" }
        assertEquals("u-1", asst.parentId)
        assertEquals(100L, asst.tokensInput)
        assertEquals(50L, asst.tokensOutput)
        assertEquals("tool_calls", asst.finish)
        assertNull(asst.error)
    }

    @Test fun userRoleCarriesAgentAndModel() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUser(rig.store, "s-1", "u-1", atMs = 1_000L)

        val out = ListMessagesTool(rig.store).execute(
            ListMessagesTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data
        val user = out.messages.single()
        assertEquals("default", user.agent)
        assertEquals("anthropic", user.modelProviderId)
        assertEquals("claude", user.modelId)
        assertNull(user.parentId)
        assertNull(user.tokensInput)
    }

    @Test fun limitCaps() = runTest {
        val rig = rig()
        newSession(rig.store)
        repeat(10) { i -> appendUser(rig.store, "s-1", "u-$i", atMs = 1_000L + i) }
        val out = ListMessagesTool(rig.store).execute(
            ListMessagesTool.Input(sessionId = "s-1", limit = 3),
            rig.ctx,
        ).data
        assertEquals(10, out.totalMessages)
        assertEquals(3, out.returnedMessages)
        // Most-recent three.
        assertEquals(listOf("u-9", "u-8", "u-7"), out.messages.map { it.id })
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            ListMessagesTool(rig.store).execute(
                ListMessagesTool.Input(sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("list_sessions"), ex.message)
    }

    @Test fun errorFieldRoundTrips() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUser(rig.store, "s-1", "u-1", atMs = 1_000L)
        appendAssistant(
            rig.store, "s-1", "a-1",
            parentId = "u-1",
            atMs = 2_000L,
            finish = FinishReason.ERROR,
            error = "provider 503",
        )
        val out = ListMessagesTool(rig.store).execute(
            ListMessagesTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data
        val asst = out.messages.single { it.role == "assistant" }
        assertEquals("error", asst.finish)
        assertEquals("provider 503", asst.error)
    }
}
