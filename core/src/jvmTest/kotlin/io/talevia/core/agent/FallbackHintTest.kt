package io.talevia.core.agent

import io.talevia.core.JsonConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pin VISION §5.4 / M3 #5 fallback-suggestion classification: every Failed
 * terminal must carry concrete recovery actions so a small user knows the
 * next step even when the LLM never got to compose a friendly farewell.
 *
 * Coverage targets the four [BackoffKind] equivalence classes routed
 * through [FallbackClassifier], the boundary cases (null / blank message,
 * an arbitrary Throwable subclass), and forward-compat serialisation of
 * [AgentRunState.Failed] with the new `fallback` field.
 */
class FallbackHintTest {

    @Test fun http5xxClassifiesAsProviderUnavailable() {
        val hint = FallbackClassifier.classify("anthropic HTTP 503: overloaded_error: ...")
        assertTrue(hint is FallbackHint.ProviderUnavailable, "got $hint")
        assertTrue(
            hint.suggestions.any { "try a different provider" in it.lowercase() },
            "ProviderUnavailable must mention switching provider — got ${hint.suggestions}",
        )
    }

    @Test fun overloadedTextClassifiesAsProviderUnavailable() {
        val hint = FallbackClassifier.classify("Provider returned: overloaded; please retry")
        assertTrue(hint is FallbackHint.ProviderUnavailable, "got $hint")
    }

    @Test fun http429ClassifiesAsRateLimited() {
        val hint = FallbackClassifier.classify("openai HTTP 429: rate_limit_exceeded")
        assertTrue(hint is FallbackHint.RateLimited, "got $hint")
        assertTrue(
            hint.suggestions.any { "wait" in it.lowercase() || "rate-limit" in it.lowercase() },
            "RateLimited must mention waiting / rate-limit window — got ${hint.suggestions}",
        )
    }

    @Test fun rateLimitTextClassifiesAsRateLimited() {
        val hint = FallbackClassifier.classify("rate limit reached for gpt-5")
        assertTrue(hint is FallbackHint.RateLimited)
    }

    @Test fun quotaExhaustedClassifiesAsRateLimited() {
        // RetryClassifier maps "quota" / "exhausted" to RATE_LIMIT — the
        // user-facing wording matches that grouping (per-account quota,
        // resets on a window).
        val hint = FallbackClassifier.classify("anthropic: quota exhausted for this minute")
        assertTrue(hint is FallbackHint.RateLimited, "got $hint")
    }

    @Test fun timeoutClassifiesAsNetwork() {
        val hint = FallbackClassifier.classify("socket timeout after 30s")
        assertTrue(hint is FallbackHint.Network, "got $hint")
        assertTrue(
            hint.suggestions.any { "network" in it.lowercase() || "connection" in it.lowercase() },
            "Network must mention network / connection — got ${hint.suggestions}",
        )
    }

    @Test fun connectionResetClassifiesAsNetwork() {
        val hint = FallbackClassifier.classify("connection reset by peer")
        assertTrue(hint is FallbackHint.Network)
    }

    @Test fun unrecognisedMessageClassifiesAsUncaught() {
        // No recognised pattern — falls through to the catch-all variant.
        // Suggestions still cover the "let me take over manually" branch
        // so M3 #5's "next-step for small users" guarantee holds.
        val hint = FallbackClassifier.classify("internal: clip 7 doesn't have an asset binding")
        assertTrue(hint is FallbackHint.Uncaught, "got $hint")
        assertTrue(
            hint.suggestions.any { "let me take over" in it.lowercase() },
            "Uncaught must offer manual takeover — got ${hint.suggestions}",
        )
    }

    @Test fun nullMessageClassifiesAsUncaught() {
        val hint = FallbackClassifier.classify(null as String?)
        assertTrue(hint is FallbackHint.Uncaught)
    }

    @Test fun blankMessageClassifiesAsUncaught() {
        val hint = FallbackClassifier.classify("   ")
        assertTrue(hint is FallbackHint.Uncaught)
    }

    @Test fun throwableOverloadFallsBackOnSimpleNameWhenMessageNull() {
        // Programmer error — `error("…")` etc. — has a non-null message.
        // But Throwables built directly with null message use the class
        // name. The Throwable overload must not crash and must still
        // produce a hint.
        class CustomBoom : RuntimeException(null as String?)
        val hint = FallbackClassifier.classify(CustomBoom())
        assertNotNull(hint)
        // CustomBoom doesn't match any pattern → Uncaught.
        assertTrue(hint is FallbackHint.Uncaught)
    }

    @Test fun throwableOverloadDelegatesToMessageClassifier() {
        val hint = FallbackClassifier.classify(RuntimeException("HTTP 503 from provider"))
        assertTrue(hint is FallbackHint.ProviderUnavailable, "got $hint")
    }

    @Test fun everyVariantSuggestionListIsNonEmpty() {
        // Structural guarantee — even a default-constructed variant must
        // produce at least one suggestion line. A FallbackHint with empty
        // suggestions defeats the whole point of the structural carrier.
        val variants: List<FallbackHint> = listOf(
            FallbackHint.ProviderUnavailable(),
            FallbackHint.RateLimited(),
            FallbackHint.Network(),
            FallbackHint.Uncaught(),
        )
        for (v in variants) {
            assertFalse(
                v.suggestions.isEmpty(),
                "${v::class.simpleName} default suggestions must be non-empty",
            )
        }
    }

    @Test fun failedSerialisesWithFallback() {
        val original = AgentRunState.Failed(
            cause = "HTTP 503 overloaded",
            fallback = FallbackHint.ProviderUnavailable(),
        )
        val json = JsonConfig.default
        val encoded = json.encodeToString(AgentRunState.serializer(), original)
        val decoded = json.decodeFromString(AgentRunState.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test fun legacyFailedJsonWithoutFallbackDecodesWithDefault() {
        // Forward-compat: rows persisted before the fallback field landed
        // (or test code that constructs `Failed("…")` without the second
        // arg) must still decode, with the default Uncaught hint filled in.
        // We derive the encoded shape from a fresh round-trip rather than
        // hardcoding the discriminator value (kotlinx.serialization picks
        // FQN by default and we don't want to couple the test to that).
        val json = JsonConfig.default
        val encoded = json.encodeToString(
            AgentRunState.serializer(),
            AgentRunState.Failed(cause = "some old error"),
        )
        // Strip the fallback object that the encoder emits — simulate a
        // pre-field row. The cause field stays.
        val legacyShaped = encoded.replace(Regex(""","fallback":\{[^}]+\}"""), "")
        assertFalse(
            "fallback" in legacyShaped,
            "test setup: legacy-shaped JSON must not contain the fallback field — got $legacyShaped",
        )
        val decoded = json.decodeFromString(AgentRunState.serializer(), legacyShaped)
        assertTrue(decoded is AgentRunState.Failed)
        assertEquals("some old error", decoded.cause)
        assertTrue(decoded.fallback is FallbackHint.Uncaught)
    }
}
