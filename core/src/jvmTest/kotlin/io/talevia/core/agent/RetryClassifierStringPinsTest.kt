package io.talevia.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * String-set drift pins for [RetryClassifier]. The sibling `RetryPolicyTest`
 * (17 existing tests) covers happy-path behavior for the classifier — at
 * least one example per [BackoffKind] passes through `kind()` and at least
 * one HTTP-priority anti-corruption case is pinned. But several
 * load-bearing classifier strings are NOT individually pinned today:
 *
 * - **`reason()` terminal-error strings (0 of 4 pinned)**: `"context_length"`,
 *   `"maximum context"`, `"context window"`, `"token limit"`. A missing
 *   pin means a refactor renaming or removing one would silently start
 *   retrying a terminal error — wasting the entire retry budget on an
 *   error that's guaranteed to fail again. Provider hosts emit different
 *   spellings so the OR-list is the contract.
 *
 * - **`kind()` RATE_LIMIT strings (3 of 7 pinned)**: existing
 *   `classifiesSemanticRateLimitTexts` covers "Rate limit", "too many
 *   requests", "quota exhausted". Not pinned: `"rate_limit"` (underscore
 *   variant), `"too_many_requests"` (underscore variant),
 *   `"rate increased too quickly"`, the `"exhausted"` substring on its
 *   own (currently only tested via "quota exhausted" which double-hits
 *   "quota" + "exhausted"). Different providers emit different spellings;
 *   the underscore variants are how OpenAI and Anthropic name their
 *   structured error fields, so dropping them silently breaks classifier
 *   accuracy on every real production error.
 *
 * - **`kind()` SERVER strings (2 of 3 pinned)**: `"overloaded"` and
 *   `"unavailable"` are pinned via existing tests. Not pinned: `"overload"`
 *   (the substring without "ed", which catches "overload" / "overloads" /
 *   "overload_error" — Anthropic uses "overloaded_error"; the OR makes both
 *   match).
 *
 * - **`kind()` NETWORK strings (2 of 3 pinned)**: `"connection reset"` and
 *   `"timeout"` are pinned. Not pinned: `"network"` on its own (no
 *   compound test exercises just this token).
 *
 * - **`reason()` user-facing message strings (0 of 5 pinned)**: when
 *   classifier hits a semantic match, `reason()` returns a CANONICAL string
 *   like "Provider is overloaded", "Rate limited", "Too many requests",
 *   "Provider quota exhausted", "Provider unavailable". These are what
 *   end up in user-visible error logs. Drift in any one would change the
 *   user surface without a single broken test.
 *
 * - **HTTP status boundary (currently only one example pinned)**: 500 ≤ x ≤
 *   599 → SERVER; 600 → OTHER. The boundary cases (500 inclusive, 599
 *   inclusive, 600 falling through) aren't pinned; off-by-one in the
 *   range check would silently misclassify edge cases.
 *
 * Same audit-pattern as cycles 309/310/313/314 — pin every load-bearing
 * literal so a refactor lands in test-red instead of silent
 * misclassification.
 */
class RetryClassifierStringPinsTest {

    // ── reason() terminal-error context-overflow pins (4 strings) ──

    @Test fun reasonReturnsNullForContextLengthString() {
        assertNull(
            RetryClassifier.reason("openai HTTP 400: context_length_exceeded", retriableHint = false),
            "context_length errors are terminal — retrying wastes the budget",
        )
    }

    @Test fun reasonReturnsNullForMaximumContextString() {
        assertNull(
            RetryClassifier.reason("error: maximum context length is 200000 tokens", retriableHint = false),
            "'maximum context' indicates terminal overflow — never retry",
        )
    }

    @Test fun reasonReturnsNullForContextWindowString() {
        assertNull(
            RetryClassifier.reason("input exceeds context window of this model", retriableHint = false),
            "'context window' overflow is terminal",
        )
    }

    @Test fun reasonReturnsNullForTokenLimitString() {
        assertNull(
            RetryClassifier.reason("token limit exceeded", retriableHint = false),
            "'token limit' is the OpenAI-flavored terminal-overflow string",
        )
    }

    @Test fun reasonReturnsNullEvenWhenRetriableHintIsTrueForContextOverflow() {
        // Important: the terminal-overflow check fires BEFORE the
        // retriableHint short-circuit. A provider mis-flagging a
        // 400-context-overflow as retriable would get bailed out
        // by reason()'s explicit early-null path. Drift to swap
        // these checks would silently retry overflow forever.
        assertNull(
            RetryClassifier.reason("HTTP 503: context_length detected but retry hinted", retriableHint = true),
            "context-overflow check MUST take priority over retriableHint=true",
        )
    }

    // ── kind() RATE_LIMIT semantic strings (7 total, 4 not in existing tests) ──

    @Test fun kindReturnsRateLimitForUnderscoreRateLimit() {
        // OpenAI structured error type field: "type": "rate_limit_error".
        // Without underscore-variant pinning, dropping the underscore
        // string from the OR list would silently route OpenAI's actual
        // 429s through OTHER instead of RATE_LIMIT.
        assertEquals(
            BackoffKind.RATE_LIMIT,
            RetryClassifier.kind("error: rate_limit_error encountered", retriableHint = true),
        )
    }

    @Test fun kindReturnsRateLimitForUnderscoreTooManyRequests() {
        // Similarly the underscore variant: "type": "too_many_requests".
        assertEquals(
            BackoffKind.RATE_LIMIT,
            RetryClassifier.kind("provider: too_many_requests in last second", retriableHint = true),
        )
    }

    @Test fun kindReturnsRateLimitForRateIncreasedTooQuicklyString() {
        // Anthropic-specific phrasing — existing tests don't cover.
        assertEquals(
            BackoffKind.RATE_LIMIT,
            RetryClassifier.kind("rate increased too quickly across 1m window", retriableHint = true),
        )
    }

    @Test fun kindReturnsRateLimitForBareExhaustedString() {
        // Existing test uses "quota exhausted" which double-hits both
        // "quota" and "exhausted". This pins that "exhausted" alone
        // (e.g. "credits exhausted") still classifies. Drift to make
        // the substring AND-linked with "quota" would silently miss
        // non-quota exhaustion errors.
        assertEquals(
            BackoffKind.RATE_LIMIT,
            RetryClassifier.kind("credits exhausted", retriableHint = true),
        )
    }

    @Test fun kindReturnsRateLimitForBareQuotaString() {
        // Sister pin to "exhausted alone": "quota" alone (e.g.
        // "monthly quota reached" without "exhausted"). Both are
        // listed in the OR-set; drift dropping either one silently
        // misclassifies.
        assertEquals(
            BackoffKind.RATE_LIMIT,
            RetryClassifier.kind("monthly quota reached", retriableHint = true),
        )
    }

    // ── kind() SERVER strings (3 total, 1 not in existing tests) ──

    @Test fun kindReturnsServerForOverloadWithoutEd() {
        // "overload" (without -ed) catches "overload" / "overloads" /
        // "overload_error" / "overload_recovery". Existing test only
        // covers "overloaded". Anthropic emits "overloaded_error" which
        // matches both, but a hypothetical provider emitting
        // "overload_recovery_in_progress" only matches "overload" — pin
        // this so the OR-list isn't trimmed.
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("provider in overload_recovery state", retriableHint = true),
        )
    }

    // ── kind() NETWORK strings (3 total, 1 not in existing tests) ──

    @Test fun kindReturnsNetworkForBareNetworkString() {
        // Existing tests cover "connection reset" + "timeout"; the bare
        // "network" string catches phrases like "network error" or
        // "network unreachable" not covered by the other two.
        assertEquals(
            BackoffKind.NETWORK,
            RetryClassifier.kind("transient network error", retriableHint = true),
        )
    }

    // ── reason() user-facing canonical message pins (5 strings) ──

    @Test fun reasonReturnsRateLimitedCanonicalMessage() {
        // The classifier returns a canonical user-facing string for
        // each semantic match. These end up in error logs / UI
        // displays. Drift in any one changes user surface text.
        assertEquals(
            "Rate limited",
            RetryClassifier.reason("OpenAI: rate limit exceeded", retriableHint = false),
        )
    }

    @Test fun reasonReturnsTooManyRequestsCanonicalMessage() {
        assertEquals(
            "Too many requests",
            RetryClassifier.reason("Anthropic: too many requests", retriableHint = false),
        )
    }

    @Test fun reasonReturnsProviderQuotaExhaustedCanonicalMessage() {
        assertEquals(
            "Provider quota exhausted",
            RetryClassifier.reason("Token budget exhausted for this hour", retriableHint = false),
        )
    }

    @Test fun reasonReturnsProviderOverloadedCanonicalMessage() {
        assertEquals(
            "Provider is overloaded",
            RetryClassifier.reason("API is overloaded right now", retriableHint = false),
        )
    }

    @Test fun reasonReturnsProviderUnavailableCanonicalMessage() {
        assertEquals(
            "Provider unavailable",
            RetryClassifier.reason("Service unavailable: try later", retriableHint = false),
        )
    }

    // ── HTTP status range boundaries on kind() ─────────────────────

    @Test fun kindHttp500LowerBoundClassifiesAsServer() {
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("provider HTTP 500 internal error", retriableHint = false),
            "HTTP 500 is the inclusive lower bound of the SERVER range",
        )
    }

    @Test fun kindHttp599UpperBoundClassifiesAsServer() {
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("provider HTTP 599 network connect timeout", retriableHint = false),
            "HTTP 599 is the inclusive upper bound of the SERVER range",
        )
    }

    @Test fun kindHttp600FallsThroughToOther() {
        // 600 is non-standard but the classifier explicitly bounds at
        // 599 — anything above falls through to semantic text matching,
        // and "HTTP 600" alone doesn't hit any semantic substring, so
        // OTHER. Pin this so the upper-bound check stays exact.
        assertEquals(
            BackoffKind.OTHER,
            RetryClassifier.kind("HTTP 600 nonstandard", retriableHint = false),
            "HTTP 600 is above the SERVER range and lacks semantic match → OTHER",
        )
    }

    @Test fun kindHttp499FallsThroughToOther() {
        // 4xx other than 429 should fall through. Most are terminal
        // (400 bad request, 401 unauthorized, 403 forbidden) but the
        // classifier doesn't explicitly classify these — they go to
        // OTHER, and reason() handles whether they're retriable via
        // retriableHint. Pin to confirm the 4xx-not-429 range routes
        // to OTHER not RATE_LIMIT.
        assertEquals(
            BackoffKind.OTHER,
            RetryClassifier.kind("HTTP 499 weird error", retriableHint = false),
        )
    }

    // ── Case-insensitivity (msg.lowercase() pin) ───────────────────

    @Test fun classifierIsCaseInsensitiveForMixedCaseHttpStatus() {
        // The HTTP regex matches `http\s+\d{3}` after lowercasing.
        // Drift removing the lowercase() call would misclassify
        // upper-case "HTTP 503" as OTHER (since the regex is `http`
        // lowercase). Pin both forms.
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("HTTP 503: server error", retriableHint = false),
        )
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("Http 503: server error", retriableHint = false),
        )
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("http 503: server error", retriableHint = false),
        )
    }

    @Test fun classifierIsCaseInsensitiveForSemanticText() {
        assertEquals(
            BackoffKind.RATE_LIMIT,
            RetryClassifier.kind("RATE LIMIT EXCEEDED", retriableHint = true),
        )
        assertEquals(
            BackoffKind.SERVER,
            RetryClassifier.kind("OVERLOADED RIGHT NOW", retriableHint = true),
        )
    }

    // ── reason() retriableHint short-circuit ───────────────────────

    @Test fun reasonReturnsMessageVerbatimWhenRetriableHintTrue() {
        // When retriableHint=true and the message ISN'T a context-
        // overflow case, reason() returns the original message
        // unchanged (does NOT canonicalise it). Pin this so
        // refactor changing the early-return doesn't silently start
        // overwriting useful provider error context.
        val raw = "anthropic HTTP 500: server_error: temporarily unable to handle request"
        assertEquals(
            raw,
            RetryClassifier.reason(raw, retriableHint = true),
            "retriableHint=true MUST short-circuit reason() to return msg verbatim",
        )
    }

    @Test fun reasonReturnsNullForUnclassifiableNonRetriableMessage() {
        // The default fall-through: an unclassifiable message with
        // retriableHint=false MUST return null (= terminal). A
        // refactor accidentally returning the message anyway would
        // start retrying genuinely terminal errors.
        assertNull(
            RetryClassifier.reason("some completely unrecognised error", retriableHint = false),
            "unclassifiable + retriableHint=false MUST be null (terminal)",
        )
    }

    @Test fun reasonReturnsNullForNullMessage() {
        // Defensive contract: null message → null reason regardless
        // of retriableHint. A NPE-introduction refactor surfaces here.
        assertNull(RetryClassifier.reason(null, retriableHint = true))
        assertNull(RetryClassifier.reason(null, retriableHint = false))
    }

    @Test fun kindReturnsOtherForNullMessage() {
        // Sister to the reason()-null-message pin — kind() must also
        // tolerate null and route to OTHER (use base curve).
        assertEquals(BackoffKind.OTHER, RetryClassifier.kind(null, retriableHint = true))
        assertEquals(BackoffKind.OTHER, RetryClassifier.kind(null, retriableHint = false))
    }

    // ── reason() classifier matches ARE returned, not bypassed ────

    @Test fun reasonReturnsClassifiedSemanticMatchEvenWhenRetriableHintFalse() {
        // The semantic classifier branches (overloaded / rate limit /
        // etc.) fire AFTER the retriableHint short-circuit. So even
        // when the provider didn't hint retriable, reason() can still
        // classify the message via semantic matching. Pin this so
        // ordering refactor surfaces here.
        assertNotNull(
            RetryClassifier.reason("API was overloaded", retriableHint = false),
            "retriableHint=false MUST still allow semantic classification to fire",
        )
    }
}
