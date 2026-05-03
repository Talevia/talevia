package io.talevia.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Direct tests for [parseRetryAfterMs] —
 * `core/provider/RetryAfter.kt`. The cross-provider HTTP
 * `retry-after-ms` / `retry-after` header parser. Cycle 156
 * audit: 15 LOC, 0 transitive test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **`-ms` header takes precedence over seconds.** Per
 *    kdoc: "Prefers the explicit `-ms` form; falls back to
 *    seconds." Drift to seconds-first would silently halve
 *    resolution for backends that send both.
 *
 * 2. **Non-positive values → null.** A backend sending
 *    `retry-after: 0` is degenerate (telling us "retry now"
 *    is no useful hint). Both `0` and negative parse to null
 *    so the caller's retry policy falls back to its own
 *    backoff.
 *
 * 3. **Unparseable / null inputs → null.** Both nulls,
 *    non-numeric strings, and whitespace-only inputs all
 *    return null. The caller falls through to its own
 *    backoff. Drift to throwing would crash the agent's
 *    retry classifier on every malformed-header response.
 */
class RetryAfterTest {

    // ── -ms precedence ──────────────────────────────────────────

    @Test fun msHeaderTakesPrecedenceWhenBothPresent() {
        // Marquee precedence pin: `-ms` form wins over
        // seconds form. Drift would lose resolution.
        val out = parseRetryAfterMs(ms = "1500", seconds = "5")
        // 1500 ms (NOT 5 sec = 5000 ms).
        assertEquals(1500L, out)
    }

    @Test fun secondsHeaderUsedWhenMsAbsent() {
        // Pin: -ms absent → fall back to seconds, multiplied
        // by 1000 (sec → ms conversion).
        val out = parseRetryAfterMs(ms = null, seconds = "3")
        assertEquals(3_000L, out)
    }

    @Test fun secondsHeaderUsedWhenMsUnparseable() {
        // Pin: -ms present but garbage → falls through to
        // seconds, NOT throws. Real-world: misconfigured
        // proxy injects `retry-after-ms: invalid`, want
        // graceful degradation to the next header.
        val out = parseRetryAfterMs(ms = "abc", seconds = "2")
        assertEquals(2_000L, out)
    }

    @Test fun secondsHeaderUsedWhenMsIsZeroOrNegative() {
        // Pin: -ms present but zero/negative → falls through
        // to seconds (NOT returns null, because seconds is
        // valid). The `if (it > 0) return ...` guard skips
        // the assignment but doesn't return null.
        val outZero = parseRetryAfterMs(ms = "0", seconds = "1")
        assertEquals(1_000L, outZero, "ms=0 falls through to seconds")
        val outNeg = parseRetryAfterMs(ms = "-100", seconds = "1")
        assertEquals(1_000L, outNeg, "ms<0 falls through to seconds")
    }

    // ── non-positive → null when no fallback ────────────────────

    @Test fun bothZeroReturnsNull() {
        assertNull(parseRetryAfterMs(ms = "0", seconds = "0"))
    }

    @Test fun bothNegativeReturnsNull() {
        assertNull(parseRetryAfterMs(ms = "-10", seconds = "-5"))
    }

    @Test fun zeroSecondsAloneReturnsNull() {
        assertNull(parseRetryAfterMs(ms = null, seconds = "0"))
    }

    @Test fun negativeSecondsAloneReturnsNull() {
        assertNull(parseRetryAfterMs(ms = null, seconds = "-3"))
    }

    // ── null / unparseable inputs ──────────────────────────────

    @Test fun bothNullReturnsNull() {
        assertNull(parseRetryAfterMs(ms = null, seconds = null))
    }

    @Test fun bothUnparseableReturnsNull() {
        // Pin: `toDoubleOrNull` returns null for non-numeric;
        // function returns null instead of throwing. Critical
        // for real-world HTTP responses that may inject
        // typo'd / missing headers.
        assertNull(parseRetryAfterMs(ms = "later", seconds = "soon"))
    }

    @Test fun emptyStringInputsReturnNull() {
        // Pin: empty string parses to null via
        // toDoubleOrNull. Safety net for misconfigured
        // proxies that strip the header value.
        assertNull(parseRetryAfterMs(ms = "", seconds = ""))
    }

    @Test fun whitespaceOnlyInputsReturnNull() {
        // Pin: input is `trim()`ed; whitespace-only becomes
        // empty, which toDoubleOrNull → null.
        assertNull(parseRetryAfterMs(ms = "   ", seconds = "\t\n "))
    }

    // ── whitespace tolerance ────────────────────────────────────

    @Test fun leadingAndTrailingWhitespaceIsTrimmed() {
        // Pin: real HTTP response headers commonly include
        // leading/trailing whitespace from naive splitting.
        // Trim absorbs that.
        assertEquals(500L, parseRetryAfterMs(ms = "  500  ", seconds = null))
        assertEquals(2_000L, parseRetryAfterMs(ms = null, seconds = "\t2\n"))
    }

    // ── decimal / floating-point values ─────────────────────────

    @Test fun decimalSecondsConvertsCorrectlyToInteger() {
        // Pin: parser uses toDouble + (* 1000.0).toLong, so
        // decimal seconds (`retry-after: 1.5`) → 1500 ms.
        // Drift to toInt-after-multiply would round-truncate
        // sub-millisecond values silently — but we already
        // operate at ms granularity so this is acceptable.
        val out = parseRetryAfterMs(ms = null, seconds = "1.5")
        assertEquals(1_500L, out)
    }

    @Test fun decimalMsTruncatesToLong() {
        // Pin: ms parser uses toDouble + .toLong(), which
        // truncates the fractional part. `retry-after-ms:
        // 1500.7` → 1500 ms. Real-world: backend rounds
        // accidentally; we don't care about sub-ms.
        val out = parseRetryAfterMs(ms = "1500.7", seconds = null)
        assertEquals(1_500L, out)
    }

    @Test fun zeroPointFiveSecondsConvertsTo500Ms() {
        // Pin: small decimal seconds doesn't round to zero —
        // fractional sec * 1000 → ms.
        val out = parseRetryAfterMs(ms = null, seconds = "0.5")
        assertEquals(500L, out)
    }
}
