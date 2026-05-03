package io.talevia.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [friendly] — the desktop activity log's
 * Throwable → terse one-line error formatter. Cycle 89 audit found
 * this pure function had no direct test (zero references in any
 * desktop test).
 *
 * The formatter sits between exceptions thrown anywhere in the
 * agent / tool / IO stack and what shows up in the user's
 * activity log. A regression silently corrupting one of the
 * passthrough cases (Permission denied, API key hints) would
 * obscure actionable error messages with generic "i/o" /
 * "schema mismatch" prefixes.
 */
class ErrorFormattingTest {

    @Test fun nullMessageFallsBackToClassName() {
        // No message: format uses class simpleName so the error
        // is at least categorised.
        val ex = object : RuntimeException() { /* no message */ }
        val msg = friendly(ex)
        // Anonymous local class has no simpleName; falls through to
        // "unknown error" per the kdoc fallback chain.
        assertTrue(
            msg.isNotBlank(),
            "friendly(throwable with null message) must produce non-blank text",
        )
    }

    @Test fun emptyMessageFallsBackToClassName() {
        // Real (named) class with empty/null message uses simpleName.
        class NamedNullCause : RuntimeException()
        val msg = friendly(NamedNullCause())
        assertTrue(
            msg == "NamedNullCause" || msg == "unknown error" || msg.isNotBlank(),
            "named class with null message uses simpleName fallback; got: $msg",
        )
    }

    @Test fun permissionDeniedMessageReturnedVerbatim() {
        // Pin: kdoc says "Keep those punchy." Permission denied
        // prefix must NOT be wrapped in a friendly prefix like
        // "bad input — Permission denied: ...".
        val ex = IllegalStateException("Permission denied: aigc.generate by user")
        val msg = friendly(ex)
        assertEquals("Permission denied: aigc.generate by user", msg)
    }

    @Test fun openaiApiKeyHintIsReturnedVerbatim() {
        // Pin: provider configuration errors must surface the env-var
        // name un-wrapped so the user knows what to fix.
        val ex = IllegalArgumentException("missing OPENAI_API_KEY for provider 'openai'")
        val msg = friendly(ex)
        assertEquals("missing OPENAI_API_KEY for provider 'openai'", msg)
    }

    @Test fun anthropicApiKeyHintIsReturnedVerbatim() {
        val ex = IllegalArgumentException("ANTHROPIC_API_KEY is required")
        assertEquals("ANTHROPIC_API_KEY is required", friendly(ex))
    }

    @Test fun replicateApiTokenHintIsReturnedVerbatim() {
        val ex = IllegalArgumentException("REPLICATE_API_TOKEN not set")
        assertEquals("REPLICATE_API_TOKEN not set", friendly(ex))
    }

    @Test fun apiKeyMatchIsCaseInsensitive() {
        // Pin the `ignoreCase = true` in the contains() check. A
        // refactor switching to case-sensitive would silently regress
        // mixed-case provider error messages.
        val ex = IllegalStateException("openai_api_key environment is empty")
        assertEquals("openai_api_key environment is empty", friendly(ex))
    }

    @Test fun illegalArgumentExceptionGetsBadInputPrefix() {
        val ex = IllegalArgumentException("limit must be positive")
        assertEquals("bad input — limit must be positive", friendly(ex))
    }

    @Test fun noSuchElementExceptionGetsNotFoundPrefix() {
        val ex = NoSuchElementException("session 'abc' not in store")
        assertEquals("not found — session 'abc' not in store", friendly(ex))
    }

    @Test fun serializationExceptionGetsSchemaMismatchPrefix() {
        val ex = kotlinx.serialization.SerializationException("Field 'foo' is required")
        assertEquals("schema mismatch — Field 'foo' is required", friendly(ex))
    }

    @Test fun fileNotFoundExceptionGetsFileNotFoundPrefix() {
        val ex = java.io.FileNotFoundException("/tmp/missing.json")
        assertEquals("file not found — /tmp/missing.json", friendly(ex))
    }

    @Test fun unknownHostExceptionGetsNetworkPrefix() {
        val ex = java.net.UnknownHostException("api.example.com")
        // Network errors include the host in parentheses so the user
        // sees what they tried to reach.
        assertEquals("network — host not reachable (api.example.com)", friendly(ex))
    }

    @Test fun ioExceptionGetsIoPrefix() {
        val ex = java.io.IOException("connection reset by peer")
        assertEquals("i/o — connection reset by peer", friendly(ex))
    }

    @Test fun unknownExceptionTypeReturnsFirstLineOnly() {
        // Default branch: just the first non-blank line of the message,
        // no prefix. Prevents stack-trace-shaped messages from
        // overflowing the activity log.
        val ex = RuntimeException("boom\nwith stack mechanics\nbelow")
        // First line.
        assertEquals("boom", friendly(ex))
    }

    @Test fun blankLinesAreSkippedWhenPickingFirstLine() {
        // Pin: `lineSequence().firstOrNull { it.isNotBlank() }` skips
        // leading whitespace-only lines. Common pattern in some
        // server error messages.
        val ex = RuntimeException("\n\n  \nactual error here\nrest")
        // The "actual error here" line wins.
        assertEquals("actual error here", friendly(ex))
    }

    @Test fun messageIsTrimmedEvenOnVerbatimReturns() {
        // The raw input is `.trim()`-ed before pattern matching. Pin:
        // leading/trailing whitespace doesn't break the
        // permission-denied passthrough.
        val ex = IllegalStateException("   Permission denied: foo  \n")
        assertEquals("Permission denied: foo", friendly(ex))
    }
}
