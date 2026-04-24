package io.talevia.core.provider

import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Rolling latency picture for provider cold-starts (Starting → Ready).
 *
 * Subscribes to [BusEvent.ProviderWarmup] events and pairs each
 * `Starting` with its matching `Ready` (keyed by `providerId + sessionId`),
 * recording the elapsed milliseconds into a per-provider ring buffer.
 * Feeds `provider_query(select=warmup_stats)` with P50 / P95 / P99 snapshots.
 *
 * Why a dedicated class rather than piggybacking on [EventBusMetricsSink]:
 * the metrics sink is only wired in the server app today, whereas
 * warmup-latency visibility is useful to every container the agent runs
 * in (CLI, Desktop, Android, iOS). Keeping this aggregator independent
 * of the sink means any container can attach it without pulling in the
 * rest of the metrics infrastructure.
 *
 * ## State
 *
 * - [pending]: `(providerId, sessionId)` → `epochMs` for unmatched
 *   `Starting` events. Serial-accessed from the bus collector so a plain
 *   `MutableMap` is safe (the collector is single-consumer per coroutine).
 * - [samples]: per-provider latency ring buffer, exposed as a [StateFlow]
 *   so [snapshot] is a non-suspending read.
 *
 * ## Capacity
 *
 * Default 256 samples per provider (≈ 2 KB boxed Long per name). Oldest
 * drops first on overflow — matches the "recent behaviour" semantics
 * already used by `MetricsRegistry.observe` (HISTOGRAM_CAP_PER_NAME = 1024).
 * A smaller cap here is fine because warmup events fire once per AIGC
 * tool dispatch (not per-request), so 256 comfortably spans a day of
 * cold-starts on a single-user device.
 */
class ProviderWarmupStats(
    bus: EventBus,
    scope: CoroutineScope,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val pending: MutableMap<Pair<String, String>, Long> = mutableMapOf()

    private val _samples = MutableStateFlow<Map<String, List<Long>>>(emptyMap())

    /** Read-only snapshot of per-provider latency samples. Non-suspending. */
    val samples: StateFlow<Map<String, List<Long>>> = _samples.asStateFlow()

    init {
        scope.launch {
            bus.events.filterIsInstance<BusEvent.ProviderWarmup>().collect { event ->
                val key = event.providerId to event.sessionId.value
                when (event.phase) {
                    BusEvent.ProviderWarmup.Phase.Starting -> {
                        pending[key] = event.epochMs
                    }
                    BusEvent.ProviderWarmup.Phase.Ready -> {
                        val start = pending.remove(key) ?: return@collect
                        val elapsed = (event.epochMs - start).coerceAtLeast(0)
                        _samples.update { prev ->
                            val existing = prev[event.providerId].orEmpty()
                            val next = if (existing.size >= capacity) {
                                existing.drop(existing.size - capacity + 1) + elapsed
                            } else {
                                existing + elapsed
                            }
                            prev + (event.providerId to next)
                        }
                    }
                }
            }
        }
    }

    /**
     * Point-in-time latency picture per provider. Empty map before any
     * matched pair arrives. P50/P95/P99 computed by sorting the current
     * ring buffer in-place each call — cheap enough at `capacity ≤ 256`
     * that we don't pre-compute.
     */
    fun snapshot(): Map<String, ProviderWarmupSnapshot> {
        val current = _samples.value
        if (current.isEmpty()) return emptyMap()
        return current.mapValues { (_, obs) ->
            val sorted = obs.sorted()
            ProviderWarmupSnapshot(
                count = sorted.size.toLong(),
                p50Ms = sorted.percentile(50),
                p95Ms = sorted.percentile(95),
                p99Ms = sorted.percentile(99),
                minMs = sorted.first(),
                maxMs = sorted.last(),
                latestMs = obs.last(),
            )
        }
    }

    private fun List<Long>.percentile(pct: Int): Long {
        if (isEmpty()) return 0L
        val idx = ((pct / 100.0) * (size - 1)).toInt().coerceIn(0, size - 1)
        return this[idx]
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 256

        /**
         * Convenience for composition roots that don't carry a dedicated
         * [CoroutineScope]. Mirrors
         * `AgentRunStateTracker.Companion.withSupervisor` so iOS / other
         * non-JVM callers can skip the scope-construction dance across the
         * language boundary.
         */
        fun withSupervisor(bus: EventBus): ProviderWarmupStats =
            ProviderWarmupStats(bus, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}

/**
 * Per-provider warmup-latency summary exposed via
 * `provider_query(select=warmup_stats)`. All values in milliseconds; 0 on
 * empty windows (shouldn't happen — snapshot filters those out).
 */
data class ProviderWarmupSnapshot(
    val count: Long,
    val p50Ms: Long,
    val p95Ms: Long,
    val p99Ms: Long,
    val minMs: Long,
    val maxMs: Long,
    val latestMs: Long,
)
