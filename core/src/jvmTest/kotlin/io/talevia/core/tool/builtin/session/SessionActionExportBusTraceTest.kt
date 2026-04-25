package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.BusEventTraceRecorder
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for `session_action(action="export_bus_trace", ...)` —
 * the operations-side flush from `BusEventTraceRecorder` to JSONL /
 * JSON for offline triage.
 */
class SessionActionExportBusTraceTest {

    private data class Rig(
        val store: SqlDelightSessionStore,
        val recorder: BusEventTraceRecorder,
        val ctx: ToolContext,
    )

    private suspend fun rig(
        sessionIdValue: String = "s-trace",
        seedEventCount: Int = 3,
    ): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(TaleviaDb(driver), bus)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = BusEventTraceRecorder(bus = bus, scope = scope)
        recorder.awaitReady()
        val ctx = ToolContext(
            sessionId = SessionId(sessionIdValue),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        // Seed a session so resolveSessionId has something to point at.
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = SessionId(sessionIdValue),
                projectId = ProjectId("p"),
                title = "trace",
                createdAt = now,
                updatedAt = now,
            ),
        )
        if (seedEventCount > 0) {
            // Fire session-scoped events the recorder will capture.
            // The recorder's collector runs on Default (different
            // dispatcher from runTest's virtual scheduler), so we
            // can't use `delay()` to settle — barrier on the
            // recorder's StateFlow until the expected count lands.
            withContext(Dispatchers.Default) {
                repeat(seedEventCount) { i ->
                    bus.publish(
                        BusEvent.PartDelta(
                            SessionId(sessionIdValue),
                            MessageId("m"),
                            PartId("p"),
                            "text",
                            "event-$i",
                        ),
                    )
                }
                withTimeout(5.seconds) {
                    recorder.records.first { (it[sessionIdValue]?.size ?: 0) >= seedEventCount }
                }
            }
        }
        return Rig(store, recorder, ctx)
    }

    @Test fun jsonlExportEmitsOneEntryPerLineDefault() = runTest {
        val rig = rig()
        val out = SessionActionTool(
            sessions = rig.store,
            busTrace = rig.recorder,
        ).execute(
            SessionActionTool.Input(action = "export_bus_trace"),
            rig.ctx,
        ).data

        assertEquals("export_bus_trace", out.action)
        assertEquals("jsonl", out.exportedTraceFormat, "default format must be jsonl")
        assertTrue(out.exportedTraceEntryCount >= 1, "expected captured entries; got ${out.exportedTraceEntryCount}")
        // jsonl: one JSON object per line, no array brackets.
        val lines = out.exportedBusTrace.lines().filter { it.isNotBlank() }
        assertEquals(out.exportedTraceEntryCount, lines.size, "lines must match entry count")
        assertTrue(lines.all { it.startsWith("{") && it.endsWith("}") }, "each line must be a JSON object")
        assertTrue(out.exportedBusTrace.first() != '[', "jsonl must NOT wrap in an array")
    }

    @Test fun jsonExportEmitsSingleArray() = runTest {
        val rig = rig()
        val out = SessionActionTool(
            sessions = rig.store,
            busTrace = rig.recorder,
        ).execute(
            SessionActionTool.Input(action = "export_bus_trace", format = "json"),
            rig.ctx,
        ).data

        assertEquals("json", out.exportedTraceFormat)
        assertTrue(out.exportedBusTrace.startsWith("["), "json format must wrap in array")
        assertTrue(out.exportedBusTrace.endsWith("]"))
        // Round-trip via the recorder's own serializer to confirm shape.
        val decoded = JsonConfig.default.decodeFromString(
            ListSerializer(BusEventTraceRecorder.Entry.serializer()),
            out.exportedBusTrace,
        )
        assertEquals(out.exportedTraceEntryCount, decoded.size)
    }

    @Test fun limitCapsToMostRecentEntries() = runTest {
        val rig = rig()
        val total = rig.recorder.snapshot("s-trace").size
        check(total >= 3) { "rig setup expected ≥ 3 events; got $total" }

        val out = SessionActionTool(
            sessions = rig.store,
            busTrace = rig.recorder,
        ).execute(
            SessionActionTool.Input(action = "export_bus_trace", limit = 2),
            rig.ctx,
        ).data

        assertEquals(2, out.exportedTraceEntryCount, "limit=2 must cap at 2 entries")
        // limit takes the MOST RECENT entries; the body must contain
        // at least one of the last 2 emits (event-1 / event-2). The
        // first emit (event-0) was dropped by the cap.
        assertTrue(
            "event-1" in out.exportedBusTrace || "event-2" in out.exportedBusTrace,
            "limit must keep the most-recent entries; got: ${out.exportedBusTrace}",
        )
    }

    @Test fun unknownFormatFailsLoud() = runTest {
        val rig = rig()
        val ex = assertFailsWith<IllegalArgumentException> {
            SessionActionTool(
                sessions = rig.store,
                busTrace = rig.recorder,
            ).execute(
                SessionActionTool.Input(action = "export_bus_trace", format = "yaml"),
                rig.ctx,
            )
        }
        assertTrue("yaml" in ex.message.orEmpty(), "error must echo the bad format")
        assertTrue("jsonl" in ex.message.orEmpty() && "json" in ex.message.orEmpty(), "error must list legal values")
    }

    @Test fun zeroOrNegativeLimitFailsLoud() = runTest {
        val rig = rig()
        val ex0 = assertFailsWith<IllegalArgumentException> {
            SessionActionTool(
                sessions = rig.store,
                busTrace = rig.recorder,
            ).execute(
                SessionActionTool.Input(action = "export_bus_trace", limit = 0),
                rig.ctx,
            )
        }
        assertTrue("limit" in ex0.message.orEmpty())
        val exNeg = assertFailsWith<IllegalArgumentException> {
            SessionActionTool(
                sessions = rig.store,
                busTrace = rig.recorder,
            ).execute(
                SessionActionTool.Input(action = "export_bus_trace", limit = -5),
                rig.ctx,
            )
        }
        assertTrue("limit" in exNeg.message.orEmpty())
    }

    @Test fun missingRecorderFailsLoud() = runTest {
        val rig = rig()
        // Construct without the recorder injection to model the
        // "container forgot to wire it" error path.
        val ex = assertFailsWith<IllegalStateException> {
            SessionActionTool(sessions = rig.store).execute(
                SessionActionTool.Input(action = "export_bus_trace"),
                rig.ctx,
            )
        }
        assertTrue("BusEventTraceRecorder" in ex.message.orEmpty())
        assertTrue("DefaultBuiltinRegistrations" in ex.message.orEmpty())
    }

    @Test fun emptyTraceRendersFriendlyOutputForLlm() = runTest {
        val rig = rig(sessionIdValue = "s-empty", seedEventCount = 0)
        val out = SessionActionTool(
            sessions = rig.store,
            busTrace = rig.recorder,
        ).execute(
            SessionActionTool.Input(action = "export_bus_trace"),
            rig.ctx,
        )
        assertEquals(0, out.data.exportedTraceEntryCount)
        assertTrue("No bus events captured" in out.outputForLlm, "outputForLlm must hint at the empty case")
    }
}
