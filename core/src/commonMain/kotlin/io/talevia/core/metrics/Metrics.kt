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

    suspend fun increment(name: String, by: Long = 1L) {
        mutex.withLock { counters[name] = (counters[name] ?: 0L) + by }
    }

    suspend fun snapshot(): Map<String, Long> =
        mutex.withLock { counters.toMap() }

    suspend fun get(name: String): Long =
        mutex.withLock { counters[name] ?: 0L }

    suspend fun reset() {
        mutex.withLock { counters.clear() }
    }
}

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
