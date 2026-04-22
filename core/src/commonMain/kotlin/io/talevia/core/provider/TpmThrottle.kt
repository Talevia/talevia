package io.talevia.core.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement

/**
 * Sliding-window tokens-per-minute guard for LLM providers.
 *
 * OpenAI (and most hosted providers) enforce a per-organization TPM limit that the
 * Agent's RetryPolicy currently only learns about after a 429 comes back. That's
 * correct-but-wasteful: a 429 consumes a full round-trip, the provider's `retry-after`
 * window burns wall-clock time, and the tail latency rises sharply once we cross the
 * limit. This class lets the provider estimate a request's token cost *before* firing
 * it and hold the call back until the sliding window has room — turning "spray 429s,
 * back off, retry" into "wait the minimum necessary, then send once".
 *
 * Purely preventative. On an under-utilised account `acquire` is essentially free
 * (one mutex hop, no sleep). The 429 fallback still works if the estimate undershoots
 * or the limit is undeclared.
 *
 * Design notes:
 *  - Sliding window = 60s by default; records keyed by monotonic `Clock` time.
 *  - `acquire(estimate)` blocks until adding `estimate` wouldn't exceed the budget,
 *    then reserves the slot. Caller promises to eventually [settle] with the real
 *    usage (so the window self-corrects if the estimate was off).
 *  - `bufferRatio` stays below 1.0 so the estimate-side jitter leaves headroom for
 *    the provider's own accounting drift.
 *  - Purely library-level: no I/O, no logging, no retry loop — that's the Agent's job.
 */
class TpmThrottle(
    /** The org-level tokens-per-minute cap, as reported by the provider. */
    private val tpmLimit: Long,
    /**
     * Fraction of [tpmLimit] we'll actually try to use. 0.85 leaves 15% headroom
     * for the provider's accounting drift and for concurrent requests we don't see.
     */
    private val bufferRatio: Double = 0.85,
    private val windowMs: Long = 60_000L,
    private val clock: Clock = Clock.System,
) {
    init {
        require(tpmLimit > 0) { "tpmLimit must be positive" }
        require(bufferRatio in 0.0..1.0) { "bufferRatio must be in [0, 1], got $bufferRatio" }
        require(windowMs > 0) { "windowMs must be positive" }
    }

    internal data class Record(val tsMs: Long, var tokens: Long)

    private val mutex = Mutex()
    private val records = ArrayDeque<Record>()

    /** Tokens the current window has reserved (estimated) or actually spent. For tests. */
    suspend fun usedTokens(): Long = mutex.withLock {
        evict()
        records.sumOf { it.tokens }
    }

    /**
     * Mark the whole budget as consumed until [retryAfterMs] from now. Call this when the
     * provider returns 429 with a Retry-After hint: the local accounting won't reflect
     * whatever tokens the provider thinks we've burned (that number lives server-side),
     * so we synthesize a full-budget record that will evict exactly when the cooldown
     * expires. Any pending [acquire] will then wait out that cooldown instead of
     * immediately re-firing the request and eating another 429.
     *
     * Clamps [retryAfterMs] to `[0, windowMs]` — values beyond one window don't help
     * (the synthetic record would just live at the head forever).
     */
    suspend fun stallFor(retryAfterMs: Long) = mutex.withLock {
        if (retryAfterMs <= 0) return@withLock
        val clamp = retryAfterMs.coerceAtMost(windowMs)
        val budget = (tpmLimit * bufferRatio).toLong().coerceAtLeast(1L)
        val now = clock.now().toEpochMilliseconds()
        // Replace the deque with a single full-budget synthetic record positioned so it
        // evicts exactly `clamp` ms from now. Clearing any prior records is safe here:
        // a 429 from the provider means the server-side window is already full, so any
        // local accounting smaller than the synthetic reservation was stale anyway.
        records.clear()
        records.addLast(Record(tsMs = now - (windowMs - clamp), tokens = budget))
    }

    /**
     * Hold the caller until the window has room for [estimateTokens], then reserve the slot.
     * Returns the [Record] handle the caller must pass back to [settle] once real usage is
     * known; settle replaces the estimate with the actual token count.
     *
     * Immediate if there's headroom. Otherwise sleeps until the oldest record's eviction,
     * retries, and repeats. An estimate bigger than the full budget is clamped to budget
     * (never wait forever) — the provider will 429 in that case, which is the correct
     * outcome.
     */
    suspend fun acquire(estimateTokens: Long): Reservation {
        val budget = (tpmLimit * bufferRatio).toLong().coerceAtLeast(1L)
        val clamped = estimateTokens.coerceAtMost(budget)
        while (true) {
            val sleepMs = mutex.withLock {
                evict()
                val used = records.sumOf { it.tokens }
                if (used + clamped <= budget) {
                    val rec = Record(clock.now().toEpochMilliseconds(), clamped)
                    records.addLast(rec)
                    return Reservation(this, rec)
                }
                // Not enough room yet. Wait until the oldest record falls out of the window.
                val oldest = records.firstOrNull() ?: return@withLock 50L
                val evictAt = oldest.tsMs + windowMs
                (evictAt - clock.now().toEpochMilliseconds()).coerceIn(50L, windowMs)
            }
            delay(sleepMs)
        }
    }

    internal suspend fun replace(record: Record, actualTokens: Long) = mutex.withLock {
        // The record may already have been evicted; that's fine — we have nothing to
        // reconcile in that case, the window already moved on without us.
        if (record in records) {
            record.tokens = actualTokens
        }
        evict()
    }

    private fun evict() {
        val cutoff = clock.now().toEpochMilliseconds() - windowMs
        while (records.isNotEmpty() && records.first().tsMs < cutoff) {
            records.removeFirst()
        }
    }

    /**
     * Handle returned by [acquire]. Call [settle] exactly once when the provider reports
     * real token usage; if the request fails without reported usage, call [settle] with
     * `0` (or drop the estimate) so we don't double-book the window.
     */
    class Reservation internal constructor(
        private val throttle: TpmThrottle,
        private val record: Record,
    ) {
        suspend fun settle(actualTokens: Long) = throttle.replace(record, actualTokens.coerceAtLeast(0))
    }
}

/**
 * Rough token-count estimate for an [LlmRequest]. Deliberately coarse — we only need
 * "close enough to avoid slamming into TPM" for [TpmThrottle] to do its job.
 *
 * Heuristic: 4 characters per token for English / code, which over-estimates CJK.
 * That bias is safer than under-estimating (better to wait a beat we didn't need
 * than to 429). Tool schemas dominate the prompt in our setup, so we count them
 * via their JSON string; messages use a flat per-message budget because chasing
 * every Part kind through the request here would couple this module to the session
 * model, which [TpmThrottle] explicitly doesn't care about.
 */
fun estimateRequestTokens(request: LlmRequest): Long {
    var chars = 0L
    request.systemPrompt?.let { chars += it.length }
    request.tools.forEach { spec ->
        chars += spec.id.length
        chars += spec.helpText.length
        chars += jsonCharCount(spec.inputSchema)
    }
    // Flat per-message estimate — each assistant/user turn is ~200-1000 chars of
    // visible text plus tool-result payloads we can't cheaply sum here. 500 chars
    // ≈ 125 tokens, which is a conservative floor.
    chars += request.messages.size * 500L
    return (chars / 4L) + 200L
}

private fun jsonCharCount(element: JsonElement): Int = element.toString().length
