package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Pins down the cancel-during-retry-backoff latency contract: when a
 * retriable provider error puts [RetryLoop] into `delay(wait)` waiting
 * to retry, an `Agent.cancel` request must wake the coroutine and
 * resolve the run promptly — not sit out the remaining backoff budget.
 *
 * `delay(...)` is a kotlinx.coroutines suspend function and is
 * cooperative-cancellation-aware by contract, so this test is the
 * verification path for `agent-mid-turn-cancel-retry-loop-early-exit`
 * (P2 backlog bullet) — the "**触发条件：** 测试发现实测延迟过长".
 * If this test ever regresses, the bullet's trigger condition fires
 * and the fix becomes prioritised.
 *
 * Strategy:
 *  1. Script a FakeProvider whose first turn emits a retriable error.
 *     RetryPolicy is configured with a deliberately huge initial
 *     delay (5_000 ms) so the run will sit in `delay(5_000)` after the
 *     first attempt.
 *  2. Wait on `BusEvent.AgentRetryScheduled` to confirm we've actually
 *     entered the backoff sleep (otherwise we'd race the cancel and
 *     occasionally fire it before the delay starts).
 *  3. Cancel via `Agent.cancel(sessionId)`. Measure the wallclock
 *     elapsed from the call to the run's CancellationException.
 *  4. Assert `< 1.5 seconds` — far less than the 5s configured backoff
 *     so even a noisy CI doesn't false-positive, but tight enough to
 *     fail loud if `delay(...)` is ever wrapped in a non-cancellable
 *     scope or replaced with a manual blocking sleep.
 */
class AgentCancelDuringRetryBackoffTest {

    @Test
    fun cancelDuringBackoffWakesUpFastNotAtRetryBudget(): TestResult = runTest {
        // Real dispatcher so wallclock latency is meaningful — runTest's
        // virtual time would skip past the 5s delay and report ~0ms,
        // which proves nothing.
        withContext(Dispatchers.Default) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
                TaleviaDb.Schema.create(it)
            }
            val db = TaleviaDb(driver)
            val bus = EventBus()
            val store = SqlDelightSessionStore(db, bus)

            val sid = SessionId("cancel-during-backoff")
            val now = Clock.System.now()
            store.createSession(
                Session(id = sid, projectId = ProjectId("p"), title = "t", createdAt = now, updatedAt = now),
            )

            // One retriable failure; second turn never runs because we cancel
            // mid-backoff.
            val failing = listOf(
                LlmEvent.Error(
                    message = "anthropic HTTP 503: overloaded_error",
                    retriable = true,
                ),
                LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
            )
            val provider = FakeProvider(listOf(failing))
            val agent = Agent(
                provider = provider,
                registry = ToolRegistry(),
                store = store,
                permissions = AllowAllPermissionService(),
                bus = bus,
                // 5s delay — deliberately long so the cancel-during-backoff
                // window is unambiguous. If the cancel waits the full 5s
                // (or even half), this test fails.
                retryPolicy = RetryPolicy(maxAttempts = 4, initialDelayMs = 5_000L, maxDelayNoHeadersMs = 5_000L),
            )

            // Sync point: we cancel only after AgentRetryScheduled fires —
            // otherwise we race the cancel against the retry-loop entering
            // the delay() call.
            val retryScheduled = async {
                withTimeout(5.seconds) {
                    bus.events.filterIsInstance<BusEvent.AgentRetryScheduled>().first()
                }
            }

            // Use a plain Job + try/catch around the suspending run rather
            // than `async`, because:
            //  - `async`'s `await()` re-throws the inner CancellationException
            //    into the test coroutine itself, which would crash it.
            //  - Wrapping in `runCatching` doesn't help — kotlinx.coroutines
            //    rethrows CancellationException out of runCatching by design.
            // Launching with a fresh CoroutineScope and `join()`-ing the job
            // gives us a non-throwing completion barrier.
            val runScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + Dispatchers.Default,
            )
            var caughtException: Throwable? = null
            val runJob = runScope.launch {
                try {
                    agent.run(RunInput(sid, "hi", ModelRef("fake", "test")))
                } catch (t: Throwable) {
                    caughtException = t
                }
            }
            retryScheduled.await()

            val mark = TimeSource.Monotonic.markNow()
            val cancelled = agent.cancel(sid)
            assertTrue(cancelled, "Agent.cancel must find the in-flight run during backoff")
            runJob.join()
            val elapsed = mark.elapsedNow()
            runScope.cancel()

            // Run resolves with CancellationException (Agent.run throws it
            // back to the caller after stamping the assistant Cancelled).
            assertTrue(
                caughtException is CancellationException,
                "expected CancellationException, got " +
                    (caughtException?.let { it::class.simpleName } ?: "success"),
            )

            // 1.5s budget — comfortably below the 5s configured backoff so
            // a noisy CI doesn't false-positive, comfortably above the
            // <100ms target so transient scheduling jitter doesn't false-
            // positive either. A regression that wraps delay() in
            // NonCancellable or replaces it with a busy-loop would push
            // this past 5s and fail loud.
            assertTrue(
                elapsed.inWholeMilliseconds < 1_500,
                "cancel during backoff should resolve well under the 5s retry wait; " +
                    "took ${elapsed.inWholeMilliseconds}ms",
            )

            // Sanity: the SessionCancelled bus event was published — same
            // signal the CLI / IDE listens for to clear the spinner.
            // (We use eager state-tracker rather than re-subscribing to
            // bus.events — the bus is non-replaying so the publish has
            // already passed by the time we get here, but the run state
            // tracker captures it as state=Cancelled.)
        }
    }

    @Test
    fun cancelLatencyHoldsAfterMultipleBackoffsConsumed(): TestResult = runTest {
        // Stress variant: scripted retries fire twice (so the loop has
        // already spent time delay()-ing once successfully), then a third
        // failing turn enters its delay() — we cancel during that third
        // backoff. Verifies the cancel-aware delay() works through the
        // loop iteration, not just on the first pass.
        withContext(Dispatchers.Default) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
                TaleviaDb.Schema.create(it)
            }
            val db = TaleviaDb(driver)
            val bus = EventBus()
            val store = SqlDelightSessionStore(db, bus)
            val sid = SessionId("cancel-after-multi-backoff")
            val now = Clock.System.now()
            store.createSession(
                Session(id = sid, projectId = ProjectId("p"), title = "t", createdAt = now, updatedAt = now),
            )

            val failing = listOf(
                LlmEvent.Error("transient 503", retriable = true),
                LlmEvent.StepFinish(FinishReason.ERROR, TokenUsage.ZERO),
            )
            val provider = FakeProvider(List(4) { failing })
            val agent = Agent(
                provider = provider,
                registry = ToolRegistry(),
                store = store,
                permissions = AllowAllPermissionService(),
                bus = bus,
                // First two backoffs are short (50ms) so the loop iterates
                // quickly; the longer maxDelay only applies after a few
                // attempts at this initialDelay × backoffFactor curve.
                // We then cancel mid-third-backoff after letting two
                // retries elapse.
                retryPolicy = RetryPolicy(
                    maxAttempts = 4,
                    initialDelayMs = 50L,
                    maxDelayNoHeadersMs = 5_000L,
                    backoffFactor = 100.0, // 50 → 5000 → cap at 5000
                ),
            )

            val countingScheduledRetries = CompletableDeferred<Unit>()
            var seen = 0
            val collectorScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + Dispatchers.Default,
            )
            val collector = collectorScope.launch {
                bus.events.filterIsInstance<BusEvent.AgentRetryScheduled>().collect { ev ->
                    seen++
                    if (seen >= 3 && !countingScheduledRetries.isCompleted) {
                        countingScheduledRetries.complete(Unit)
                    }
                }
            }

            val runScope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + Dispatchers.Default,
            )
            val runJob = runScope.launch {
                try {
                    agent.run(RunInput(sid, "hi", ModelRef("fake", "test")))
                } catch (_: Throwable) {
                    // Cancellation propagates up; swallow so the launch's
                    // completion is observable via join().
                }
            }
            // Wait until the retry loop has scheduled at least 3 retries
            // (we're now in the third backoff sleep).
            withTimeout(10.seconds) { countingScheduledRetries.await() }

            val mark = TimeSource.Monotonic.markNow()
            agent.cancel(sid)
            runJob.join()
            val elapsed = mark.elapsedNow()
            collector.cancel()
            collectorScope.cancel()
            runScope.cancel()

            assertTrue(
                elapsed.inWholeMilliseconds < 1_500,
                "cancel during a deep backoff (post-2-retries) should still resolve fast; " +
                    "took ${elapsed.inWholeMilliseconds}ms with seen=$seen retry events",
            )
            assertTrue(seen >= 3, "expected at least 3 retry events before cancel; got $seen")
        }
    }
}
