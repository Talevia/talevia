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
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test fun emptySessionReturnsZero() = runTest {
        val rig = rig()
        newSession(rig.store)
        val out = EstimateSessionTokensTool(rig.store).execute(
            EstimateSessionTokensTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data
        assertEquals("s-1", out.sessionId)
        assertEquals(0, out.messageCount)
        assertEquals(0, out.totalTokens)
        assertEquals(0, out.largestMessageTokens)
        assertNull(out.messages)
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

        val out = EstimateSessionTokensTool(rig.store).execute(
            EstimateSessionTokensTool.Input(sessionId = "s-1"),
            rig.ctx,
        ).data

        assertEquals(2, out.messageCount)
        // Independent recomputation — `forHistory` drives both sides, so we
        // compute the expected result directly against the same store view.
        val expected = TokenEstimator.forHistory(rig.store.listMessagesWithParts(SessionId("s-1")))
        assertEquals(expected, out.totalTokens)
        // Sanity: token counts track text length via the ~4-char heuristic.
        assertEquals(TokenEstimator.forText(userText) + TokenEstimator.forText(asstText), out.totalTokens)
        assertTrue(out.largestMessageTokens > 0)
    }

    @Test fun includeBreakdownExposesPerMessageTokens() = runTest {
        val rig = rig()
        newSession(rig.store)
        appendUserWithText(rig.store, "s-1", "u-1", atMs = 1_000L, text = "u".repeat(100))
        appendAssistantWithText(
            rig.store, "s-1", "a-1",
            parentId = "u-1", atMs = 2_000L, text = "a".repeat(200),
        )

        val out = EstimateSessionTokensTool(rig.store).execute(
            EstimateSessionTokensTool.Input(sessionId = "s-1", includeBreakdown = true),
            rig.ctx,
        ).data

        val rows = assertNotNull(out.messages, "breakdown should be non-null when includeBreakdown=true")
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
        // Sizes chosen so token counts are strictly ordered under the ~4-char heuristic.
        appendUserWithText(rig.store, "s-1", "u-1", atMs = 1_000L, text = "x".repeat(40))
        appendAssistantWithText(
            rig.store, "s-1", "a-1",
            parentId = "u-1", atMs = 2_000L, text = "x".repeat(400),
        )
        appendUserWithText(rig.store, "s-1", "u-2", atMs = 3_000L, text = "x".repeat(120))

        val out = EstimateSessionTokensTool(rig.store).execute(
            EstimateSessionTokensTool.Input(sessionId = "s-1", includeBreakdown = true),
            rig.ctx,
        ).data

        assertEquals(3, out.messageCount)
        val rows = assertNotNull(out.messages)
        val expectedMax = rows.maxOf { it.tokens }
        assertEquals(expectedMax, out.largestMessageTokens)
        // And that max should belong to the 400-char assistant row.
        val asstTokens = TokenEstimator.forText("x".repeat(400))
        assertEquals(asstTokens, out.largestMessageTokens)
    }

    @Test fun missingSessionFailsLoudly() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalStateException> {
            EstimateSessionTokensTool(rig.store).execute(
                EstimateSessionTokensTool.Input(sessionId = "ghost"),
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

        val res = EstimateSessionTokensTool(rig.store).execute(
            EstimateSessionTokensTool.Input(sessionId = "s-1"),
            rig.ctx,
        )
        assertNull(res.data.messages)

        // Serialize the output to JSON and confirm the `messages` key either
        // doesn't appear or is explicit null — the serialized payload is the
        // actual tool-result surface the LLM sees, so the terse contract
        // lives there.
        val json = JsonConfig.default.encodeToString(EstimateSessionTokensTool.Output.serializer(), res.data)
        val parsed = JsonConfig.default.parseToJsonElement(json).toString()
        // Either key absent (default = null, omitted), or key present with null value.
        val ok = !parsed.contains("\"messages\"") || parsed.contains("\"messages\":null")
        assertTrue(ok, "expected terse JSON to omit or null-out `messages`; got: $parsed")
    }
}
