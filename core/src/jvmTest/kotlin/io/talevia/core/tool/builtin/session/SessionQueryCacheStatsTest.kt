package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.CacheStatsRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers `select=cache_stats` on [SessionQueryTool]: aggregates
 * TokenUsage across assistant messages, computes hit ratio, handles
 * edge cases (no messages, zero input, cache-less session).
 */
class SessionQueryCacheStatsTest {

    private val now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun fixture(
        messages: List<Pair<String, TokenUsage>>,
        sessionIdValue: String = "s-cache",
    ): Triple<SqlDelightSessionStore, FileProjectStore, SessionId> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = ProjectStoreTestKit.create()
        val sid = SessionId(sessionIdValue)
        sessions.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
        // Anchor user message (assistant needs a parent).
        val userId = MessageId("u-anchor")
        sessions.appendMessage(
            Message.User(
                id = userId,
                sessionId = sid,
                createdAt = now,
                agent = "test",
                model = ModelRef("test", "test"),
            ),
        )
        messages.forEachIndexed { idx, (id, tokens) ->
            sessions.appendMessage(
                Message.Assistant(
                    id = MessageId(id),
                    sessionId = sid,
                    createdAt = now,
                    parentId = userId,
                    model = ModelRef("test", "test"),
                    tokens = tokens,
                ),
            )
        }
        return Triple(sessions, projects, sid)
    }

    private fun tool(sessions: SqlDelightSessionStore, projects: FileProjectStore): SessionQueryTool {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        return SessionQueryTool(sessions, AgentRunStateTracker(EventBus(), scope), projects)
    }

    private fun rows(out: SessionQueryTool.Output): List<CacheStatsRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(CacheStatsRow.serializer()),
            out.rows,
        )

    @Test fun missingSessionIdRejected() = runTest {
        val (sessions, projects, _) = fixture(emptyList())
        val ex = assertFailsWith<IllegalStateException> {
            tool(sessions, projects).execute(
                SessionQueryTool.Input(select = "cache_stats"),
                ctx(),
            )
        }
        assertTrue("sessionId" in ex.message.orEmpty())
    }

    @Test fun missingSessionErrors() = runTest {
        val (sessions, projects, _) = fixture(emptyList())
        val ex = assertFailsWith<IllegalStateException> {
            tool(sessions, projects).execute(
                SessionQueryTool.Input(select = "cache_stats", sessionId = "ghost"),
                ctx(),
            )
        }
        assertTrue("not found" in ex.message.orEmpty())
    }

    @Test fun emptySessionReturnsZerosAndRatioZero() = runTest {
        val (sessions, projects, sid) = fixture(emptyList())
        val out = tool(sessions, projects).execute(
            SessionQueryTool.Input(select = "cache_stats", sessionId = sid.value),
            ctx(),
        ).data
        val row = rows(out).single()
        assertEquals(0, row.assistantMessageCount)
        assertEquals(0L, row.totalInputTokens)
        assertEquals(0L, row.cacheReadTokens)
        assertEquals(0L, row.cacheWriteTokens)
        assertEquals(0.0, row.hitRatio, "divide-by-zero case must report 0.0 not NaN")
    }

    @Test fun singleMessageHitRatioComputedCorrectly() = runTest {
        val (sessions, projects, sid) = fixture(
            listOf("m1" to TokenUsage(input = 1000L, cacheRead = 500L, cacheWrite = 0L)),
        )
        val out = tool(sessions, projects).execute(
            SessionQueryTool.Input(select = "cache_stats", sessionId = sid.value),
            ctx(),
        ).data
        val row = rows(out).single()
        assertEquals(1, row.assistantMessageCount)
        assertEquals(1000L, row.totalInputTokens)
        assertEquals(500L, row.cacheReadTokens)
        assertEquals(0.5, row.hitRatio, "500/1000 = 0.5")
    }

    @Test fun multipleMessagesAggregateAcrossTurns() = runTest {
        val (sessions, projects, sid) = fixture(
            listOf(
                "m1" to TokenUsage(input = 1000L, cacheRead = 200L, cacheWrite = 800L),
                "m2" to TokenUsage(input = 1200L, cacheRead = 1000L, cacheWrite = 0L),
                "m3" to TokenUsage(input = 800L, cacheRead = 400L, cacheWrite = 0L),
            ),
        )
        val out = tool(sessions, projects).execute(
            SessionQueryTool.Input(select = "cache_stats", sessionId = sid.value),
            ctx(),
        ).data
        val row = rows(out).single()
        assertEquals(3, row.assistantMessageCount)
        assertEquals(3000L, row.totalInputTokens, "1000 + 1200 + 800")
        assertEquals(1600L, row.cacheReadTokens, "200 + 1000 + 400")
        assertEquals(800L, row.cacheWriteTokens)
        // 1600 / 3000 ≈ 0.5333...
        assertTrue(row.hitRatio > 0.53 && row.hitRatio < 0.54)
    }

    @Test fun sessionWithNoCacheSignalReportsZeroRatio() = runTest {
        // Provider didn't populate cacheRead at all — totalInput > 0 but cacheRead == 0.
        val (sessions, projects, sid) = fixture(
            listOf("m1" to TokenUsage(input = 500L, cacheRead = 0L, cacheWrite = 0L)),
        )
        val out = tool(sessions, projects).execute(
            SessionQueryTool.Input(select = "cache_stats", sessionId = sid.value),
            ctx(),
        ).data
        val row = rows(out).single()
        assertEquals(500L, row.totalInputTokens)
        assertEquals(0L, row.cacheReadTokens)
        assertEquals(0.0, row.hitRatio)
    }
}
