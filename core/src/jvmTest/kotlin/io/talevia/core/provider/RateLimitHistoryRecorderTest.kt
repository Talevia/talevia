package io.talevia.core.provider

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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Coverage for [RateLimitHistoryRecorder] — the bus-aggregator that
 * pairs every [BusEvent.AgentRetryScheduled] whose
 * [io.talevia.core.agent.RetryClassifier.kind] resolves to
 * [io.talevia.core.agent.BackoffKind.RATE_LIMIT] with its provider
 * and ring-buffers per-provider for the "how often did I hit the
 * tier-1 cap today?" ops query. Cycle 98 audit: 135 LOC, 2 transitive
 * refs — only exercised via the higher-level provider-query path.
 *
 * Same dispatch pattern as `PermissionHistoryRecorderTest`: each
 * test wraps in `withContext(Dispatchers.Default)` because the
 * recorder collector lives on a real-dispatcher SupervisorJob scope
 * — runTest's virtual time wouldn't drive the bus collect.
 *
 * The classifier-coupling pin is the most load-bearing: a refactor
 * that changes [RetryClassifier.kind]'s output for "HTTP 429" or
 * "rate limit" strings would silently bypass the ring buffer
 * (operators stop seeing rate-limit entries on their dashboard
 * even though the agent is still retrying). The
 * `serverErrorIsDropped` / `unclassifiedReasonIsDropped` cases lock
 * the inverse (don't pollute the rate-limit ring with non-rate-limit
 * events).
 */
class RateLimitHistoryRecorderTest {

    private suspend fun waitForEntry(
        recorder: RateLimitHistoryRecorder,
        providerId: String,
        predicate: (List<RateLimitHistoryRecorder.Entry>) -> Boolean,
    ) {
        withTimeout(5.seconds) {
            while (!predicate(recorder.snapshot(providerId))) yield()
        }
    }

    @Test fun rateLimitRetryIsCapturedWithReasonAndProvider(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 2,
                    waitMs = 4_000L,
                    reason = "anthropic HTTP 429: tier-1 RPM exceeded",
                    providerId = "anthropic",
                ),
            )
            waitForEntry(recorder, "anthropic") { it.size == 1 }

            val e = recorder.snapshot("anthropic").single()
            assertEquals("anthropic", e.providerId)
            assertEquals("s1", e.sessionId)
            assertEquals(2, e.attempt)
            assertEquals(4_000L, e.waitMs)
            // Pin: reason string round-trips verbatim. Operators read this
            // from the dashboard to learn WHICH tier they're hitting; a
            // refactor extracting just "HTTP 429" would lose context.
            assertEquals("anthropic HTTP 429: tier-1 RPM exceeded", e.reason)
            assertTrue(e.epochMs > 0L, "epochMs must be stamped")
        }
    }

    @Test fun retryWithRateLimitTextStringClassifies(): TestResult = runTest {
        // Pin: classifier accepts the literal "rate limit" phrase too,
        // not just "HTTP 429". A refactor narrowing the classifier to
        // status codes only would drop entries for providers that
        // express the limit as text.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 1,
                    waitMs = 2_000L,
                    reason = "openai rate limit reached, please slow down",
                    providerId = "openai",
                ),
            )
            waitForEntry(recorder, "openai") { it.size == 1 }
            assertEquals(1, recorder.snapshot("openai").size)
        }
    }

    @Test fun serverErrorRetryIsDropped(): TestResult = runTest {
        // Pin: HTTP 500/503 retries belong to a different ops question
        // (transient provider outage, not rate cap). Recorder must
        // drop them. A regression keeping all retries would inflate
        // the rate-limit dashboard with unrelated noise.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 1,
                    waitMs = 2_000L,
                    reason = "anthropic HTTP 503: service unavailable",
                    providerId = "anthropic",
                ),
            )
            // Send a sentinel rate-limit event to confirm the collector
            // is alive, then assert the 503 was dropped.
            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 2,
                    waitMs = 4_000L,
                    reason = "anthropic HTTP 429: rate limit",
                    providerId = "anthropic",
                ),
            )
            waitForEntry(recorder, "anthropic") { it.size == 1 }
            // The 503 was dropped — only one entry, the 429.
            assertEquals(1, recorder.snapshot("anthropic").size)
            assertTrue("429" in recorder.snapshot("anthropic").single().reason)
        }
    }

    @Test fun unclassifiedReasonIsDropped(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 1,
                    waitMs = 2_000L,
                    reason = "weird unclassifiable error string",
                    providerId = "anthropic",
                ),
            )
            // Sentinel rate-limit to prove the collector ran past the
            // dropped event.
            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 2,
                    waitMs = 4_000L,
                    reason = "HTTP 429 too_many_requests",
                    providerId = "anthropic",
                ),
            )
            waitForEntry(recorder, "anthropic") { it.size == 1 }
            assertEquals(1, recorder.snapshot("anthropic").size)
        }
    }

    @Test fun nullProviderIdIsDropped(): TestResult = runTest {
        // Pin the kdoc contract: "Events without a providerId ... are
        // also dropped — the per-provider grouping needs a key."
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 1,
                    waitMs = 2_000L,
                    reason = "HTTP 429",
                    providerId = null, // legacy emitter
                ),
            )
            // Sentinel.
            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 2,
                    waitMs = 4_000L,
                    reason = "HTTP 429 retry-after",
                    providerId = "openai",
                ),
            )
            waitForEntry(recorder, "openai") { it.size == 1 }
            // The null-provider event was dropped — total snapshot has
            // exactly the openai entry.
            assertEquals(1, recorder.snapshot().values.sumOf { it.size })
            assertTrue(recorder.snapshot("openai").isNotEmpty())
        }
    }

    @Test fun entriesGroupedPerProvider(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            for (i in 1..3) {
                bus.publish(
                    BusEvent.AgentRetryScheduled(
                        sessionId = SessionId("s1"),
                        attempt = i,
                        waitMs = 1000L * i,
                        reason = "HTTP 429 anthropic-tier-1",
                        providerId = "anthropic",
                    ),
                )
            }
            for (i in 1..2) {
                bus.publish(
                    BusEvent.AgentRetryScheduled(
                        sessionId = SessionId("s1"),
                        attempt = i,
                        waitMs = 1500L * i,
                        reason = "HTTP 429 openai-tier-2",
                        providerId = "openai",
                    ),
                )
            }
            waitForEntry(recorder, "anthropic") { it.size == 3 }
            waitForEntry(recorder, "openai") { it.size == 2 }

            val all = recorder.snapshot()
            assertEquals(setOf("anthropic", "openai"), all.keys)
            assertEquals(3, all.getValue("anthropic").size)
            assertEquals(2, all.getValue("openai").size)
        }
    }

    @Test fun ringBufferEvictsOldestWhenAtCapacity(): TestResult = runTest {
        // Pin the per-provider capacity guard: when count == capacity,
        // the OLDEST entry is dropped on next insert. Without this the
        // recorder would grow unbounded (process-scoped retention but
        // no upper bound = a slow OOM in long-running CLI / desktop /
        // server). The bound is per-provider so a flooding provider
        // can't push out other providers' entries.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope, capacityPerProvider = 3)
            recorder.awaitReady()

            for (i in 1..5) {
                bus.publish(
                    BusEvent.AgentRetryScheduled(
                        sessionId = SessionId("s1"),
                        attempt = i,
                        waitMs = 1000L * i,
                        reason = "HTTP 429 attempt-$i",
                        providerId = "anthropic",
                    ),
                )
            }
            // Cap is 3; size hits 3 after the third event and stays there
            // for the remaining two — so wait for the LAST attempt
            // (attempt = 5) to land, which proves all 5 were processed.
            waitForEntry(recorder, "anthropic") { entries -> entries.any { it.attempt == 5 } }
            val entries = recorder.snapshot("anthropic")
            assertEquals(3, entries.size)
            // Pin oldest-first ordering AND that the oldest entries
            // (attempt 1, 2) were evicted, leaving 3, 4, 5.
            assertEquals(listOf(3, 4, 5), entries.map { it.attempt })
            assertTrue(
                entries.first().reason.contains("attempt-3"),
                "oldest surviving entry should be attempt-3; got ${entries.first().reason}",
            )
        }
    }

    @Test fun snapshotByProviderIdReturnsEmptyForUnknownProvider(): TestResult = runTest {
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()
            // No events published.
            assertEquals(emptyList(), recorder.snapshot("nonexistent"))
            assertEquals(emptyMap(), recorder.snapshot())
        }
    }

    @Test fun withSupervisorMintsItsOwnScopeAndCollects(): TestResult = runTest {
        // Pin the convenience factory: composition roots that don't
        // already own a long-lived scope use this. Verify the recorder
        // wires up identically (collector starts, events ring-buffer).
        withContext(Dispatchers.Default) {
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder.withSupervisor(bus)
            recorder.awaitReady()

            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 1,
                    waitMs = 2_000L,
                    reason = "HTTP 429",
                    providerId = "anthropic",
                ),
            )
            waitForEntry(recorder, "anthropic") { it.size == 1 }
            assertEquals(1, recorder.snapshot("anthropic").size)
        }
    }

    @Test fun recordsStateFlowReflectsLatestSnapshot(): TestResult = runTest {
        // Pin the StateFlow surface: subscribers (UI, dashboards) read
        // `recorder.records.value` directly. snapshot() must agree
        // with .records.value at all times — diverging would break
        // any compose / SwiftUI binding pulling from the StateFlow.
        withContext(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val bus = EventBus()
            val recorder = RateLimitHistoryRecorder(bus, scope)
            recorder.awaitReady()

            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = SessionId("s1"),
                    attempt = 1,
                    waitMs = 2_000L,
                    reason = "HTTP 429",
                    providerId = "anthropic",
                ),
            )
            waitForEntry(recorder, "anthropic") { it.size == 1 }
            assertEquals(recorder.records.value, recorder.snapshot())
            assertEquals(
                recorder.records.value["anthropic"].orEmpty(),
                recorder.snapshot("anthropic"),
            )
        }
    }
}
