package io.talevia.core.tool.builtin.session.query

import io.talevia.core.JsonConfig
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.permission.PermissionHistoryRecorder
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for [runPermissionHistoryQuery] —
 * `session_query(select=permission_history)`. Reads the
 * in-memory [PermissionHistoryRecorder] so the agent can
 * answer "did the user already say no to this permission?"
 * within the same process. Cycle 142 audit: 114 LOC, 0
 * transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`sessionId` required; recorder=null is graceful.**
 *    Missing sessionId → `error()` naming the SELECT and
 *    pointing at sessions discovery. Recorder=null (test
 *    rigs without permission UX wired) → 0 rows + dedicated
 *    "not wired" narrative. The two cases must be
 *    distinguishable so the agent doesn't conflate "no
 *    history yet" with "I'm in a no-permission rig."
 *
 * 2. **`decision` quad-state mapping with pending-first
 *    precedence.** `accepted=null → "pending"`, then
 *    `remembered=true → "always"`, then `accepted=true →
 *    "once"`, else `"reject"`. The pending check must
 *    take precedence — a pending entry with `remembered=null`
 *    must not slip into a non-pending bucket.
 *
 * 3. **Narrative tally counts must not NPE on pending
 *    entries.** Pre-fix the production code does
 *    `rowsAll.count { !it.accepted!!.let { acc -> acc } || it.decision == "reject" }`
 *    which `!!`-unwraps a possibly-null `accepted` for any
 *    pending row → NPE if any pending row exists. Pinned
 *    by a mixed (pending + reject) snapshot: the query
 *    must complete and report rejected=1, pending=1 in
 *    narrative.
 */
class PermissionHistoryQueryTest {

    private suspend fun waitFor(
        recorder: PermissionHistoryRecorder,
        sessionId: String,
        predicate: (List<PermissionHistoryRecorder.Entry>) -> Boolean,
    ) {
        withTimeout(5.seconds) {
            while (!predicate(recorder.snapshot(sessionId))) yield()
        }
    }

    private suspend fun setupRecorder(): Pair<PermissionHistoryRecorder, EventBus> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus()
        val recorder = PermissionHistoryRecorder(bus, scope)
        recorder.awaitReady()
        return recorder to bus
    }

    private fun input(sessionId: String? = "s1") = SessionQueryTool.Input(
        select = "permission_history",
        sessionId = sessionId,
    )

    private fun decodeRows(out: SessionQueryTool.Output): List<PermissionHistoryRow> =
        JsonConfig.default.decodeFromJsonElement(
            ListSerializer(PermissionHistoryRow.serializer()),
            out.rows,
        )

    // ── input validation ──────────────────────────────────────────

    @Test fun missingSessionIdFailsLoudWithDiscoveryHint() {
        val ex = assertFailsWith<IllegalStateException> {
            runPermissionHistoryQuery(recorder = null, input(sessionId = null), 100, 0)
        }
        val msg = ex.message.orEmpty()
        assertTrue("permission_history" in msg, "select named; got: $msg")
        assertTrue("sessionId" in msg, "field named; got: $msg")
        assertTrue(
            "session_query(select=sessions)" in msg,
            "discovery hint included; got: $msg",
        )
    }

    // ── recorder=null path ───────────────────────────────────────

    @Test fun nullRecorderReturnsZeroRowsWithNotWiredNarrative() {
        // Pin: recorder=null is the "test rig without permission
        // UX wired" path — must NOT throw, must return 0 rows
        // and a narrative that distinguishes the case from
        // "no history yet."
        val result = runPermissionHistoryQuery(recorder = null, input(), 100, 0)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
        assertTrue(decodeRows(result.data).isEmpty())
        val out = result.outputForLlm
        assertTrue(
            "not wired" in out,
            "narrative distinguishes from empty-history case; got: $out",
        )
    }

    // ── recorder wired but no entries ────────────────────────────

    @Test fun recorderWiredButEmptyForSessionShowsHistoryEmptyNarrative(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (recorder, _) = setupRecorder()
            val result = runPermissionHistoryQuery(recorder, input(), 100, 0)
            assertEquals(0, result.data.total)
            assertTrue(
                "no permission asks recorded" in result.outputForLlm,
                "got: ${result.outputForLlm}",
            )
            // Must NOT use the "not wired" phrasing — wired but
            // empty is a distinct state.
            assertTrue(
                "not wired" !in result.outputForLlm,
                "wired-but-empty != not-wired; got: ${result.outputForLlm}",
            )
        }
    }

    // ── decision quad-state mapping ──────────────────────────────

    @Test fun pendingEntryDecisionIsPending(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (recorder, bus) = setupRecorder()
            bus.publish(
                BusEvent.PermissionAsked(SessionId("s1"), "rid-pending", "fs.write", listOf("/tmp/*")),
            )
            waitFor(recorder, "s1") { it.size == 1 }
            val result = runPermissionHistoryQuery(recorder, input(), 100, 0)
            val row = decodeRows(result.data).single()
            assertEquals("pending", row.decision)
            // Pin: accepted carries the recorder's null forward.
            assertEquals(null, row.accepted)
        }
    }

    @Test fun acceptedOnceDecisionIsOnce(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (recorder, bus) = setupRecorder()
            bus.publish(BusEvent.PermissionAsked(SessionId("s1"), "rid-once", "fs.write", listOf("/tmp/*")))
            waitFor(recorder, "s1") { it.size == 1 }
            bus.publish(BusEvent.PermissionReplied(SessionId("s1"), "rid-once", accepted = true, remembered = false))
            waitFor(recorder, "s1") { it.single().accepted == true }
            val row = decodeRows(runPermissionHistoryQuery(recorder, input(), 100, 0).data).single()
            assertEquals("once", row.decision)
        }
    }

    @Test fun acceptedAndRememberedDecisionIsAlways(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (recorder, bus) = setupRecorder()
            bus.publish(BusEvent.PermissionAsked(SessionId("s1"), "rid-always", "fs.write", listOf("/tmp/*")))
            waitFor(recorder, "s1") { it.size == 1 }
            bus.publish(BusEvent.PermissionReplied(SessionId("s1"), "rid-always", accepted = true, remembered = true))
            waitFor(recorder, "s1") { it.single().remembered == true }
            val row = decodeRows(runPermissionHistoryQuery(recorder, input(), 100, 0).data).single()
            assertEquals("always", row.decision)
        }
    }

    @Test fun rejectedDecisionIsReject(): TestResult = runTest {
        // Note the WHEN-precedence quirk: per kdoc the buckets
        // are pending/always/once/reject in that order. With
        // accepted=false + remembered=true the production code
        // routes to "always" (remembered=true wins ahead of
        // accepted), so "reject" requires accepted=false AND
        // remembered=false (not "remembered=any").
        withContext(Dispatchers.Default) {
            val (recorder, bus) = setupRecorder()
            bus.publish(BusEvent.PermissionAsked(SessionId("s1"), "rid-reject", "fs.write", listOf("/tmp/*")))
            waitFor(recorder, "s1") { it.size == 1 }
            bus.publish(BusEvent.PermissionReplied(SessionId("s1"), "rid-reject", accepted = false, remembered = false))
            waitFor(recorder, "s1") { it.single().accepted == false }
            val row = decodeRows(runPermissionHistoryQuery(recorder, input(), 100, 0).data).single()
            assertEquals("reject", row.decision)
        }
    }

    @Test fun rememberedRejectAliasesToAlwaysPerCurrentSemantics(): TestResult = runTest {
        // Pin: production code routes accepted=false +
        // remembered=true to "always" (remembered=true wins
        // ahead of accepted=false). This is arguably a UX
        // ambiguity — "always" can mean either "always accept"
        // or "always reject" depending on `accepted`. Pinning
        // the current semantic so a future intentional change
        // surfaces here rather than silently shifting the
        // decision string.
        withContext(Dispatchers.Default) {
            val (recorder, bus) = setupRecorder()
            bus.publish(BusEvent.PermissionAsked(SessionId("s1"), "rid", "fs.write", listOf("/tmp/*")))
            waitFor(recorder, "s1") { it.size == 1 }
            bus.publish(BusEvent.PermissionReplied(SessionId("s1"), "rid", accepted = false, remembered = true))
            waitFor(recorder, "s1") { it.single().remembered == true }
            val row = decodeRows(runPermissionHistoryQuery(recorder, input(), 100, 0).data).single()
            assertEquals("always", row.decision, "remembered=true wins ahead of accepted=false")
        }
    }

    // ── narrative tally + NPE-on-pending bug pin ──────────────

    @Test fun mixedPendingAndRejectCompletesAndCountsCorrectly(): TestResult = runTest {
        // The marquee bug-fix pin. Pre-fix: production line
        // `rowsAll.count { !it.accepted!!.let { acc -> acc } || it.decision == "reject" }`
        // NPEs when any row has accepted=null (pending). The
        // expected behavior is: rejected count = entries with
        // decision == "reject", pending count = entries with
        // decision == "pending"; the query must complete
        // gracefully on a mixed snapshot.
        withContext(Dispatchers.Default) {
            val (recorder, bus) = setupRecorder()
            // Pending: ask without reply.
            bus.publish(BusEvent.PermissionAsked(SessionId("s1"), "p1", "net.fetch", listOf("*")))
            waitFor(recorder, "s1") { it.size == 1 }
            // Rejected: ask + reject.
            bus.publish(BusEvent.PermissionAsked(SessionId("s1"), "r1", "fs.write", listOf("/etc/*")))
            waitFor(recorder, "s1") { it.size == 2 }
            bus.publish(BusEvent.PermissionReplied(SessionId("s1"), "r1", accepted = false, remembered = false))
            waitFor(recorder, "s1") { it.firstOrNull { e -> e.requestId == "r1" }?.accepted == false }

            val result = runPermissionHistoryQuery(recorder, input(), 100, 0)
            // Pin: completes without NPE.
            assertEquals(2, result.data.total, "both entries surface")
            val out = result.outputForLlm
            // Pin: tally counts surface in narrative.
            assertTrue("1 rejected" in out, "rejected count; got: $out")
            assertTrue("1 pending" in out, "pending count; got: $out")
        }
    }

    @Test fun narrativeMostRecentReadsOldestFirstSnapshotsTail(): TestResult = runTest {
        // Pin: recorder.snapshot() returns oldest-first order, so
        // narrative reads `rowsAll.last()` for "Most recent".
        // Test plants two replied entries; the second-published
        // one must appear in "Most recent: <perm> → <decision>".
        withContext(Dispatchers.Default) {
            val (recorder, bus) = setupRecorder()
            bus.publish(BusEvent.PermissionAsked(SessionId("s1"), "rid-old", "old.perm", listOf("*")))
            waitFor(recorder, "s1") { it.size == 1 }
            bus.publish(BusEvent.PermissionReplied(SessionId("s1"), "rid-old", accepted = true, remembered = false))
            waitFor(recorder, "s1") { it.single().accepted == true }
            bus.publish(BusEvent.PermissionAsked(SessionId("s1"), "rid-new", "new.perm", listOf("*")))
            waitFor(recorder, "s1") { it.size == 2 }
            bus.publish(BusEvent.PermissionReplied(SessionId("s1"), "rid-new", accepted = false, remembered = false))
            waitFor(recorder, "s1") { it.firstOrNull { e -> e.requestId == "rid-new" }?.accepted == false }

            val out = runPermissionHistoryQuery(recorder, input(), 100, 0).outputForLlm
            assertTrue(
                "Most recent: new.perm → reject" in out,
                "newest entry surfaces in 'Most recent'; got: $out",
            )
        }
    }

    // ── pagination ────────────────────────────────────────────────

    @Test fun limitTrimsRowsButTotalReflectsAll(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val (recorder, bus) = setupRecorder()
            repeat(5) { i ->
                bus.publish(BusEvent.PermissionAsked(SessionId("s1"), "rid-$i", "p$i", listOf("*")))
            }
            waitFor(recorder, "s1") { it.size == 5 }
            val result = runPermissionHistoryQuery(recorder, input(), 2, 0)
            assertEquals(2, decodeRows(result.data).size)
            assertEquals(5, result.data.total)
        }
    }

    // ── output framing ──────────────────────────────────────────

    @Test fun outputCarriesSelectAndCounts() {
        val result = runPermissionHistoryQuery(recorder = null, input(), 100, 0)
        assertEquals(SessionQueryTool.SELECT_PERMISSION_HISTORY, result.data.select)
        assertEquals(0, result.data.total)
        assertEquals(0, result.data.returned)
    }

    @Test fun titleIncludesSessionIdAndReturnedSlashTotal() {
        val result = runPermissionHistoryQuery(recorder = null, input(), 100, 0)
        assertTrue(
            "session_query permission_history s1" in (result.title ?: ""),
            "title format; got: ${result.title}",
        )
        assertTrue("(0/0)" in (result.title ?: ""), "got: ${result.title}")
    }
}
