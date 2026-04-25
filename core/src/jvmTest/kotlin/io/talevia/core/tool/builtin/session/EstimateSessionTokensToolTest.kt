package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.TokenEstimator
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.TokenEstimateRow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cycle 141 folded `estimate_session_tokens` into
 * `session_query(select=token_estimate)`. This suite continues to
 * exercise the pre-compaction session-weight probe — empty session,
 * heuristic-tracked totals, optional per-message breakdown,
 * largest-message tracking, missing-session error — but routes
 * through the unified dispatcher.
 */
class EstimateSessionTokensToolTest {

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

    private suspend fun appendUserWithText(
        store: SqlDelightSessionStore,
        sessionId: String,
        messageId: String,
        atMs: Long,
        text: String,
    ) {
        val sid = SessionId(sessionId)
        val mid = MessageId(messageId)
        store.appendMessage(
            Message.User(
                id = mid,
                sessionId = sid,
                createdAt = Instant.fromEpochMilliseconds(atMs),
                agent = "default",
                model = ModelRef("anthropic", "claude"),
            ),
        )
        store.upsertPart(
            Part.Text(
                id = PartId("pt-$messageId"),
                messageId = mid,
                sessionId = sid,
                createdAt = Instant.fromEpochMilliseconds(atMs + 1),
                text = text,
            ),
        )
    }

    private suspend fun appendAssistantWithText(
        store: SqlDelightSessionStore,
        sessionId: String,
        messageId: String,
        parentId: String,
        atMs: Long,
        text: String,
    ) {
        val sid = SessionId(sessionId)
        val mid = MessageId(messageId)
        store.appendMessage(
            Message.Assistant(
                id = mid,
                sessionId = sid,
                createdAt = Instant.fromEpochMilliseconds(atMs),
                parentId = MessageId(parentId),
                model = ModelRef("anthropic", "claude"),
            ),
        )
        store.upsertPart(
            Part.Text(
                id = PartId("pt-$messageId"),
                messageId = mid,
                sessionId = sid,
                createdAt = Instant.fromEpochMilliseconds(atMs + 1),
                text = text,
            ),
        )
    }

    private fun tokenEstimateInput(sessionId: String, includeBreakdown: Boolean? = null) =
        SessionQueryTool.Input(
            select = SessionQueryTool.SELECT_TOKEN_ESTIMATE,
            sessionId = sessionId,
            includeBreakdown = includeBreakdown,
        )

    private fun decodeRow(out: SessionQueryTool.Output): TokenEstimateRow {
        assertEquals(SessionQueryTool.SELECT_TOKEN_ESTIMATE, out.select)
        assertEquals(1, out.total)
        assertEquals(1, out.returned)
        val rows = JsonConfig.default.decodeFromJsonElement(
            ListSerializer(TokenEstimateRow.serializer()),
            out.rows,
        )
        return rows.single()
    }

    @Test fun emptySessionReturnsZero() = runTest {
        val rig = rig()
        newSession(rig.store)
        val row = decodeRow(
            SessionQueryTool(rig.store).execute(
                tokenEstimateInput("s-1"),
                rig.ctx,
            ).data,
        )
        assertEquals("s-1", row.sessionId)
        assertEquals(0, row.messageCount)
        assertEquals(0, row.totalTokens)
        assertEquals(0, row.largestMessageTokens)
        assertNull(row.breakdown)
    }

    @Test fun wellPopulatedSessionSumsTokens() = runTest {
        val rig = rig()
        newSession(rig.store)
        val userText = "u".repeat(100)
        val asstText = "a".repeat(200)
        appendUserWithText(rig.store, "s-1", "u-1", atMs = 1_000L, text = userText)
        appendAssistantWithText(
            rig.store, "s-1", "a-1",
            parentId = "u-1", atMs = 2_000L, text = asstText,
        )

        val row = decodeRow(
            SessionQueryTool(rig.store).execute(
                tokenEstimateInput("s-1"),
                rig.ctx,
            ).data,
        )

        assertEquals(2, row.messageCount)
        val expected = TokenEstimator.forHistory(rig.store.listMessagesWithParts(SessionId("s-1")))
        assertEquals(expected, row.totalTokens)
        assertEquals(TokenEstimator.forText(userText) + TokenEstimator.forText(asstText), row.totalTokens)
        assertTrue(row.largestMessageTokens > 0)
    }

    @Test fun includeBreakdownExposesPerMessageTokens() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUserWithText(rig.store, "s-1", "u-1", atMs = 1_000L, text = "u".repeat(100))
        appendAssistantWithText(
            rig.store, "s-1", "a-1",
            parentId = "u-1", atMs = 2_000L, text = "a".repeat(200),
        )

        val row = decodeRow(
            SessionQueryTool(rig.store).execute(
                tokenEstimateInput("s-1", includeBreakdown = true),
                rig.ctx,
            ).data,
        )

        val rows = assertNotNull(row.breakdown, "breakdown should be non-null when includeBreakdown=true")
        assertEquals(2, rows.size)
        // Most-recent first → assistant then user.
        assertEquals("a-1", rows[0].id)
        assertEquals("assistant", rows[0].role)
        assertEquals(TokenEstimator.forText("a".repeat(200)), rows[0].tokens)
        assertEquals("u-1", rows[1].id)
        assertEquals("user", rows[1].role)
        assertEquals(TokenEstimator.forText("u".repeat(100)), rows[1].tokens)
    }

    @Test fun largestMessageTokensTracksMax() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUserWithText(rig.store, "s-1", "u-1", atMs = 1_000L, text = "x".repeat(40))
        appendAssistantWithText(
            rig.store, "s-1", "a-1",
            parentId = "u-1", atMs = 2_000L, text = "x".repeat(400),
        )
        appendUserWithText(rig.store, "s-1", "u-2", atMs = 3_000L, text = "x".repeat(120))

        val row = decodeRow(
            SessionQueryTool(rig.store).execute(
                tokenEstimateInput("s-1", includeBreakdown = true),
                rig.ctx,
            ).data,
        )

        assertEquals(3, row.messageCount)
        val rows = assertNotNull(row.breakdown)
        val expectedMax = rows.maxOf { it.tokens }
        assertEquals(expectedMax, row.largestMessageTokens)
        val asstTokens = TokenEstimator.forText("x".repeat(400))
        assertEquals(asstTokens, row.largestMessageTokens)
    }

    @Test fun missingSessionFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            SessionQueryTool(rig.store).execute(
                tokenEstimateInput("ghost"),
                rig.ctx,
            )
        }
        assertTrue(ex.message!!.contains("ghost"), ex.message)
        assertTrue(ex.message!!.contains("session_query(select=sessions)"), ex.message)
    }

    @Test fun defaultOmitsBreakdown() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUserWithText(rig.store, "s-1", "u-1", atMs = 1_000L, text = "u".repeat(100))
        appendAssistantWithText(
            rig.store, "s-1", "a-1",
            parentId = "u-1", atMs = 2_000L, text = "a".repeat(200),
        )

        val row = decodeRow(
            SessionQueryTool(rig.store).execute(
                tokenEstimateInput("s-1"),
                rig.ctx,
            ).data,
        )
        assertNull(row.breakdown)

        // Serialize the row to JSON and confirm the `breakdown` key either
        // doesn't appear or is explicit null — the serialized payload is what
        // the LLM ultimately consumes, so the terse contract lives there.
        val json = JsonConfig.default.encodeToString(TokenEstimateRow.serializer(), row)
        val ok = !json.contains("\"breakdown\"") || json.contains("\"breakdown\":null")
        assertTrue(ok, "expected terse JSON to omit or null-out `breakdown`; got: $json")
    }
}
