package io.talevia.core.provider

import io.talevia.core.tool.ToolSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TpmThrottleTest {

    /** Clock tied to a [TestScope]'s virtual time so delay() advancement is observable. */
    private class VirtualClock(private val scope: TestScope) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(scope.currentTime)
    }

    @Test fun acquireReturnsImmediatelyWhenBelowBudget() = runTest {
        val throttle = TpmThrottle(tpmLimit = 1000, bufferRatio = 1.0, clock = VirtualClock(this))
        val startTime = currentTime
        throttle.acquire(100)
        assertEquals(startTime, currentTime, "no delay should be introduced below budget")
        assertEquals(100L, throttle.usedTokens())
    }

    @Test fun acquireWaitsWhenWindowIsFull() = runTest {
        val throttle = TpmThrottle(tpmLimit = 1000, bufferRatio = 1.0, windowMs = 60_000, clock = VirtualClock(this))
        throttle.acquire(1000) // fills the budget exactly
        val start = currentTime

        var secondAcquired = false
        val job = launch {
            throttle.acquire(100)
            secondAcquired = true
        }
        // Not enough time yet — window is still full.
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(secondAcquired, "should still be waiting before window rolls over")

        // Advance past the window; the oldest reservation expires and the call proceeds.
        advanceTimeBy(31_000)
        runCurrent()
        assertTrue(secondAcquired, "should acquire after window roll-over")
        assertTrue(currentTime - start >= 60_000, "must have waited at least one window")
        job.cancel()
    }

    @Test fun settleReplacesEstimateWithActuals() = runTest {
        val throttle = TpmThrottle(tpmLimit = 1000, bufferRatio = 1.0, clock = VirtualClock(this))
        val reservation = throttle.acquire(estimateTokens = 800)
        assertEquals(800L, throttle.usedTokens())

        reservation.settle(actualTokens = 200)
        assertEquals(200L, throttle.usedTokens(), "actuals should overwrite the estimate")

        // After settle, there's headroom the estimate was blocking — a second call fits.
        throttle.acquire(700)
        assertEquals(900L, throttle.usedTokens())
    }

    @Test fun expiredRecordsAreEvictedOnRead() = runTest {
        val throttle = TpmThrottle(tpmLimit = 1000, bufferRatio = 1.0, windowMs = 60_000, clock = VirtualClock(this))
        throttle.acquire(900)
        assertEquals(900L, throttle.usedTokens())
        advanceTimeBy(60_001) // window fully passed
        assertEquals(0L, throttle.usedTokens(), "records older than windowMs must be evicted")
    }

    @Test fun bufferRatioLimitsEffectiveBudget() = runTest {
        // limit=1000, ratio=0.5 → effective budget 500. A 600-token ask cannot fit immediately.
        val throttle = TpmThrottle(tpmLimit = 1000, bufferRatio = 0.5, clock = VirtualClock(this))
        throttle.acquire(500)
        assertEquals(500L, throttle.usedTokens())

        val start = currentTime
        var proceeded = false
        val job = launch {
            throttle.acquire(100)
            proceeded = true
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(proceeded, "must wait for window roll-over even if total < raw limit")
        job.cancel()
        assertEquals(start + 1_000, currentTime)
    }

    @Test fun oversizedEstimateIsClampedAndEventuallyProceeds() = runTest {
        // An estimate larger than the full budget must NOT block forever; we clamp and proceed.
        val throttle = TpmThrottle(tpmLimit = 1000, bufferRatio = 1.0, clock = VirtualClock(this))
        throttle.acquire(10_000) // 10x the limit
        assertEquals(1000L, throttle.usedTokens(), "estimate should be clamped to full budget")
    }

    @Test fun stallForBlocksAcquireUntilCooldownElapses() = runTest {
        val throttle = TpmThrottle(tpmLimit = 1000, bufferRatio = 1.0, windowMs = 60_000, clock = VirtualClock(this))
        // Simulate a cold-start 429: local records are empty but the provider signals
        // a 10s cooldown.
        throttle.stallFor(10_000)

        val start = currentTime
        var acquired = false
        val job = launch {
            throttle.acquire(100)
            acquired = true
        }
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(acquired, "should still be waiting halfway through cooldown")

        advanceTimeBy(5_500)
        runCurrent()
        assertTrue(acquired, "should release once cooldown elapses")
        assertTrue(currentTime - start in 9_000..12_000, "wait should be ~10s, was ${currentTime - start}ms")
        job.cancel()
    }

    @Test fun estimateRequestTokensGrowsWithToolSchemaSize() {
        val base = LlmRequest(
            model = io.talevia.core.session.ModelRef("openai", "gpt-4o"),
            messages = emptyList(),
            tools = emptyList(),
            systemPrompt = null,
        )
        val withTools = base.copy(
            tools = List(10) { i ->
                ToolSpec(
                    id = "tool_$i",
                    helpText = "description for tool $i".repeat(10),
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        put("padding", "x".repeat(200))
                    },
                )
            },
        )
        assertTrue(
            estimateRequestTokens(withTools) > estimateRequestTokens(base),
            "more tools ⇒ higher estimate",
        )
    }
}
