package io.talevia.core.agent

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the three behaviours the `adaptive-retry-backoff` cycle added on
 * top of the baseline exponential curve: jitter, rate-limit floor, and
 * [BackoffKind] classification. Plus the invariants the legacy curve had
 * to keep: retry-after honour, exponential growth, max-delay cap.
 */
class RetryPolicyTest {

    @Test fun deterministicPolicyGrowsExponentiallyAcrossAttempts() {
        val p = RetryPolicy.Deterministic
        assertEquals(2_000L, p.delayFor(1))
        assertEquals(4_000L, p.delayFor(2))
        assertEquals(8_000L, p.delayFor(3))
        assertEquals(16_000L, p.delayFor(4))
        // Capped at maxDelayNoHeadersMs (30s) by default.
        assertEquals(30_000L, p.delayFor(5))
        assertEquals(30_000L, p.delayFor(10))
    }

    @Test fun retryAfterHeaderWinsOverExponential() {
        val p = RetryPolicy.Deterministic
        assertEquals(5_500L, p.delayFor(attempt = 3, retryAfterMs = 5_500))
        // Honours it even if larger than the no-header cap.
        assertEquals(60_000L, p.delayFor(attempt = 1, retryAfterMs = 60_000))
    }

    @Test fun retryAfterHonoursGlobalMaxDelayCap() {
        val p = RetryPolicy.Deterministic.copy(maxDelayMs = 45_000)
        assertEquals(45_000L, p.delayFor(attempt = 1, retryAfterMs = 120_000))
    }

    @Test fun zeroJitterIsExactlyTheBaseCurve() {
        val p = RetryPolicy(jitterFactor = 0.0)
        repeat(10) { attempt ->
            assertEquals(
                p.copy(jitterFactor = 0.0).delayFor(attempt + 1, random = Random(seed = 42)),
                p.delayFor(attempt + 1, random = Random(seed = 1337)),
                "jitter=0 must be deterministic across seeds for attempt=${attempt + 1}",
            )
        }
    }

    @Test fun jitterStaysWithinPlusMinusBand() {
        val p = RetryPolicy(jitterFactor = 0.2)
        val base = 2_000L
        repeat(100) {
            val delay = p.delayFor(attempt = 1, random = Random(it))
            assertTrue(delay >= (base * 0.8).toLong(), "delay $delay < 0.8×base for seed=$it")
            assertTrue(delay <= (base * 1.2).toLong(), "delay $delay > 1.2×base for seed=$it")
        }
    }

    @Test fun rateLimitFloorLiftsShortDelaysToFloor() {
        // Default: 15s floor.
        val p = RetryPolicy.Deterministic
        // Attempt 1 would normally be 2s — floor to 15s.
        assertEquals(15_000L, p.delayFor(attempt = 1, kind = BackoffKind.RATE_LIMIT))
        // Attempt 3 is 8s — floor to 15s.
        assertEquals(15_000L, p.delayFor(attempt = 3, kind = BackoffKind.RATE_LIMIT))
        // Attempt 4 is 16s — above floor, unchanged.
        assertEquals(16_000L, p.delayFor(attempt = 4, kind = BackoffKind.RATE_LIMIT))
    }

    @Test fun rateLimitFloorDoesNotApplyToOtherKinds() {
        val p = RetryPolicy.Deterministic
        // Attempt 1 with SERVER kind stays at base 2s.
        assertEquals(2_000L, p.delayFor(attempt = 1, kind = BackoffKind.SERVER))
        assertEquals(2_000L, p.delayFor(attempt = 1, kind = BackoffKind.NETWORK))
        assertEquals(2_000L, p.delayFor(attempt = 1, kind = BackoffKind.OTHER))
    }

    @Test fun rateLimitFloorNullDisablesFeature() {
        val p = RetryPolicy.Deterministic.copy(rateLimitMinDelayMs = null)
        assertEquals(2_000L, p.delayFor(attempt = 1, kind = BackoffKind.RATE_LIMIT))
    }

    @Test fun retryAfterHeaderOverridesRateLimitFloor() {
        // If the provider says "retry in 500ms", we obey it rather than imposing our own floor.
        val p = RetryPolicy.Deterministic
        assertEquals(500L, p.delayFor(attempt = 1, retryAfterMs = 500, kind = BackoffKind.RATE_LIMIT))
    }

    @Test fun invalidConstructorArgsFailLoud() {
        assertFailsWith<IllegalArgumentException> { RetryPolicy(maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(backoffFactor = 0.5) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(jitterFactor = -0.1) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(jitterFactor = 1.5) }
        assertFailsWith<IllegalArgumentException> { RetryPolicy(rateLimitMinDelayMs = -1) }
    }

    // ── RetryClassifier.kind ──────────────────────────────────────────

    @Test fun classifiesHttp429AsRateLimit() {
        assertEquals(
            BackoffKind.RATE_LIMIT,
            RetryClassifier.kind("openai HTTP 429: rate_limit_exceeded", retriableHint = true),
        )
    }

    @Test fun classifies5xxAsServer() {
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("anthropic HTTP 503: overloaded_error", retriableHint = true),
        )
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("HTTP 502 Bad Gateway", retriableHint = false),
        )
    }

    @Test fun classifiesSemanticRateLimitTexts() {
        assertEquals(BackoffKind.RATE_LIMIT, RetryClassifier.kind("Rate limit reached", true))
        assertEquals(BackoffKind.RATE_LIMIT, RetryClassifier.kind("too many requests", true))
        assertEquals(BackoffKind.RATE_LIMIT, RetryClassifier.kind("quota exhausted", true))
    }

    @Test fun classifiesOverloadedAsServer() {
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("Provider is overloaded", true),
        )
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("Service unavailable", true),
        )
    }

    @Test fun classifiesNetworkMessagesAsNetwork() {
        assertEquals(BackoffKind.NETWORK, RetryClassifier.kind("connection reset by peer", true))
        assertEquals(BackoffKind.NETWORK, RetryClassifier.kind("socket timeout", true))
    }

    @Test fun classifiesUnknownAsOther() {
        assertEquals(BackoffKind.OTHER, RetryClassifier.kind("some weird error", true))
        assertEquals(BackoffKind.OTHER, RetryClassifier.kind(null, true))
    }

    @Test fun httpStatusTakesPriorityOverSemanticText() {
        // A 5xx whose body mentions "rate limit" classifies as SERVER — transport
        // signal beats semantic signal (same heuristic as metrics retry slug).
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("HTTP 503: rate_limit_detected_but_we_return_503", true),
        )
    }
}
