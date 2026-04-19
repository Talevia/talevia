package io.talevia.core.metrics

import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory counter registry. Maps a string name to a Long. Written by the
 * metrics sink, read by whatever scrape endpoint the app decides to expose
 * (Prometheus-style text, JSON dump, etc.). Intentionally minimalist — no
 * buckets, no histograms — enough to answer "is anything failing?".
 *
 * Counter names follow `area.event` dotted style (permission.asked,
 * agent.run.failed) to keep them easy to glob in a dashboard.
 */
class MetricsRegistry {
    private val mutex = Mutex()
    private val counters = mutableMapOf<String, Long>()

    // Histograms: store all raw observations (ms) and sort lazily for percentile queries.
    private val histograms = mutableMapOf<String, MutableList<Long>>()

    suspend fun increment(name: String, by: Long = 1L) {
        mutex.withLock { counters[name] = (counters[name] ?: 0L) + by }
    }

    /** Record a latency observation (in milliseconds) for the named histogram. */
    suspend fun observe(name: String, ms: Long) {
        mutex.withLock { histograms.getOrPut(name) { mutableListOf() }.add(ms) }
    }

    suspend fun snapshot(): Map<String, Long> =
        mutex.withLock { counters.toMap() }

    /** Return P50/P95/P99 for every histogram that has at least one observation. */
    suspend fun histogramSnapshot(): Map<String, HistogramStats> = mutex.withLock {
        histograms.mapValues { (_, obs) ->
            val sorted = obs.sorted()
            HistogramStats(
                count = sorted.size.toLong(),
                p50 = sorted.percentile(50),
                p95 = sorted.percentile(95),
                p99 = sorted.percentile(99),
            )
        }
    }

    suspend fun get(name: String): Long =
        mutex.withLock { counters[name] ?: 0L }

    suspend fun reset() {
        mutex.withLock { counters.clear(); histograms.clear() }
    }

    private fun List<Long>.percentile(pct: Int): Long {
        if (isEmpty()) return 0L
        val idx = ((pct / 100.0) * (size - 1)).toInt().coerceIn(0, size - 1)
        return this[idx]
    }
}

data class HistogramStats(
    val count: Long,
    val p50: Long,
    val p95: Long,
    val p99: Long,
)

/**
 * Translate [BusEvent]s into counter increments. Listens forever on the given
 * scope; caller owns the Job and cancels it at shutdown.
 *
 * The mapping is deliberately shallow — one counter per event class. Anything
 * richer (per-session histograms, latency timers) can be added later without
 * breaking existing scrape consumers.
 */
class EventBusMetricsSink(
    private val bus: EventBus,
    private val registry: MetricsRegistry,
) {
    fun attach(scope: CoroutineScope): Job = scope.launch {
        bus.events.filterIsInstance<BusEvent>().collect { event ->
            try {
                registry.increment(counterName(event))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                // Metrics must never break the run — log and swallow.
                Loggers.get("metrics.sink").warn(
                    "failed to record bus event",
                    "event" to event::class.simpleName,
                    "error" to (t.message ?: t::class.simpleName),
                )
            }
        }
    }

    private fun counterName(event: BusEvent): String = when (event) {
        is BusEvent.SessionCreated -> "session.created"
        is BusEvent.SessionUpdated -> "session.updated"
        is BusEvent.SessionDeleted -> "session.deleted"
        is BusEvent.MessageUpdated -> "message.updated"
        is BusEvent.PartUpdated -> "part.updated"
        is BusEvent.PartDelta -> "part.delta"
        is BusEvent.PermissionAsked -> "permission.asked"
        is BusEvent.PermissionReplied ->
            if (event.accepted) "permission.granted" else "permission.rejected"
        is BusEvent.AgentRunFailed -> "agent.run.failed"
        is BusEvent.SessionCancelled -> "session.cancelled"
    }
}
