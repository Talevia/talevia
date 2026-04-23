package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.ContextPressureRow
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers `select=context_pressure` on [SessionQueryTool]: token-footprint
 * vs auto-compaction-threshold snapshot, without depending on the agent
 * tracker (unlike `select=status`).
 *
 * Semantic boundaries (§3a rule 9):
 *  - Missing `sessionId` rejected (matches every other sessionId-scoped select).
 *  - Missing session errors (typo / wrong id surfaces loud).
 *  - Empty session → zero estimate, ratio 0.0, full threshold as margin.
 *  - Below threshold → ratio < 1, marginTokens positive, overThreshold=false.
 *  - Over threshold → ratio > 1 (un-clamped), marginTokens negative,
 *    overThreshold=true. Guards against status's clamp-at-1.0 behavior
 *    masking the over-limit case.
 *  - `messageCount` excludes the anchor user message when relevant? No —
 *    TokenEstimator.forHistory sums across all messages with parts (user +
 *    assistant), so messageCount reports ALL non-compacted messages.
 *  - Rig without agentStates (null tracker) still works — the whole point
 *    of the split from `select=status`.
 */
class SessionQueryContextPressureTest {

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
        sessionIdValue: String = "s-ctx",
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
        return Triple(sessions, projects, sid)
    }

    private suspend fun appendUserWithText(
        sessions: SqlDelightSessionStore,
        sid: SessionId,
        msgIdValue: String,
        text: String,
    ): MessageId {
        val mid = MessageId(msgIdValue)
        sessions.appendMessage(
            Message.User(
                id = mid,
                sessionId = sid,
                createdAt = now,
                agent = "test",
                model = ModelRef("test", "test"),
            ),
        )
        sessions.upsertPart(
            Part.Text(
                id = PartId("p-$msgIdValue"),
                messageId = mid,
                sessionId = sid,
                createdAt = now,
                text = text,
            ),
        )
        return mid
    }

    /** Pure-store tool construction — no tracker or project store. */
    private fun tool(sessions: SqlDelightSessionStore): SessionQueryTool =
        SessionQueryTool(sessions)

    private fun rows(out: SessionQueryTool.Output): List<ContextPressureRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(ContextPressureRow.serializer()),
            out.rows,
        )

    @Test fun missingSessionIdRejected() = runTest {
        val (sessions, _, _) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            tool(sessions).execute(
                SessionQueryTool.Input(select = "context_pressure"),
                ctx(),
            )
        }
        assertTrue("sessionId" in ex.message.orEmpty())
    }

    @Test fun missingSessionErrors() = runTest {
        val (sessions, _, _) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            tool(sessions).execute(
                SessionQueryTool.Input(select = "context_pressure", sessionId = "ghost"),
                ctx(),
            )
        }
        assertTrue("not found" in ex.message.orEmpty())
    }

    @Test fun emptySessionReportsZeroEstimateAndFullMargin() = runTest {
        val (sessions, _, sid) = fixture()
        val out = tool(sessions).execute(
            SessionQueryTool.Input(select = "context_pressure", sessionId = sid.value),
            ctx(),
        ).data
        val row = rows(out).single()
        assertEquals(0, row.currentEstimate)
        assertEquals(120_000, row.threshold, "Threshold must match Agent.compactionTokenThreshold default")
        assertEquals(0.0, row.ratio)
        assertEquals(120_000, row.marginTokens, "Empty session: full threshold is remaining margin")
        assertEquals(false, row.overThreshold)
        assertEquals(0, row.messageCount)
    }

    @Test fun belowThresholdReportsPositiveMargin() = runTest {
        val (sessions, _, sid) = fixture()
        // 400 chars → ~100 tokens at 4 chars/token. Well below threshold.
        appendUserWithText(sessions, sid, "m1", "a".repeat(400))

        val out = tool(sessions).execute(
            SessionQueryTool.Input(select = "context_pressure", sessionId = sid.value),
            ctx(),
        ).data
        val row = rows(out).single()
        assertTrue(row.currentEstimate in 90..110, "Expected ~100, got ${row.currentEstimate}")
        assertTrue(row.ratio < 1.0)
        assertTrue(row.marginTokens > 0, "Under threshold must report positive margin")
        assertEquals(false, row.overThreshold)
        assertEquals(1, row.messageCount)
    }

    @Test fun overThresholdReportsNegativeMarginAndUnclampedRatio() = runTest {
        val (sessions, _, sid) = fixture()
        // 600_000 chars → ~150_000 tokens at 4 chars/token. Over 120_000 threshold.
        // Uses a single Part.Text to drive TokenEstimator.forHistory past the
        // threshold without needing to fabricate many messages.
        appendUserWithText(sessions, sid, "m-big", "a".repeat(600_000))

        val out = tool(sessions).execute(
            SessionQueryTool.Input(select = "context_pressure", sessionId = sid.value),
            ctx(),
        ).data
        val row = rows(out).single()
        assertTrue(row.currentEstimate >= 120_000, "Expected over threshold, got ${row.currentEstimate}")
        assertTrue(row.ratio > 1.0, "Un-clamped ratio must surface over-threshold case; got ${row.ratio}")
        assertTrue(row.marginTokens < 0, "Over threshold must report negative margin; got ${row.marginTokens}")
        assertEquals(true, row.overThreshold)
    }

    @Test fun incompatibleFilterRejected() = runTest {
        // kind is a select=parts filter; passing it on context_pressure must
        // fail loud per the rejectIncompatibleFilters cross-field guard.
        val (sessions, _, sid) = fixture()
        val ex = assertFailsWith<IllegalStateException> {
            tool(sessions).execute(
                SessionQueryTool.Input(select = "context_pressure", sessionId = sid.value, kind = "text"),
                ctx(),
            )
        }
        assertTrue("kind" in ex.message.orEmpty())
    }

    @Test fun multipleMessagesSumAcrossHistory() = runTest {
        val (sessions, _, sid) = fixture()
        appendUserWithText(sessions, sid, "m1", "a".repeat(400)) // ~100 tokens
        appendUserWithText(sessions, sid, "m2", "b".repeat(800)) // ~200 tokens
        appendUserWithText(sessions, sid, "m3", "c".repeat(1200)) // ~300 tokens

        val out = tool(sessions).execute(
            SessionQueryTool.Input(select = "context_pressure", sessionId = sid.value),
            ctx(),
        ).data
        val row = rows(out).single()
        // ~600 tokens total. Allow slack for the 4-char-round-up.
        assertTrue(row.currentEstimate in 580..620, "Expected ~600, got ${row.currentEstimate}")
        assertEquals(3, row.messageCount)
    }
}
