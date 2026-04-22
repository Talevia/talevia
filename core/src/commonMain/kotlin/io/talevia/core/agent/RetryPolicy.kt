package io.talevia.core.agent

import kotlin.math.pow
import kotlin.random.Random

/**
 * Coarse taxonomy for why a provider call failed transiently. Drives kind-
 * specific backoff shaping inside [RetryPolicy.delayFor] — rate-limit errors
 * benefit from a longer floor than network blips because quota windows reset
 * on a minute/hour scale while TCP resets resolve in ms.
 *
 * Deliberately shallow (4 values, not N) — we don't want a cardinality
 * explosion that forces per-enum policy tuning. Anything we can't classify
 * falls into [OTHER] and uses the base policy unchanged.
 */
enum class BackoffKind {
    /** HTTP 429 / "rate limit" / "too many requests" / "quota exhausted". */
    RATE_LIMIT,

    /** HTTP 5xx / "overloaded" / "unavailable". */
    SERVER,

    /** Connection reset, socket timeout, DNS failure — not currently emitted
     *  by our providers explicitly but reserved for future network-layer
     *  classification. */
    NETWORK,

    /** Anything else the classifier didn't match — use base policy. */
    OTHER,
}

/**
 * Tunables for [Agent]'s transient-error retry loop. Mirrors OpenCode's
 * `session/retry.ts` constants — exponential backoff (2s, 4s, 8s, ...) capped
 * either by a provider-supplied `retry-after` header or by
 * [maxDelayNoHeadersMs] when the provider is silent.
 *
 * [maxAttempts] is the total number of provider-stream attempts per LLM turn,
 * not the number of retries — `maxAttempts = 1` disables retry entirely.
 *
 * Two extras beyond the flat exponential curve:
 *  - [jitterFactor] — randomly multiplies the computed delay by
 *    `[1-jitterFactor, 1+jitterFactor]` so a wall-clock-aligned batch of
 *    clients doesn't retry in lockstep. Defaults to 0.2 (±20%); set to `0.0`
 *    for deterministic tests.
 *  - [rateLimitMinDelayMs] — when the failure classifies as
 *    [BackoffKind.RATE_LIMIT] and no provider `retry-after` is available,
 *    the delay is floored at this value. Null = no floor (base curve wins).
 *    Useful because retrying a 429 in 2 seconds wastes a call; quota windows
 *    reset on minute-scale.
 */
data class RetryPolicy(
    val maxAttempts: Int = 4,
    val initialDelayMs: Long = 2_000,
    val backoffFactor: Double = 2.0,
    val maxDelayNoHeadersMs: Long = 30_000,
    val maxDelayMs: Long = 10 * 60_000,
    val jitterFactor: Double = 0.2,
    val rateLimitMinDelayMs: Long? = 15_000,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1 (got $maxAttempts)" }
        require(initialDelayMs >= 0) { "initialDelayMs must be >= 0" }
        require(backoffFactor >= 1.0) { "backoffFactor must be >= 1.0" }
        require(jitterFactor in 0.0..1.0) {
            "jitterFactor must be in [0.0, 1.0] (got $jitterFactor)"
        }
        require(rateLimitMinDelayMs == null || rateLimitMinDelayMs >= 0) {
            "rateLimitMinDelayMs must be >= 0 or null (got $rateLimitMinDelayMs)"
        }
    }

    /**
     * Compute the wait before [attempt] (1-based — `attempt = 1` is the first
     * retry after the initial failure). Honors a provider-supplied
     * [retryAfterMs] if present, else exponential backoff capped by
     * [maxDelayNoHeadersMs].
     *
     * [kind] defaults to [BackoffKind.OTHER]; when [BackoffKind.RATE_LIMIT]
     * is passed, applies [rateLimitMinDelayMs] as a floor on the exponential
     * curve (before jitter / after retry-after honour). [random] is
     * parametric to make the jitter deterministic under test.
     */
    fun delayFor(
        attempt: Int,
        retryAfterMs: Long? = null,
        kind: BackoffKind = BackoffKind.OTHER,
        random: Random = Random.Default,
    ): Long {
        require(attempt >= 1) { "attempt must be >= 1" }
        if (retryAfterMs != null && retryAfterMs > 0) {
            return retryAfterMs.coerceAtMost(maxDelayMs)
        }
        val raw = initialDelayMs.toDouble() * backoffFactor.pow(attempt - 1)
        var capped = raw.coerceAtMost(maxDelayNoHeadersMs.toDouble()).toLong()
        if (kind == BackoffKind.RATE_LIMIT && rateLimitMinDelayMs != null) {
            capped = capped.coerceAtLeast(rateLimitMinDelayMs)
                .coerceAtMost(maxDelayMs) // floor still respects the global max
        }
        val jittered = if (jitterFactor == 0.0) {
            capped
        } else {
            val lo = 1.0 - jitterFactor
            val hi = 1.0 + jitterFactor
            (capped.toDouble() * (lo + random.nextDouble() * (hi - lo))).toLong()
                .coerceAtLeast(0L)
        }
        return jittered.coerceAtMost(maxDelayMs)
    }

    companion object {
        val Default: RetryPolicy = RetryPolicy()
        val None: RetryPolicy = RetryPolicy(maxAttempts = 1)

        /**
         * Deterministic policy for unit tests — zero jitter so `delayFor`
         * calls are reproducible without threading a `Random` seed.
         */
        val Deterministic: RetryPolicy = RetryPolicy(jitterFactor = 0.0)
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

    /**
     * Classify the retryable reason into a [BackoffKind] so [RetryPolicy]
     * can apply kind-specific shaping (floor for RATE_LIMIT, etc.). Takes
     * the same raw message + retriable-hint inputs as [reason] so callers
     * can classify in one pass.
     *
     * Returns [BackoffKind.OTHER] for unclassifiable messages — the policy
     * treats OTHER as "use the base curve unchanged", so we never fail
     * closed on an unrecognised message shape.
     */
    @Suppress("UNUSED_PARAMETER")
    fun kind(message: String?, retriableHint: Boolean): BackoffKind {
        val msg = message?.lowercase() ?: return BackoffKind.OTHER

        // HTTP status classification takes priority — transport signal
        // beats semantic signal when both are present.
        val httpStatus = Regex("""http\s+(\d{3})""").find(msg)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (httpStatus != null) {
            return when {
                httpStatus == 429 -> BackoffKind.RATE_LIMIT
                httpStatus in 500..599 -> BackoffKind.SERVER
                else -> BackoffKind.OTHER
            }
        }

        if ("rate limit" in msg ||
            "rate_limit" in msg ||
            "too many requests" in msg ||
            "too_many_requests" in msg ||
            "rate increased too quickly" in msg ||
            "quota" in msg ||
            "exhausted" in msg
        ) {
            return BackoffKind.RATE_LIMIT
        }
        if ("overloaded" in msg || "overload" in msg || "unavailable" in msg) {
            return BackoffKind.SERVER
        }
        if ("network" in msg || "timeout" in msg || "connection reset" in msg) {
            return BackoffKind.NETWORK
        }
        // retriable-hinted but unclassifiable → still use OTHER so the base
        // curve applies, but don't fail closed. retriableHint intentionally
        // ignored once we got here — its role is upstream in `reason(...)`.
        return BackoffKind.OTHER
    }
}
