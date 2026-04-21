package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRule
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DescribeSessionToolTest {

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
        projectId: String = "p",
        title: String = id,
        rules: List<PermissionRule> = emptyList(),
        archived: Boolean = false,
    ): Session {
        val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val s = Session(
            id = SessionId(id),
            projectId = ProjectId(projectId),
            title = title,
            createdAt = now,
            updatedAt = now,
            archived = archived,
            permissionRules = rules,
        )
        store.createSession(s)
        return s
    }

    private suspend fun appendUser(
        store: SqlDelightSessionStore,
        sessionId: String,
        messageId: String,
        atMs: Long = 1_700_000_000_000L,
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
        tokens: TokenUsage = TokenUsage.ZERO,
        atMs: Long = 1_700_000_000_000L,
    ) {
        store.appendMessage(
            Message.Assistant(
                id = MessageId(messageId),
                sessionId = SessionId(sessionId),
                createdAt = Instant.fromEpochMilliseconds(atMs),
                parentId = MessageId("placeholder"),
                model = ModelRef("anthropic", "claude"),
                tokens = tokens,
            ),
        )
    }

    @Test fun emptySessionHasZeroCounts() = runTest {
        val rig = rig()
        newSession(rig.store)

        val out = DescribeSessionTool(rig.store).execute(
            DescribeSessionTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        assertEquals("s-1", out.id)
        assertEquals(0, out.messageCount)
        assertEquals(0, out.userMessageCount)
        assertEquals(0, out.assistantMessageCount)
        assertEquals(0L, out.totalTokensInput)
        assertFalse(out.hasCompactionPart)
        // latestMessageAt falls back to session.createdAt when empty.
        assertEquals(out.createdAtEpochMs, out.latestMessageAtEpochMs)
    }

    @Test fun countsAndSumsTokensAcrossAssistantTurns() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUser(rig.store, "s-1", "u-1", atMs = 1_000L)
        appendAssistant(
            rig.store, "s-1", "a-1",
            tokens = TokenUsage(input = 100, output = 50, cacheRead = 10, cacheWrite = 5),
            atMs = 2_000L,
        )
        appendUser(rig.store, "s-1", "u-2", atMs = 3_000L)
        appendAssistant(
            rig.store, "s-1", "a-2",
            tokens = TokenUsage(input = 200, output = 80, cacheRead = 20),
            atMs = 4_000L,
        )

        val out = DescribeSessionTool(rig.store).execute(
            DescribeSessionTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        assertEquals(4, out.messageCount)
        assertEquals(2, out.userMessageCount)
        assertEquals(2, out.assistantMessageCount)
        assertEquals(300L, out.totalTokensInput)
        assertEquals(130L, out.totalTokensOutput)
        assertEquals(30L, out.totalTokensCacheRead)
        assertEquals(5L, out.totalTokensCacheWrite)
        assertEquals(4_000L, out.latestMessageAtEpochMs)
    }

    @Test fun detectsCompactionPart() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendAssistant(rig.store, "s-1", "a-1")
        rig.store.upsertPart(
            Part.Compaction(
                id = PartId("p-1"),
                messageId = MessageId("a-1"),
                sessionId = SessionId("s-1"),
                createdAt = Instant.fromEpochMilliseconds(1_700_000_000_000L),
                replacedFromMessageId = MessageId("a-1"),
                replacedToMessageId = MessageId("a-1"),
                summary = "collapsed",
            ),
        )

        val out = DescribeSessionTool(rig.store).execute(
            DescribeSessionTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data
        assertTrue(out.hasCompactionPart)
    }

    @Test fun exposesPermissionRuleCount() = runTest {
        val rig = rig()
        newSession(
            rig.store,
            rules = listOf(
                PermissionRule(permission = "fs.read", pattern = "/tmp/*", action = PermissionAction.ALLOW),
                PermissionRule(permission = "fs.write", pattern = "/tmp/*", action = PermissionAction.ALLOW),
            ),
        )

        val out = DescribeSessionTool(rig.store).execute(
            DescribeSessionTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data
        assertEquals(2, out.permissionRuleCount)
    }

    @Test fun missingSessionFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            DescribeSessionTool(rig.store).execute(
                DescribeSessionTool.Input(sessionId = "ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("list_sessions"), ex.message)
    }
}
