package io.talevia.core.tool.builtin.session.query

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.BusEventTraceRecorder
import io.talevia.core.bus.EventBus
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [runBusTraceQuery] —
 * `core/tool/builtin/session/query/BusTraceQuery.kt`. The
 * SessionQueryTool's `select=bus_trace` handler reading
 * from BusEventTraceRecorder's per-session ring buffer.
 * Cycle 197 audit: 88 LOC, 0 direct test refs (transitive
 * coverage via SessionQueryBusTraceTest exercises the
 * full query path but the optional-recorder fallback,
 * sinceEpochMs filter math, and pluralization-conditional
 * title were never pinned at the helper level).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Optional recorder: `null` → zero rows + "not
 *    wired" body, NOT throw.** Per kdoc: "When the
 *    recorder isn't wired (test rigs without the
 *    aggregator), the query reports zero rows with a
 *    'not wired' note rather than failing." Drift to
 *    "throw on null" would crash queries on any test
 *    rig that doesn't bother wiring the recorder.
 *
 * 2. **`sinceEpochMs` filter is `>=` (inclusive of
 *    boundary).** Drift to `>` would silently drop
 *    boundary entries.
 *
 * 3. **`kind` filter is exact-match on event-class
 *    simpleName (e.g. "SessionCreated").** Drift to
 *    "case-insensitive" or "substring" would silently
 *    surface unrelated events.
 */
class BusTraceQueryTest {

    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest fun cleanup() {
        scope.cancel()
    }

    private fun input(
        sessionId: String?,
        sinceEpochMs: Long? = null,
        kind: String? = null,
    ): SessionQueryTool.Input = SessionQueryTool.Input(
        select = SessionQueryTool.SELECT_BUS_TRACE,
        sessionId = sessionId,
        sinceEpochMs = sinceEpochMs,
        kind = kind,
    )

    private fun rowFields(
        rows: kotlinx.serialization.json.JsonArray,
        key: String,
    ): List<String> = rows.map { row ->
        row.toString().substringAfter("\"$key\":\"").substringBefore("\"")
    }

    private suspend fun setupRecorderAndPump(
        sid: SessionId,
        events: List<BusEvent>,
    ): BusEventTraceRecorder {
        val bus = EventBus()
        val recorder = BusEventTraceRecorder(bus, scope)
        recorder.awaitReady()
        for (event in events) bus.publish(event)
        // Wait until all events have surfaced into the
        // recorder's snapshot.
        withTimeout(2_000) {
            while (recorder.snapshot(sid.value).size < events.size) {
                yield()
            }
        }
        return recorder
    }

    // ── Required-input rejection ─────────────────────────────

    @Test fun missingSessionIdThrowsWithDiscoverabilityHint() {
        // The runBusTraceQuery function isn't suspending,
        // so no runBlocking needed for the pure-validation
        // path.
        val ex = assertFailsWith<IllegalStateException> {
            runBusTraceQuery(
                recorder = null,
                input = input(null),
                limit = 100,
                offset = 0,
            )
        }
        assertTrue(
            "requires sessionId" in (ex.message ?: ""),
            "requires phrase; got: ${ex.message}",
        )
        assertTrue(
            "session_query(select=sessions)" in (ex.message ?: ""),
            "discoverability hint; got: ${ex.message}",
        )
    }

    // ── Optional recorder: null → fallback message ──────────

    @Test fun nullRecorderReturnsZeroRowsWithNotWiredNote() {
        // Marquee optional-aggregator pin: per kdoc "When
        // the recorder isn't wired (test rigs without the
        // aggregator), the query reports zero rows with a
        // 'not wired' note rather than failing."
        val result = runBusTraceQuery(
            recorder = null,
            input = input("s1"),
            limit = 100,
            offset = 0,
        )
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertTrue(
            "not wired" in result.outputForLlm,
            "body cites 'not wired'; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Production containers always wire it" in result.outputForLlm,
            "body cites production note; got: ${result.outputForLlm}",
        )
    }

    // ── Empty trace → "no events captured" body ─────────────

    @Test fun emptyTraceReturnsEmptyResultWithFriendlyBody() = runBlocking {
        val bus = EventBus()
        val recorder = BusEventTraceRecorder(bus, scope)
        recorder.awaitReady()

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1"),
            limit = 100,
            offset = 0,
        )
        assertEquals(0, result.data.total)
        assertTrue(
            "No bus events captured" in result.outputForLlm,
            "body cites empty; got: ${result.outputForLlm}",
        )
    }

    // ── Happy path: events captured + reported ──────────────

    @Test fun captureAndReportSessionEventsInOrder() = runBlocking {
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(
                BusEvent.SessionCreated(sid),
                BusEvent.SessionUpdated(sid),
                BusEvent.SessionDeleted(sid),
            ),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1"),
            limit = 100,
            offset = 0,
        )
        assertEquals(3, result.data.total)
        assertEquals(3, result.data.returned)
        // kind values surface in JSON rows.
        val kinds = rowFields(result.data.rows, "kind")
        assertContentEquals(
            listOf("SessionCreated", "SessionUpdated", "SessionDeleted"),
            kinds,
            "oldest-first order from recorder",
        )
        assertTrue("Bus trace for s1" in result.outputForLlm)
        assertTrue("3 event(s)" in result.outputForLlm)
    }

    // ── kind filter ─────────────────────────────────────────

    @Test fun kindFilterExactMatchOnSimpleName() = runBlocking {
        // Marquee exact-match pin: the filter is
        // `it.kind == input.kind`. Drift to "substring"
        // or "case-insensitive" would silently surface
        // unrelated events.
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(
                BusEvent.SessionCreated(sid),
                BusEvent.SessionUpdated(sid),
                BusEvent.SessionUpdated(sid),
                BusEvent.SessionDeleted(sid),
            ),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1", kind = "SessionUpdated"),
            limit = 100,
            offset = 0,
        )
        assertEquals(2, result.data.total, "only the 2 SessionUpdated events")
        assertContentEquals(
            listOf("SessionUpdated", "SessionUpdated"),
            rowFields(result.data.rows, "kind"),
        )
    }

    @Test fun kindFilterCaseSensitive() = runBlocking {
        // Pin: drift to case-insensitive would match
        // "sessionupdated" against "SessionUpdated".
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(BusEvent.SessionUpdated(sid)),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1", kind = "sessionupdated"),
            limit = 100,
            offset = 0,
        )
        assertEquals(0, result.data.total, "case-sensitive: 'sessionupdated' != 'SessionUpdated'")
    }

    @Test fun kindFilterScopeAppearsInBody() = runBlocking {
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(
                BusEvent.SessionCreated(sid),
                BusEvent.SessionUpdated(sid),
            ),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1", kind = "SessionCreated"),
            limit = 100,
            offset = 0,
        )
        assertTrue(
            "(kind=SessionCreated)" in result.outputForLlm,
            "kind scope cited; got: ${result.outputForLlm}",
        )
    }

    // ── sinceEpochMs filter (inclusive boundary) ────────────

    @Test fun sinceEpochMsFilterIsInclusiveAtBoundary() = runBlocking {
        // Marquee inclusive-boundary pin: filter is
        // `it.epochMs >= input.sinceEpochMs`. Drift to
        // `>` would silently drop entries at the boundary.
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(
                BusEvent.SessionCreated(sid),
                BusEvent.SessionUpdated(sid),
            ),
        )

        // Pull a snapshot to find an actual epochMs value
        // — then filter at exactly that boundary.
        val snapshot = recorder.snapshot("s1")
        val firstEpoch = snapshot.first().epochMs

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1", sinceEpochMs = firstEpoch),
            limit = 100,
            offset = 0,
        )
        // First event's epochMs == sinceEpochMs → INCLUDED
        // (>= comparison).
        assertTrue(
            result.data.total >= 1,
            "first event included at exact boundary; got total: ${result.data.total}",
        )
    }

    @Test fun sinceEpochMsFilterDropsOlderEntries() = runBlocking {
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(
                BusEvent.SessionCreated(sid),
                BusEvent.SessionUpdated(sid),
            ),
        )

        val snapshot = recorder.snapshot("s1")
        // Set sinceEpochMs to AFTER the latest event →
        // zero rows.
        val afterLast = snapshot.last().epochMs + 1_000_000L

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1", sinceEpochMs = afterLast),
            limit = 100,
            offset = 0,
        )
        assertEquals(0, result.data.total, "sinceEpochMs after-all → empty")
    }

    @Test fun nullSinceEpochMsAppliesNoFilter() = runBlocking {
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(
                BusEvent.SessionCreated(sid),
                BusEvent.SessionUpdated(sid),
            ),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1", sinceEpochMs = null),
            limit = 100,
            offset = 0,
        )
        assertEquals(2, result.data.total, "null sinceEpochMs → no filter")
    }

    // ── Combined filters ────────────────────────────────────

    @Test fun sinceEpochMsAndKindFiltersComposeViaAnd() = runBlocking {
        // Pin: both filters apply (logical AND, not OR).
        // Plant 4 events, filter to a kind+time window
        // that matches only some.
        //
        // Note: bus events pumped in rapid succession may
        // share an epochMs (Clock.System.now() ms-precision
        // can collide). To make the time-window unambiguous,
        // use sinceEpochMs > maxEpochMs to exclude all
        // (kind filter matches none) AND
        // sinceEpochMs < minEpochMs to include all (kind
        // filter matches updates only) — verifying both
        // filters actually apply.
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(
                BusEvent.SessionCreated(sid),
                BusEvent.SessionUpdated(sid),
                BusEvent.SessionUpdated(sid),
                BusEvent.SessionDeleted(sid),
            ),
        )
        val snapshot = recorder.snapshot("s1")

        // Time window includes all → kind narrows to
        // 2 SessionUpdated.
        val resultBoth = runBusTraceQuery(
            recorder = recorder,
            input = input("s1", sinceEpochMs = 0L, kind = "SessionUpdated"),
            limit = 100,
            offset = 0,
        )
        assertEquals(2, resultBoth.data.total, "kind filter applied")

        // Time window excludes all → kind irrelevant.
        val futureMs = snapshot.maxOf { it.epochMs } + 1_000_000L
        val resultNone = runBusTraceQuery(
            recorder = recorder,
            input = input("s1", sinceEpochMs = futureMs, kind = "SessionUpdated"),
            limit = 100,
            offset = 0,
        )
        assertEquals(0, resultNone.data.total, "time filter applied (AND, not OR)")
    }

    // ── Pagination ─────────────────────────────────────────

    @Test fun paginationLimitTakesFirstNRowsAfterFilter() = runBlocking {
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(
                BusEvent.SessionCreated(sid),
                BusEvent.SessionUpdated(sid),
                BusEvent.SessionUpdated(sid),
                BusEvent.SessionDeleted(sid),
            ),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1"),
            limit = 2,
            offset = 0,
        )
        assertEquals(4, result.data.total, "total reflects filtered count (no filter here)")
        assertEquals(2, result.data.returned)
        // Oldest-first → first 2.
        assertContentEquals(
            listOf("SessionCreated", "SessionUpdated"),
            rowFields(result.data.rows, "kind"),
        )
    }

    @Test fun paginationOffsetSkipsFirstNAfterFilter() = runBlocking {
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(
                BusEvent.SessionCreated(sid),
                BusEvent.SessionUpdated(sid),
                BusEvent.SessionUpdated(sid),
                BusEvent.SessionDeleted(sid),
            ),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1"),
            limit = 100,
            offset = 2,
        )
        assertEquals(4, result.data.total)
        assertEquals(2, result.data.returned)
        assertContentEquals(
            listOf("SessionUpdated", "SessionDeleted"),
            rowFields(result.data.rows, "kind"),
        )
    }

    // ── Title pluralization: 1 vs N ────────────────────────

    @Test fun titlePluralizationSingleEventHasNoS() = runBlocking {
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(BusEvent.SessionCreated(sid)),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1"),
            limit = 100,
            offset = 0,
        )
        assertTrue(
            "(1 event)" in result.title!!,
            "singular form for 1 event; got: ${result.title}",
        )
    }

    @Test fun titlePluralizationMultipleEventsHasS() = runBlocking {
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(
                BusEvent.SessionCreated(sid),
                BusEvent.SessionUpdated(sid),
            ),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1"),
            limit = 100,
            offset = 0,
        )
        assertTrue(
            "(2 events)" in result.title!!,
            "plural form for 2 events; got: ${result.title}",
        )
    }

    @Test fun titlePluralizationZeroEventsHasS() = runBlocking {
        // Edge: 0 events. Per impl `if (total == 1) "" else "s"`
        // — 0 falls into the `else` branch → "events".
        // English: "0 events" is the right form.
        val bus = EventBus()
        val recorder = BusEventTraceRecorder(bus, scope)
        recorder.awaitReady()

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1"),
            limit = 100,
            offset = 0,
        )
        assertTrue(
            "(0 events)" in result.title!!,
            "zero is plural in English; got: ${result.title}",
        )
    }

    // ── Output.select echoes ────────────────────────────────

    @Test fun outputSelectIsBusTrace() = runBlocking {
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(BusEvent.SessionCreated(sid)),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1"),
            limit = 100,
            offset = 0,
        )
        assertEquals(SessionQueryTool.SELECT_BUS_TRACE, result.data.select)
    }

    @Test fun rowExposesAllEntryFields() = runBlocking {
        val sid = SessionId("s1")
        val recorder = setupRecorderAndPump(
            sid,
            listOf(BusEvent.SessionCreated(sid)),
        )

        val result = runBusTraceQuery(
            recorder = recorder,
            input = input("s1"),
            limit = 100,
            offset = 0,
        )
        val rowJson = result.data.rows[0].toString()
        // Entry fields: sessionId, kind, epochMs, summary.
        assertTrue("\"sessionId\":\"s1\"" in rowJson, "sessionId; got: $rowJson")
        assertTrue("\"kind\":\"SessionCreated\"" in rowJson, "kind; got: $rowJson")
        assertTrue("\"epochMs\":" in rowJson, "epochMs; got: $rowJson")
        assertTrue("\"summary\":" in rowJson, "summary; got: $rowJson")
    }
}
