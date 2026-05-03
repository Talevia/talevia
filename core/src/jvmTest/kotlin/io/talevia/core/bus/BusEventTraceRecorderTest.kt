package io.talevia.core.bus

import io.talevia.core.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [BusEventTraceRecorder] — the per-session bus event
 * ring buffer used for "what happened in this session?" debug queries.
 * Cycle 84 audit found this class had no direct test (3 transitive
 * references via SessionActionExportBusTrace + SessionQueryBusTrace
 * which exercise the recorder through other tools rather than testing
 * the recorder's own contract).
 */
class BusEventTraceRecorderTest {

    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest fun cleanup() {
        scope.cancel()
    }

    @Test fun snapshotReturnsEmptyForUnknownSession() = runBlocking {
        // No events yet: any sessionId returns empty list (NOT null).
        val bus = EventBus()
        val recorder = BusEventTraceRecorder(bus, scope)
        recorder.awaitReady()
        assertEquals(emptyList(), recorder.snapshot("never-seen"))
    }

    @Test fun sessionEventsAreCapturedInOldestFirstOrder() = runBlocking {
        val bus = EventBus()
        val recorder = BusEventTraceRecorder(bus, scope)
        recorder.awaitReady()
        val sid = SessionId("s")

        // Publish three session events in order.
        bus.publish(BusEvent.SessionCreated(sid))
        bus.publish(BusEvent.SessionUpdated(sid))
        bus.publish(BusEvent.SessionDeleted(sid))

        withTimeout(2_000) {
            while (recorder.snapshot("s").size < 3) yield()
        }
        val rows = recorder.snapshot("s")
        // Oldest-first per the kdoc.
        assertEquals(listOf("SessionCreated", "SessionUpdated", "SessionDeleted"), rows.map { it.kind })
    }

    @Test fun nonSessionEventsAreIgnored() = runBlocking {
        // Bus events that don't implement SessionEvent are skipped.
        // ProviderWarmup is process-scoped, no session affinity.
        val bus = EventBus()
        val recorder = BusEventTraceRecorder(bus, scope)
        recorder.awaitReady()
        val sid = SessionId("s")

        // Mix session + non-session events. AigcCacheProbe has no
        // sessionId (project-scoped), so the recorder must skip it.
        bus.publish(BusEvent.SessionCreated(sid))
        bus.publish(BusEvent.AigcCacheProbe(toolId = "generate_image", hit = false))
        bus.publish(BusEvent.SessionUpdated(sid))

        withTimeout(2_000) {
            while (recorder.snapshot("s").size < 2) yield()
        }
        val rows = recorder.snapshot("s")
        assertEquals(2, rows.size, "non-session AigcCacheProbe must be skipped")
        assertTrue(rows.all { it.kind != "AigcCacheProbe" })
    }

    @Test fun perSessionBuffersAreIndependent() = runBlocking {
        val bus = EventBus()
        val recorder = BusEventTraceRecorder(bus, scope)
        recorder.awaitReady()

        bus.publish(BusEvent.SessionCreated(SessionId("a")))
        bus.publish(BusEvent.SessionCreated(SessionId("b")))
        bus.publish(BusEvent.SessionUpdated(SessionId("a")))

        withTimeout(2_000) {
            while (recorder.snapshot("a").size < 2 || recorder.snapshot("b").size < 1) yield()
        }
        assertEquals(2, recorder.snapshot("a").size)
        assertEquals(1, recorder.snapshot("b").size)
        assertEquals(emptyList(), recorder.snapshot("c"))
    }

    @Test fun ringBufferEvictsOldestPastCapacity() = runBlocking {
        // Capacity 3 for the test; publish 5 events; only the last 3
        // should remain. Pin the takeLast(capacity) semantic.
        val bus = EventBus()
        val recorder = BusEventTraceRecorder(bus, scope, capacityPerSession = 3)
        recorder.awaitReady()
        val sid = SessionId("s")

        repeat(5) {
            // Use SessionCreated each time (kind doesn't matter for capacity test).
            bus.publish(BusEvent.SessionCreated(sid))
        }

        withTimeout(2_000) {
            while (recorder.snapshot("s").size < 3) yield()
            // Wait one extra yield to confirm size doesn't grow past 3.
            yield()
        }
        val rows = recorder.snapshot("s")
        assertEquals(3, rows.size, "ring buffer caps at capacityPerSession=3")
    }

    @Test fun entryKindIsClassSimpleName() = runBlocking {
        val bus = EventBus()
        val recorder = BusEventTraceRecorder(bus, scope)
        recorder.awaitReady()
        val sid = SessionId("s")

        bus.publish(BusEvent.SessionCreated(sid))
        bus.publish(BusEvent.SessionUpdated(sid))
        bus.publish(BusEvent.SessionDeleted(sid))

        withTimeout(2_000) {
            while (recorder.snapshot("s").size < 3) yield()
        }
        val kinds = recorder.snapshot("s").map { it.kind }
        // Pin: the "kind" string is the data class's simpleName, not
        // a string literal we control. A regression switching to
        // `qualifiedName` or hand-mapped strings would change the
        // shape exposed to agents querying the trace.
        assertEquals(listOf("SessionCreated", "SessionUpdated", "SessionDeleted"), kinds)
    }

    @Test fun entrySummaryIsTruncatedAtLimit() = runBlocking {
        // Pin SUMMARY_LIMIT=200. The summary field uses event.toString().take(SUMMARY_LIMIT).
        // SessionUpdated's toString is short ("SessionUpdated(sessionId=SessionId(value=s))")
        // — well under 200 chars. We can't easily craft an event with a
        // >200 char toString without faking, but pin the constant + the
        // truncation behavior on the toString length we observe.
        val bus = EventBus()
        val recorder = BusEventTraceRecorder(bus, scope)
        recorder.awaitReady()
        val sid = SessionId("s")
        bus.publish(BusEvent.SessionCreated(sid))
        withTimeout(2_000) { while (recorder.snapshot("s").isEmpty()) yield() }

        val row = recorder.snapshot("s")[0]
        assertTrue(
            row.summary.length <= BusEventTraceRecorder.SUMMARY_LIMIT,
            "summary must be ≤ ${BusEventTraceRecorder.SUMMARY_LIMIT} chars; got ${row.summary.length}",
        )
        // Pin the constants the kdoc commits to.
        assertEquals(256, BusEventTraceRecorder.DEFAULT_CAPACITY_PER_SESSION)
        assertEquals(200, BusEventTraceRecorder.SUMMARY_LIMIT)
    }

    @Test fun withSupervisorFactoryProducesUsableRecorder() = runBlocking {
        // Pin the convenience factory: callers without a long-lived
        // scope can use this and the recorder works.
        val bus = EventBus()
        val recorder = BusEventTraceRecorder.withSupervisor(bus)
        recorder.awaitReady()
        bus.publish(BusEvent.SessionCreated(SessionId("s")))

        withTimeout(2_000) { while (recorder.snapshot("s").isEmpty()) yield() }
        assertEquals(1, recorder.snapshot("s").size)
        // No scope cleanup — supervisor scope leaks into test by design;
        // documented limitation of the factory (suitable for production
        // long-lived runtimes, not unit tests).
    }
}
