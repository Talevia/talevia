package io.talevia.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structural carrier for next-step suggestions when [Agent.run] terminates
 * in [AgentRunState.Failed]. Lets the user-facing surface render concrete
 * recovery actions instead of dumping the raw exception message — small
 * users get to know what to try, even if the LLM didn't get a chance to
 * compose a friendly farewell.
 *
 * VISION §5.4 / M3 exit criterion #5 ("失败时 agent 输出含 try.*provider /
 * next.*step / 换 provider 之类，让小白用户知道下一步该做什么"). The
 * structural carrier is required because a system-prompt nudge alone fails
 * the moment the LLM never gets to a final message — provider 5xx that
 * gives up mid-stream, network drop, retry-exhaustion: the LLM literally
 * cannot speak. The bus event always fires regardless, and subscribers
 * (CLI / desktop UIs) can render the [suggestions] strings inline.
 *
 * Variants are derived from [BackoffKind] (the existing retry-classifier's
 * coarse taxonomy) plus [Uncaught] as the catch-all so every Throwable
 * produces something. New failure classes get a new sealed subtype, not a
 * boolean flag — see §3a #4 ("don't use binary state fields").
 */
@Serializable
sealed interface FallbackHint {
    /**
     * 1–3 short prose strings rendered to the user verbatim. Order matters:
     * the first is the most likely to recover, the last is the "give up
     * gracefully" branch. Always non-empty (even [Uncaught] supplies a
     * generic line); empty list would defeat the structural-carrier
     * guarantee.
     */
    val suggestions: List<String>

    /**
     * Provider returned 5xx / "overloaded" / "unavailable". A different
     * provider in the fallback chain is likely to succeed; if no fallback
     * is configured, suggest waiting for recovery.
     */
    @Serializable
    @SerialName("provider_unavailable")
    data class ProviderUnavailable(
        override val suggestions: List<String> = listOf(
            "try a different provider",
            "wait a few minutes for the provider to recover",
        ),
    ) : FallbackHint

    /**
     * Provider returned 429 / "rate limit" / "quota exhausted". Rate-limit
     * windows reset on minute scale (see [RetryPolicy.rateLimitMinDelayMs]);
     * suggesting "switch provider" routes around the per-account quota.
     */
    @Serializable
    @SerialName("rate_limited")
    data class RateLimited(
        override val suggestions: List<String> = listOf(
            "wait a minute and retry — the rate-limit window resets shortly",
            "try a different provider to bypass the per-account quota",
        ),
    ) : FallbackHint

    /**
     * Network-layer failure (timeout / connection reset / DNS). User-side
     * connectivity issue most of the time; retry shortly is usually enough.
     */
    @Serializable
    @SerialName("network")
    data class Network(
        override val suggestions: List<String> = listOf(
            "check your network connection",
            "try again in a few seconds — transient network blips usually clear quickly",
        ),
    ) : FallbackHint

    /**
     * Failure didn't match any known class. Generic suggestions that
     * cover the most common recoveries (rephrase, hand back to the
     * human). Includes "let me take over manually" so the M3 #5 spec
     * "让小白用户知道下一步该做什么" is satisfied even on uncategorisable
     * throwables.
     */
    @Serializable
    @SerialName("uncaught")
    data class Uncaught(
        override val suggestions: List<String> = listOf(
            "rephrase the request and try again — the model may have misread an ambiguous instruction",
            "let me take over manually — the agent could not complete the next step on its own",
        ),
    ) : FallbackHint
}

/**
 * Maps a failure message into a [FallbackHint]. Reuses [RetryClassifier.kind]
 * so the agent's view of "what kind of failure is this" stays consistent
 * across the retry policy (drives backoff shaping) and the user-facing
 * fallback (drives suggestion text). One classifier, two consumers — when
 * we add a new [BackoffKind], this function gets a new arm and both
 * consumers update together.
 */
object FallbackClassifier {
    fun classify(message: String?): FallbackHint {
        if (message.isNullOrBlank()) return FallbackHint.Uncaught()
        return when (RetryClassifier.kind(message, retriableHint = true)) {
            BackoffKind.RATE_LIMIT -> FallbackHint.RateLimited()
            BackoffKind.SERVER -> FallbackHint.ProviderUnavailable()
            BackoffKind.NETWORK -> FallbackHint.Network()
            BackoffKind.OTHER -> FallbackHint.Uncaught()
        }
    }

    fun classify(cause: Throwable): FallbackHint =
        classify(cause.message ?: cause::class.simpleName)
}
