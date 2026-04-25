package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.BusEventTraceRecorder
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Part
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.BusTraceRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for `session_query(select=bus_trace)` — per-session ring
 * buffer of session-bound bus events.
 */
class SessionQueryBusTraceTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private fun emptySessionStore(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    @Test fun unwiredRecorderReportsZeroRowsWithNote(): TestResult = runTest {
        val tool = SessionQueryTool(emptySessionStore())
        val result = tool.execute(
            SessionQueryTool.Input(select = "bus_trace", sessionId = "s1"),
            ctx(),
        )
        assertEquals(0, result.data.total)
        assertTrue("expected 'not wired' note: ${result.outputForLlm}") {
            result.outputForLlm.contains("not wired")
        }
    }

    @Test fun rejectsMissingSessionId(): TestResult = runTest {
        val recorder = BusEventTraceRecorder.withSupervisor(EventBus())
        val tool = SessionQueryTool(emptySessionStore(), busTrace = recorder)
        kotlin.test.assertFailsWith<IllegalStateException> {
            tool.execute(SessionQueryTool.Input(select = "bus_trace"), ctx())
        }
    }

    @Test fun capturesSessionEventsAndFiltersByKind(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val bus = EventBus()
            val recorder = BusEventTraceRecorder(
                bus,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
            recorder.awaitReady()

            // Three events on s1 (two PartUpdated, one PartDelta), one on s2.
            // The recorder should bucket per-session and tag each by class
            // simple-name.
            fun textPart(partId: String) = Part.Text(
                id = PartId(partId),
                messageId = MessageId("m1"),
                sessionId = SessionId("s1"),
                createdAt = Instant.fromEpochMilliseconds(0),
                text = "",
            )
            bus.publish(
                BusEvent.PartUpdated(
                    sessionId = SessionId("s1"),
                    messageId = MessageId("m1"),
                    partId = PartId("p1"),
                    part = textPart("p1"),
                ),
            )
            bus.publish(
                BusEvent.PartDelta(
                    sessionId = SessionId("s1"),
                    messageId = MessageId("m1"),
                    partId = PartId("p1"),
                    field = "text",
                    delta = "hello",
                ),
            )
            bus.publish(
                BusEvent.PartUpdated(
                    sessionId = SessionId("s1"),
                    messageId = MessageId("m1"),
                    partId = PartId("p2"),
                    part = textPart("p2"),
                ),
            )
            bus.publish(BusEvent.SessionCancelled(SessionId("s2")))

            withTimeout(5.seconds) {
                while (recorder.snapshot("s1").size < 3) yield()
            }

            val tool = SessionQueryTool(emptySessionStore(), busTrace = recorder)
            // No filter — should see all 3 s1 events.
            val all = tool.execute(
                SessionQueryTool.Input(select = "bus_trace", sessionId = "s1"),
                ctx(),
            ).data
            assertEquals(3, all.total)
            val rows = all.rows.decodeRowsAs(BusTraceRow.serializer())
            assertEquals(listOf("PartUpdated", "PartDelta", "PartUpdated"), rows.map { it.kind })
            assertTrue(rows.all { it.sessionId == "s1" }, "all rows must be s1")

            // Filter by kind=PartDelta — should see only 1 row.
            val deltas = tool.execute(
                SessionQueryTool.Input(select = "bus_trace", sessionId = "s1", kind = "PartDelta"),
                ctx(),
            ).data
            assertEquals(1, deltas.total)
            val deltaRows = deltas.rows.decodeRowsAs(BusTraceRow.serializer())
            assertEquals("PartDelta", deltaRows.single().kind)
            assertTrue(
                deltaRows.single().summary.contains("hello"),
                "summary should preserve event toString fields",
            )

            // Cross-session isolation: s2 sees only its own event.
            val s2 = tool.execute(
                SessionQueryTool.Input(select = "bus_trace", sessionId = "s2"),
                ctx(),
            ).data
            assertEquals(1, s2.total)
            val s2Rows = s2.rows.decodeRowsAs(BusTraceRow.serializer())
            assertEquals("SessionCancelled", s2Rows.single().kind)
        }
    }

    @Test fun ringBufferRotatesAtCapacity(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val bus = EventBus()
            // Cap=4 — easier to assert rotation than the production 256.
            val recorder = BusEventTraceRecorder(
                bus,
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
                capacityPerSession = 4,
            )
            recorder.awaitReady()

            // Publish 6 events; ring buffer of cap 4 should keep the latest 4.
            fun textPart(partId: String) = Part.Text(
                id = PartId(partId),
                messageId = MessageId("m"),
                sessionId = SessionId("s"),
                createdAt = Instant.fromEpochMilliseconds(0),
                text = "",
            )
            repeat(6) { i ->
                bus.publish(
                    BusEvent.PartUpdated(
                        sessionId = SessionId("s"),
                        messageId = MessageId("m$i"),
                        partId = PartId("p$i"),
                        part = textPart("p$i"),
                    ),
                )
            }

            withTimeout(5.seconds) {
                while (recorder.snapshot("s").size < 4) yield()
            }
            // Yield a few more rounds to confirm the buffer settles at cap.
            repeat(5) { yield() }

            val snap = recorder.snapshot("s")
            assertEquals(4, snap.size, "ring buffer caps at 4 — older events rotated out")
            assertTrue(snap.all { it.kind == "PartUpdated" })
        }
    }
}
