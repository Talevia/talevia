package io.talevia.core.agent

import kotlin.math.pow

/**
 * Tunables for [Agent]'s transient-error retry loop. Mirrors OpenCode's
 * `session/retry.ts` constants — exponential backoff (2s, 4s, 8s, ...) capped
 * either by a provider-supplied `retry-after` header or by
 * [maxDelayNoHeadersMs] when the provider is silent.
 *
 * [maxAttempts] is the total number of provider-stream attempts per LLM turn,
 * not the number of retries — `maxAttempts = 1` disables retry entirely.
 */
data class RetryPolicy(
    val maxAttempts: Int = 4,
    val initialDelayMs: Long = 2_000,
    val backoffFactor: Double = 2.0,
    val maxDelayNoHeadersMs: Long = 30_000,
    val maxDelayMs: Long = 10 * 60_000,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1 (got $maxAttempts)" }
        require(initialDelayMs >= 0) { "initialDelayMs must be >= 0" }
        require(backoffFactor >= 1.0) { "backoffFactor must be >= 1.0" }
    }

    /**
     * Compute the wait before [attempt] (1-based — `attempt = 1` is the first
     * retry after the initial failure). Honors a provider-supplied
     * [retryAfterMs] if present, else exponential backoff capped by
     * [maxDelayNoHeadersMs].
     */
    fun delayFor(attempt: Int, retryAfterMs: Long? = null): Long {
        require(attempt >= 1) { "attempt must be >= 1" }
        if (retryAfterMs != null && retryAfterMs > 0) {
            return retryAfterMs.coerceAtMost(maxDelayMs)
        }
        val raw = initialDelayMs.toDouble() * backoffFactor.pow(attempt - 1)
        val capped = raw.coerceAtMost(maxDelayNoHeadersMs.toDouble()).toLong()
        return capped.coerceAtMost(maxDelayMs)
    }

    companion object {
        val Default: RetryPolicy = RetryPolicy()
        val None: RetryPolicy = RetryPolicy(maxAttempts = 1)
    }
}

/**
 * Classifies an [io.talevia.core.provider.LlmEvent.Error] into a retryable
 * reason or `null` when the error is terminal. Mirrors OpenCode's
 * `retryable()` (session/retry.ts:54-104): 5xx / 429 are retriable, as are
 * messages mentioning "overloaded", "rate limit", "too many requests",
 * "exhausted", "unavailable". Context-overflow errors are explicitly not
 * retriable.
 *
 * Returns the human-readable reason to log / publish, or `null` to skip
 * retry.
 */
object RetryClassifier {
    fun reason(message: String?, retriableHint: Boolean): String? {
        val msg = message?.lowercase() ?: return null

        // Explicit non-retriable: context window overflow.
        if ("context_length" in msg ||
            "maximum context" in msg ||
            "context window" in msg ||
            "token limit" in msg
        ) {
            return null
        }

        if (retriableHint) return message

        // HTTP status codes embedded in the provider-formatted message
        // (e.g. "anthropic HTTP 503: overloaded_error: ...").
        Regex("""http\s+(\d{3})""").find(msg)?.groupValues?.get(1)?.toIntOrNull()?.let { status ->
            if (status in 500..599 || status == 429) return message
        }

        if ("overloaded" in msg || "overload" in msg) return "Provider is overloaded"
        if ("rate limit" in msg || "rate_limit" in msg || "rate increased too quickly" in msg) {
            return "Rate limited"
        }
        if ("too many requests" in msg || "too_many_requests" in msg) return "Too many requests"
        if ("exhausted" in msg) return "Provider quota exhausted"
        if ("unavailable" in msg) return "Provider unavailable"

        return null
    }
}
