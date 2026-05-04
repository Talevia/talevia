package io.talevia.core.permission

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for [PermissionHistoryRecorder] — the bus-aggregator that
 * pairs [BusEvent.PermissionAsked] with its matching
 * [BusEvent.PermissionReplied] and exposes the result via
 * [PermissionHistoryRecorder.snapshot].
 *
 * Each test body wraps in `withContext(Dispatchers.Default)` because
 * the recorder's bus-collect coroutine runs on the SupervisorJob's
 * scope; runTest's virtual time doesn't drive that thread, so the
 * test would time out on event delivery without the real-dispatcher
 * jump.
 */
class PermissionHistoryRecorderTest {

    private suspend fun waitForEntry(
        recorder: PermissionHistoryRecorder,
        sessionId: String,
        predicate: (List<PermissionHistoryRecorder.Entry>) -> Boolean,
    ) {
        // 10s rather than 5s: the recorder's collector runs on
        // Dispatchers.Default and gets starved under concurrent
        // gradle daemon load (cycle 283 stop-hook caught a real
        // 5s-window race after a 19-test compile-and-run). 10s
        // still fails fast on real bugs.
        withTimeout(10.seconds) {
            while (!predicate(recorder.snapshot(sessionId))) yield()
        }
    }

    @Test fun matchedAskAndReplyPairsByRequestId(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = PermissionHistoryRecorder(bus, scope)
            recorder.awaitReady()

            val sid = SessionId("s1")
            bus.publish(BusEvent.PermissionAsked(sid, "rid-1", "network.fetch", listOf("https://example.com/*")))
            waitForEntry(recorder, "s1") { it.size == 1 && it.single().accepted == null }

            bus.publish(BusEvent.PermissionReplied(sid, "rid-1", accepted = false, remembered = true))
            waitForEntry(recorder, "s1") { it.singleOrNull()?.accepted == false }

            val entry = recorder.snapshot("s1").single()
            assertEquals("network.fetch", entry.permission)
            assertEquals(listOf("https://example.com/*"), entry.patterns)
            assertEquals(false, entry.accepted)
            assertEquals(true, entry.remembered)
            assertNotNull(entry.repliedEpochMs)
        }
    }

    @Test fun pendingEntryIsVisibleBeforeReply(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = PermissionHistoryRecorder(bus, scope)
            recorder.awaitReady()
            val sid = SessionId("s2")
            bus.publish(BusEvent.PermissionAsked(sid, "rid-pending", "fs.write", listOf("/tmp/*")))
            waitForEntry(recorder, "s2") { it.size == 1 }
            val entry = recorder.snapshot("s2").single()
            assertNull(entry.accepted, "pending entry must keep accepted=null until reply")
            assertNull(entry.repliedEpochMs)
        }
    }

    @Test fun multipleSessionsAreIsolated(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = PermissionHistoryRecorder(bus, scope)
            recorder.awaitReady()
            bus.publish(BusEvent.PermissionAsked(SessionId("a"), "ra", "p", listOf("*")))
            bus.publish(BusEvent.PermissionAsked(SessionId("b"), "rb", "p", listOf("*")))
            waitForEntry(recorder, "a") { it.size == 1 }
            waitForEntry(recorder, "b") { it.size == 1 }
            assertEquals("ra", recorder.snapshot("a").single().requestId)
            assertEquals("rb", recorder.snapshot("b").single().requestId)
        }
    }

    @Test fun unmatchedReplyIsNoOp(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = PermissionHistoryRecorder(bus, scope)
            recorder.awaitReady()
            // Reply with no matching ask — should not crash, should not create an entry.
            bus.publish(BusEvent.PermissionReplied(SessionId("ghost"), "rid-orphan", true, false))
            yield()
            yield()
            assertTrue(recorder.snapshot("ghost").isEmpty())
        }
    }

    @Test fun ringBufferDropsOldestPastCapacity(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = PermissionHistoryRecorder(bus, scope, capacityPerSession = 3)
            recorder.awaitReady()
            val sid = SessionId("s3")
            for (i in 1..5) {
                bus.publish(BusEvent.PermissionAsked(sid, "rid-$i", "p", listOf("*")))
            }
            waitForEntry(recorder, "s3") { it.size == 3 }
            val ids = recorder.snapshot("s3").map { it.requestId }
            assertEquals(listOf("rid-3", "rid-4", "rid-5"), ids)
        }
    }
}
