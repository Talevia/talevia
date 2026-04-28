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

    // Histograms: fixed-capacity ring buffer per name. Each `observe()` appends
    // a raw observation (ms); once [HISTOGRAM_CAP_PER_NAME] entries are in
    // flight, the oldest drops to make room for the newest. `histogramSnapshot()`
    // computes P50/P95/P99 over the current window — since oldest observations
    // evict first, percentile estimates track recent behaviour rather than
    // lifetime behaviour, which is what ops actually wants (a 3-hour-old P99
    // spike is paged; a week-old one is history). The cap is deliberately one
    // cheap array per named histogram: a long-lived server process that
    // observes one latency per tool dispatch can run for days without
    // unbounded memory growth, while 1024 samples is comfortably above the
    // ~30-50 samples you need for stable high-percentile estimates.
    private val histograms = mutableMapOf<String, ArrayDeque<Long>>()

    suspend fun increment(name: String, by: Long = 1L) {
        mutex.withLock { counters[name] = (counters[name] ?: 0L) + by }
    }

    /**
     * Record a latency observation (in milliseconds) for the named histogram.
     * The name's ring buffer retains the most recent [HISTOGRAM_CAP_PER_NAME]
     * observations; overflow evicts the oldest.
     */
    suspend fun observe(name: String, ms: Long) {
        mutex.withLock {
            val buf = histograms.getOrPut(name) { ArrayDeque(HISTOGRAM_CAP_PER_NAME) }
            if (buf.size >= HISTOGRAM_CAP_PER_NAME) buf.removeFirst()
            buf.addLast(ms)
        }
    }

    suspend fun snapshot(): Map<String, Long> =
        mutex.withLock { counters.toMap() }

    /**
     * P50/P95/P99 for every histogram that has at least one observation.
     * `count` is the window size (≤ [HISTOGRAM_CAP_PER_NAME]), not the
     * lifetime observation count — there's no drift-safe way to report
     * lifetime count without also exposing the ring-buffer eviction
     * behaviour, and the scrape consumer cares about "what are recent
     * latencies?" more than "how many have you seen total?" (the matching
     * counter `area.event` already tallies lifetime counts).
     */
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

    companion object {
        /**
         * Maximum observations retained per histogram name. Chosen so a single
         * `ArrayDeque<Long>` costs ~16 KB (1024 × 16 B boxed Long) per named
         * histogram — cheap even with hundreds of named histograms — while
         * giving comfortably more samples than the ~30-50 needed for stable
         * P95/P99 estimates.
         */
        const val HISTOGRAM_CAP_PER_NAME: Int = 1024
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
                // AigcCacheProbe additionally increments a per-tool counter so
                // dashboards can answer "which tool is caching well vs
                // missing?" without re-scraping the base counter by label.
                if (event is BusEvent.AigcCacheProbe) {
                    val tag = if (event.hit) "hit" else "miss"
                    registry.increment("aigc.cache.${event.toolId}.$tag.total")
                }
                // AigcCostRecorded additionally feeds a cents gauge so dashboards
                // can render "spend this session" without re-reading the lockfile.
                if (event is BusEvent.AigcCostRecorded) {
                    val cents = event.costCents
                    if (cents != null) {
                        registry.increment("aigc.cost.cents", by = cents)
                        registry.increment("aigc.cost.${event.toolId}.cents", by = cents)
                        // Per-tool call count — divider for avg-cents calculations.
                        // Without this the agent can see cumulative spend but not
                        // "what does one call cost on average?", which is what the
                        // VISION §5.2 cost tradeoff ("generate_image vs dall-e-3")
                        // needs.
                        registry.increment("aigc.cost.${event.toolId}.count")
                    } else {
                        registry.increment("aigc.cost.unknown")
                    }
                }
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
        is BusEvent.SessionFull -> "session.full"
        is BusEvent.MessageUpdated -> "message.updated"
        is BusEvent.MessageDeleted -> "message.deleted"
        is BusEvent.SessionReverted -> "session.reverted"
        is BusEvent.PartUpdated -> "part.updated"
        is BusEvent.PartDelta -> "part.delta"
        is BusEvent.PermissionAsked -> "permission.asked"
        is BusEvent.PermissionReplied ->
            if (event.accepted) "permission.granted" else "permission.rejected"
        is BusEvent.AgentRunFailed -> "agent.run.failed"
        is BusEvent.SessionCancelled -> "session.cancelled"
        is BusEvent.SessionCancelRequested -> "session.cancel.requested"
        is BusEvent.AgentRetryScheduled -> "agent.retry.${retryReasonSlug(event.reason)}"
        is BusEvent.AgentProviderFallback -> "agent.provider.fallback"
        is BusEvent.SessionCompactionAuto -> "session.compaction.auto"
        is BusEvent.SessionCompacted -> "session.compacted"
        is BusEvent.AgentRunStateChanged -> "agent.run.state.${stateTag(event.state)}"
        is BusEvent.SessionProjectBindingChanged -> "session.project.binding.changed"
        is BusEvent.ProjectValidationWarning -> "project.validation.warning"
        is BusEvent.AssetsMissing -> "project.assets.missing"
        is BusEvent.AigcCostRecorded -> "aigc.cost.recorded"
        is BusEvent.SpendCapApproaching -> "spend.cap.approaching.${event.scope}"
        is BusEvent.AigcCacheProbe ->
            if (event.hit) "aigc.cache.hits.total" else "aigc.cache.misses.total"
        // Per-phase counters so a Prometheus scrape can alert when
        // Starting / Ready cardinalities drift (e.g. an engine emits
        // Starting but never Ready = upstream timeouts before first poll).
        is BusEvent.ProviderWarmup -> when (event.phase) {
            BusEvent.ProviderWarmup.Phase.Starting -> "provider.warmup.starting"
            BusEvent.ProviderWarmup.Phase.Ready -> "provider.warmup.ready"
        }
        // Per-phase counters mirror the warmup pattern so a Prometheus
        // scrape can alert when Started / Completed cardinalities drift
        // (Started without Completed/Failed = stuck job).
        is BusEvent.AigcJobProgress -> when (event.phase) {
            BusEvent.AigcProgressPhase.Started -> "aigc.job.started"
            BusEvent.AigcProgressPhase.Progress -> "aigc.job.progress"
            BusEvent.AigcProgressPhase.Completed -> "aigc.job.completed"
            BusEvent.AigcProgressPhase.Failed -> "aigc.job.failed"
        }
        is BusEvent.ToolSpecBudgetWarning -> "tool.spec.budget.warning"
    }

    /**
     * Classify `BusEvent.AgentRetryScheduled.reason` (free-form, produced by
     * [io.talevia.core.agent.RetryClassifier]) into one of a small fixed set
     * of slugs so the counter has bounded cardinality. Every retry still
     * increments **exactly one** `agent.retry.<slug>` counter. Unknown /
     * arbitrary messages (e.g. provider-formatted HTTP errors that didn't
     * match a known pattern) fall into `other` — the Prometheus scrape can
     * still aggregate "all retries" by summing the `agent.retry.*` family.
     *
     * Intentionally written as a cascade so the most specific match wins:
     * an HTTP 503 with "overloaded" in the body is classified `http_5xx`
     * (transport category) over `overload` (semantic category) because the
     * transport signal is the actionable one for ops.
     */
    internal fun retryReasonSlug(reason: String): String {
        val lower = reason.lowercase()
        // HTTP status codes embedded in the provider-formatted message.
        val httpStatus = Regex("""http\s+(\d{3})""").find(lower)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (httpStatus != null) {
            return when {
                httpStatus == 429 -> "http_429"
                httpStatus in 500..599 -> "http_5xx"
                else -> "http_${httpStatus}"
            }
        }
        return when {
            "overload" in lower -> "overload"
            "rate limit" in lower || "rate_limit" in lower || "too many" in lower -> "rate_limit"
            "quota" in lower || "exhausted" in lower -> "quota_exhausted"
            "unavailable" in lower -> "unavailable"
            else -> "other"
        }
    }

    private fun stateTag(state: io.talevia.core.agent.AgentRunState): String = when (state) {
        is io.talevia.core.agent.AgentRunState.Idle -> "idle"
        is io.talevia.core.agent.AgentRunState.Generating -> "generating"
        is io.talevia.core.agent.AgentRunState.AwaitingTool -> "awaiting_tool"
        is io.talevia.core.agent.AgentRunState.Compacting -> "compacting"
        is io.talevia.core.agent.AgentRunState.Cancelled -> "cancelled"
        is io.talevia.core.agent.AgentRunState.Failed -> "failed"
    }
}
