package io.talevia.core.provider

import io.talevia.core.agent.BackoffKind
import io.talevia.core.agent.RetryClassifier
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Per-provider ledger of rate-limited retry events — pairs every
 * [BusEvent.AgentRetryScheduled] whose retriable reason classifies as
 * [BackoffKind.RATE_LIMIT] with the provider that scheduled it, and
 * keeps a ring buffer per provider so operators can answer
 * "how often am I hitting Anthropic's tier-1 cap today?".
 *
 * Same bus-aggregator pattern as [ProviderWarmupStats] /
 * [io.talevia.core.permission.PermissionHistoryRecorder] /
 * [io.talevia.core.agent.AgentProviderFallbackTracker]: subscribes on
 * construction, ring-buffers events keyed by some axis, exposes a
 * `snapshot()` for query-tool consumers.
 *
 * Filter logic re-uses the shared [RetryClassifier.kind] so the
 * recorder's view of "rate-limited" matches what [io.talevia.core
 * .agent.RetryLoop] applies to backoff shaping. Events whose kind
 * is not `RATE_LIMIT` (`SERVER`, `OTHER`) are silently dropped — they
 * belong to a different ops question (transient HTTP 5xx, generic
 * provider error).
 *
 * Events without a `providerId` (legacy emitters / test rigs that
 * publish AgentRetryScheduled with the default null) are also
 * dropped — the per-provider grouping needs a key. Production emitters
 * (RetryLoop) always carry providerId since cycle-91.
 *
 * Process-scoped only. A long-running CLI / desktop / server keeps
 * the buffer for as long as it lives; restart starts fresh. That's
 * acceptable for "did I hit a rate limit recently?" — the dashboards
 * dashboards on top of it would scrape periodically anyway.
 */
class RateLimitHistoryRecorder(
    bus: EventBus,
    scope: CoroutineScope,
    private val capacityPerProvider: Int = DEFAULT_CAPACITY_PER_PROVIDER,
    private val clock: Clock = Clock.System,
) {

    /**
     * One captured rate-limit retry. `reason` is the verbatim
     * [BusEvent.AgentRetryScheduled.reason] string so the operator
     * can see the underlying provider message ("anthropic HTTP 429:
     * tier-1 RPM exceeded") rather than just "rate limited".
     */
    data class Entry(
        val providerId: String,
        val sessionId: String,
        val attempt: Int,
        val waitMs: Long,
        val reason: String,
        val epochMs: Long,
    )

    private val _records = MutableStateFlow<Map<String, List<Entry>>>(emptyMap())
    val records: StateFlow<Map<String, List<Entry>>> = _records.asStateFlow()

    private val ready = CompletableDeferred<Unit>()

    init {
        require(capacityPerProvider > 0) {
            "capacityPerProvider must be > 0 (got $capacityPerProvider)"
        }
        scope.launch {
            bus.events
                .onSubscription { ready.complete(Unit) }
                .filterIsInstance<BusEvent.AgentRetryScheduled>()
                .collect { event ->
                    val providerId = event.providerId ?: return@collect
                    val kind = RetryClassifier.kind(event.reason, retriableHint = true)
                    if (kind != BackoffKind.RATE_LIMIT) return@collect
                    val entry = Entry(
                        providerId = providerId,
                        sessionId = event.sessionId.value,
                        attempt = event.attempt,
                        waitMs = event.waitMs,
                        reason = event.reason,
                        epochMs = clock.now().toEpochMilliseconds(),
                    )
                    _records.update { prev ->
                        val existing = prev[providerId].orEmpty()
                        val next = if (existing.size >= capacityPerProvider) {
                            existing.drop(existing.size - capacityPerProvider + 1) + entry
                        } else {
                            existing + entry
                        }
                        prev + (providerId to next)
                    }
                }
        }
    }

    /** Test hook — suspends until the bus collector is actively subscribed. */
    suspend fun awaitReady() {
        ready.await()
    }

    /**
     * Point-in-time history per provider, oldest-first within each
     * provider's bucket. Empty map before any rate-limit retry has been
     * captured.
     */
    fun snapshot(): Map<String, List<Entry>> = _records.value

    /** Convenience for the per-provider lookup the query layer uses. */
    fun snapshot(providerId: String): List<Entry> = _records.value[providerId].orEmpty()

    companion object {
        const val DEFAULT_CAPACITY_PER_PROVIDER: Int = 256

        /**
         * Convenience for composition roots that don't already manage a
         * long-lived [CoroutineScope]. Mints a supervisor scope so a
         * crash in the collector doesn't propagate up. Mirrors
         * [ProviderWarmupStats.Companion.withSupervisor] / iOS Swift
         * companion-factory pattern.
         */
        fun withSupervisor(bus: EventBus): RateLimitHistoryRecorder =
            RateLimitHistoryRecorder(bus, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
