package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.CancellationHistoryRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Coverage for `session_query(select=cancellation_history)`. Edges (§3a #9):
 *  - no cancelled turns → empty rows + distinctive narrative.
 *  - cancelled turn with in-flight tools → row reports the count + distinct
 *    toolIds (set to the `"cancelled: <reason>"` Failed state that
 *    `finalizeCancelled` stamps).
 *  - cancelled turn without in-flight tools → zero count + empty ids list.
 *  - mixed error / cancelled / end_turn messages → only CANCELLED ones surface.
 *  - messageId filter → narrows to one specific cancelled turn.
 *  - sessionId missing → loud reject.
 *  - same toolId appearing twice in in-flight → `distinct().sorted()` dedupe.
 */
class SessionQueryCancellationHistoryTest {

    private data class Rig(
        val tool: SessionQueryTool,
        val sessions: SqlDelightSessionStore,
        val ctx: ToolContext,
    )

    private fun rig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { TaleviaDb.Schema.create(it) }
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val sessions = SqlDelightSessionStore(db, bus)
        val tool = SessionQueryTool(sessions = sessions)
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(tool, sessions, ctx)
    }

    private suspend fun seedSession(
        sessions: SqlDelightSessionStore,
        sid: SessionId,
        assistants: List<Message.Assistant>,
        parts: List<Part> = emptyList(),
    ) {
        sessions.createSession(
            Session(
                id = sid,
                projectId = ProjectId("p"),
                title = "cancel-history",
                createdAt = Instant.fromEpochMilliseconds(0),
                updatedAt = Instant.fromEpochMilliseconds(0),
            ),
        )
        assistants.forEach { sessions.appendMessage(it) }
        parts.forEach { sessions.upsertPart(it) }
    }

    private fun assistant(
        id: String,
        sid: SessionId,
        epochMs: Long,
        finish: FinishReason,
        error: String? = null,
    ): Message.Assistant = Message.Assistant(
        id = MessageId(id),
        sessionId = sid,
        createdAt = Instant.fromEpochMilliseconds(epochMs),
        parentId = MessageId("u-$id"),
        model = ModelRef("anthropic", "claude-sonnet-4-6"),
        finish = finish,
        error = error,
        tokens = TokenUsage(input = 10, output = 5),
    )

    private fun toolPart(
        partId: String,
        messageId: String,
        sid: SessionId,
        toolId: String,
        cancelledMessage: String,
    ): Part.Tool = Part.Tool(
        id = PartId(partId),
        messageId = MessageId(messageId),
        sessionId = sid,
        createdAt = Instant.fromEpochMilliseconds(0),
        callId = CallId("call-$partId"),
        toolId = toolId,
        state = ToolState.Failed(input = JsonObject(emptyMap()), message = cancelledMessage),
    )

    private fun decode(out: SessionQueryTool.Output): List<CancellationHistoryRow> =
        out.rows.decodeRowsAs(CancellationHistoryRow.serializer())

    @Test fun noCancelledTurnsEmitEmpty() = runTest {
        val r = rig()
        val sid = SessionId("s")
        seedSession(r.sessions, sid, listOf(assistant("a1", sid, 1_000, FinishReason.END_TURN)))
        val out = r.tool.execute(
            SessionQueryTool.Input(select = "cancellation_history", sessionId = "s"),
            r.ctx,
        )
        assertEquals(0, out.data.total)
        assertTrue("no cancelled" in out.outputForLlm.lowercase(), out.outputForLlm)
    }

    @Test fun cancelledTurnWithInFlightToolsReportsCountAndIds() = runTest {
        val r = rig()
        val sid = SessionId("s")
        seedSession(
            r.sessions,
            sid,
            assistants = listOf(
                assistant("a-cancel", sid, 1_000, FinishReason.CANCELLED, error = "user ctrl-c"),
            ),
            parts = listOf(
                toolPart("p1", "a-cancel", sid, "generate_image", "cancelled: user ctrl-c"),
                toolPart("p2", "a-cancel", sid, "generate_image", "cancelled: user ctrl-c"),
                toolPart("p3", "a-cancel", sid, "synthesize_speech", "cancelled: user ctrl-c"),
                // Same-message normal Failed (not cancelled) — must NOT count.
                toolPart("p4", "a-cancel", sid, "read_file", "ENOENT"),
            ),
        )
        val rows = decode(
            r.tool.execute(
                SessionQueryTool.Input(select = "cancellation_history", sessionId = "s"),
                r.ctx,
            ).data,
        )
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("a-cancel", row.messageId)
        assertEquals("user ctrl-c", row.reason)
        assertEquals(3, row.inFlightToolCallCount, "only cancelled-prefix tool parts count")
        assertEquals(
            listOf("generate_image", "synthesize_speech"),
            row.inFlightToolIds,
            "distinct, sorted",
        )
    }

    @Test fun cancelledTurnWithNoInFlightToolsReportsZero() = runTest {
        val r = rig()
        val sid = SessionId("s")
        seedSession(
            r.sessions,
            sid,
            assistants = listOf(assistant("a-early", sid, 1_000, FinishReason.CANCELLED, error = null)),
        )
        val rows = decode(
            r.tool.execute(
                SessionQueryTool.Input(select = "cancellation_history", sessionId = "s"),
                r.ctx,
            ).data,
        )
        assertEquals(1, rows.size)
        assertEquals(0, rows.single().inFlightToolCallCount)
        assertEquals(emptyList(), rows.single().inFlightToolIds)
        assertEquals(null, rows.single().reason)
    }

    @Test fun mixedOutcomesFilterToCancelledOnly() = runTest {
        val r = rig()
        val sid = SessionId("s")
        seedSession(
            r.sessions,
            sid,
            assistants = listOf(
                assistant("a1", sid, 1_000, FinishReason.END_TURN),
                assistant("a2", sid, 2_000, FinishReason.CANCELLED, error = "ctrl-c"),
                assistant("a3", sid, 3_000, FinishReason.ERROR, error = "boom"),
                assistant("a4", sid, 4_000, FinishReason.CANCELLED, error = "ctrl-c"),
            ),
        )
        val rows = decode(
            r.tool.execute(
                SessionQueryTool.Input(select = "cancellation_history", sessionId = "s"),
                r.ctx,
            ).data,
        )
        assertEquals(listOf("a2", "a4"), rows.map { it.messageId }, "oldest-first, only CANCELLED turns")
    }

    @Test fun messageIdFilterNarrowsToOne() = runTest {
        val r = rig()
        val sid = SessionId("s")
        seedSession(
            r.sessions,
            sid,
            assistants = listOf(
                assistant("a1", sid, 1_000, FinishReason.CANCELLED, error = "r1"),
                assistant("a2", sid, 2_000, FinishReason.CANCELLED, error = "r2"),
            ),
        )
        val rows = decode(
            r.tool.execute(
                SessionQueryTool.Input(select = "cancellation_history", sessionId = "s", messageId = "a2"),
                r.ctx,
            ).data,
        )
        assertEquals(1, rows.size)
        assertEquals("a2", rows.single().messageId)
    }

    @Test fun unknownMessageIdEmitsEmptyWithDiagnostic() = runTest {
        val r = rig()
        val sid = SessionId("s")
        seedSession(
            r.sessions,
            sid,
            assistants = listOf(assistant("a1", sid, 1_000, FinishReason.CANCELLED)),
        )
        val out = r.tool.execute(
            SessionQueryTool.Input(select = "cancellation_history", sessionId = "s", messageId = "nope"),
            r.ctx,
        )
        assertEquals(0, out.data.total)
        assertTrue("messageId='nope'" in out.outputForLlm, out.outputForLlm)
    }

    @Test fun sessionIdMissingRejectsLoud() = runTest {
        val r = rig()
        val ex = assertFailsWith<IllegalStateException> {
            r.tool.execute(
                SessionQueryTool.Input(select = "cancellation_history"),
                r.ctx,
            )
        }
        assertTrue("requires sessionId" in ex.message!!, ex.message)
    }
}
