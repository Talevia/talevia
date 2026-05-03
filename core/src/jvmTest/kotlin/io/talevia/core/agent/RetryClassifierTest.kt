package io.talevia.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Direct tests for [RetryClassifier.reason] — the function `RetryLoop`
 * calls per-failure to decide whether to retry at all (`null` → break
 * out of retry loop, stamp ERROR). Cycle 101 audit found the `kind()`
 * sibling is well-covered by `RetryPolicyTest`, but `reason()` has
 * **zero direct tests** despite being the gating decision for every
 * retry attempt.
 *
 * Two correctness contracts a regression could silently break:
 *
 * 1. **Non-retriable user errors short-circuit to null** — context
 *    window overflow, "maximum context" / "token limit" messages
 *    are user-fixable not provider-blip-fixable. A regression
 *    returning the message instead of null on these would cause
 *    every long-context turn to retry up to `maxAttempts` times
 *    against the same response, wasting tokens + time before
 *    finally failing.
 *
 * 2. **`retriableHint = true` is the trusted shortcut** — when the
 *    provider tells us "yes, this is retriable" via the hint flag,
 *    we honour it without re-parsing the message. A regression that
 *    fell through to the message-pattern matchers would silently
 *    drop the provider's intent (the message might not match any
 *    known pattern → return null → no retry, even though the
 *    provider explicitly asked for one).
 *
 * Plus the message-pattern fallback branches:
 * - HTTP 5xx / 429 → return message
 * - "overloaded" → "Provider is overloaded"
 * - "rate limit" / "rate_limit" / "rate increased too quickly" → "Rate limited"
 * - "too many requests" / "too_many_requests" → "Too many requests"
 * - "exhausted" → "Provider quota exhausted"
 * - "unavailable" → "Provider unavailable"
 * - unclassifiable + retriableHint=false → null
 */
class RetryClassifierTest {

    // ── Null / non-retriable short-circuits ────────────────────────

    @Test fun nullMessageReturnsNull() {
        assertNull(RetryClassifier.reason(null, retriableHint = true))
        assertNull(RetryClassifier.reason(null, retriableHint = false))
    }

    @Test fun contextLengthExceededIsNotRetriable() {
        // Pin: even with retriableHint=true, context_length errors
        // MUST short-circuit to null. Otherwise every long-context
        // turn would retry maxAttempts times and waste tokens.
        assertNull(RetryClassifier.reason("anthropic HTTP 400: context_length_exceeded", true))
        assertNull(RetryClassifier.reason("anthropic HTTP 400: context_length_exceeded", false))
    }

    @Test fun maximumContextIsNotRetriable() {
        assertNull(RetryClassifier.reason("openai: This model's maximum context length is 8192 tokens", true))
        assertNull(RetryClassifier.reason("openai: This model's maximum context length is 8192 tokens", false))
    }

    @Test fun contextWindowIsNotRetriable() {
        assertNull(RetryClassifier.reason("error: input exceeds the context window", true))
    }

    @Test fun tokenLimitIsNotRetriable() {
        assertNull(RetryClassifier.reason("error: token limit reached", true))
    }

    @Test fun nonRetriableShortCircuitFiresEvenWhenRetriableHintIsTrue() {
        // Pin the precedence rule: the user-error check happens BEFORE
        // the retriableHint pass-through. A regression flipping the order
        // would let a provider with overzealous retriableHint cause
        // infinite retry on a perfectly clear "your context is too long"
        // signal.
        for (signal in listOf("context_length", "maximum context", "context window", "token limit")) {
            assertNull(
                RetryClassifier.reason("HTTP 400: $signal exceeded by 100 tokens", retriableHint = true),
                "signal '$signal' must short-circuit even with retriableHint=true",
            )
        }
    }

    // ── retriableHint=true short-circuit ───────────────────────────

    @Test fun retriableHintTrueReturnsTheMessageVerbatim() {
        // Pin: retriableHint=true returns the raw message (with original
        // case preserved) — provider-level hint is trusted as the
        // primary signal. A regression that lower-cased the return
        // value or substituted a generic string would lose the
        // provider's diagnostic detail.
        val msg = "Anthropic Internal Error: please retry"
        assertEquals(msg, RetryClassifier.reason(msg, retriableHint = true))
    }

    @Test fun retriableHintTrueShortCircuitsBeforeMessagePatternMatching() {
        // Pin: with retriableHint=true, even a message that doesn't
        // match ANY known pattern still returns the message. The hint
        // is the trusted signal; pattern matching is the fallback for
        // when the hint is false.
        val unmatchableButRetriable = "Provider X transient blip flux capacitor"
        assertEquals(
            unmatchableButRetriable,
            RetryClassifier.reason(unmatchableButRetriable, retriableHint = true),
        )
    }

    // ── HTTP status code dispatch (retriableHint=false) ────────────

    @Test fun http429ReturnsMessageEvenWithoutRetriableHint() {
        // 429 is retriable by status, even when the provider didn't
        // set retriableHint. Pin: the message itself is returned so
        // log / bus-event consumers see the provider's specific text.
        val msg = "openai HTTP 429: rate_limit_exceeded"
        assertEquals(msg, RetryClassifier.reason(msg, retriableHint = false))
    }

    @Test fun http5xxReturnsMessage() {
        for (status in listOf(500, 502, 503, 504, 599)) {
            val msg = "anthropic HTTP $status: provider error"
            assertEquals(
                msg,
                RetryClassifier.reason(msg, retriableHint = false),
                "5xx status $status must be retriable",
            )
        }
    }

    @Test fun http4xxNonRateLimitIsNotRetriable() {
        // Pin: 400 / 401 / 403 / 404 etc. are NOT retriable. Returning
        // these as retriable would cause every auth/permission/notfound
        // error to retry maxAttempts times on the same broken request.
        for (status in listOf(400, 401, 403, 404, 422)) {
            val msg = "openai HTTP $status: client error"
            assertNull(
                RetryClassifier.reason(msg, retriableHint = false),
                "4xx-non-429 status $status must not be retriable; got: ${RetryClassifier.reason(msg, false)}",
            )
        }
    }

    @Test fun http3xxIsNotRetriable() {
        // Defensive: 3xx redirects shouldn't reach the classifier
        // (HTTP client follows them), but pin the behavior anyway.
        assertNull(RetryClassifier.reason("HTTP 301: moved", retriableHint = false))
    }

    // ── Semantic pattern dispatch ──────────────────────────────────

    @Test fun overloadedReturnsProviderOverloaded() {
        assertEquals("Provider is overloaded", RetryClassifier.reason("anthropic: overloaded_error", false))
        assertEquals("Provider is overloaded", RetryClassifier.reason("system overload detected", false))
    }

    @Test fun rateLimitVariantsReturnRateLimited() {
        // Pin all 3 phrasing variants the function recognises:
        // "rate limit", "rate_limit", "rate increased too quickly".
        // A refactor narrowing to just one variant would silently
        // miss the others. Provider message wording isn't stable
        // enough to lock to one phrasing.
        assertEquals("Rate limited", RetryClassifier.reason("Rate limit reached", false))
        assertEquals("Rate limited", RetryClassifier.reason("error: rate_limit_exceeded", false))
        assertEquals("Rate limited", RetryClassifier.reason("rate increased too quickly, slow down", false))
    }

    @Test fun tooManyRequestsVariantsReturnTooManyRequests() {
        // Distinct from rate-limit branch — messages mentioning "too
        // many requests" produce a different classifier label.
        assertEquals("Too many requests", RetryClassifier.reason("error: too many requests", false))
        assertEquals("Too many requests", RetryClassifier.reason("HTTP_TOO_MANY_REQUESTS", false))
    }

    @Test fun exhaustedReturnsProviderQuotaExhausted() {
        assertEquals("Provider quota exhausted", RetryClassifier.reason("error: quota exhausted", false))
        assertEquals("Provider quota exhausted", RetryClassifier.reason("monthly quota has been exhausted", false))
    }

    @Test fun unavailableReturnsProviderUnavailable() {
        // Distinct from "overloaded" — service unavailable might be
        // a different ops question (deployment in progress, region
        // outage, etc.).
        assertEquals("Provider unavailable", RetryClassifier.reason("Service unavailable", false))
    }

    @Test fun caseInsensitivePatternMatching() {
        // Pin: the function lower-cases the message internally (line 136
        // `message.lowercase()`), so RATE LIMIT, Rate Limit, rate limit
        // all match the same arm.
        assertEquals("Rate limited", RetryClassifier.reason("RATE LIMIT EXCEEDED", false))
        assertEquals("Rate limited", RetryClassifier.reason("Rate Limit Exceeded", false))
        assertEquals("Provider is overloaded", RetryClassifier.reason("OVERLOADED ERROR", false))
    }

    @Test fun unclassifiableMessageWithoutRetriableHintReturnsNull() {
        // Pin: messages that don't match any pattern AND don't have
        // retriableHint=true return null — the classifier doesn't
        // fall through to "retry on anything that smells weird". Saves
        // the user from infinite retry on truly unknown error shapes.
        assertNull(RetryClassifier.reason("Some completely novel error shape", retriableHint = false))
        assertNull(RetryClassifier.reason("Provider X internal exception", retriableHint = false))
    }

    @Test fun emptyMessageWithoutRetriableHintReturnsNull() {
        assertNull(RetryClassifier.reason("", retriableHint = false))
    }

    @Test fun emptyMessageWithRetriableHintReturnsTheEmptyString() {
        // Pin: empty + retriableHint=true returns the empty string
        // (since empty string is the message). Documenting the
        // observed behaviour — the alternative interpretation (treat
        // empty as null) might be desired but isn't current code.
        assertEquals("", RetryClassifier.reason("", retriableHint = true))
    }

    // ── Precedence between branches ─────────────────────────────────

    @Test fun retriableHintBypassesSemanticPatterns() {
        // A message that would match "Rate limited" via pattern matching
        // returns the RAW message when retriableHint=true (because the
        // hint check fires first). Pin the precedence so a refactor
        // doesn't silently swap the order.
        val msg = "rate limit reached"
        assertEquals(
            msg,
            RetryClassifier.reason(msg, retriableHint = true),
            "retriableHint=true returns raw message before pattern matching",
        )
    }

    @Test fun contextLengthOverridesRetriableHintAndHttpStatus() {
        // Triple precedence: context-overflow check fires before BOTH
        // the retriableHint short-circuit AND the HTTP status pattern
        // match. Pin all three combinations.
        assertNull(RetryClassifier.reason("HTTP 429: context_length exceeded", retriableHint = true))
        assertNull(RetryClassifier.reason("HTTP 503: context_length exceeded", retriableHint = false))
        assertNull(RetryClassifier.reason("anthropic: token limit + rate limit error", retriableHint = true))
    }
}
