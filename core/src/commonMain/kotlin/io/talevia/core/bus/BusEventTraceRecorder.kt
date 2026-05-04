package io.talevia.core.bus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

/**
 * Per-session ring buffer of [BusEvent.SessionEvent] entries — the
 * "what happened recently in this session?" trace agents and operators
 * pull when a turn fails and the CLI log isn't accessible (Server /
 * Desktop have no equivalent of `~/.talevia/cli.log`).
 *
 * Captures every event that implements [BusEvent.SessionEvent] (the
 * vast majority — anything that pertains to a specific session). Events
 * with no session affinity (system-wide warmup, etc.) are ignored — they
 * belong on a separate process-wide trace if anyone ever needs one.
 *
 * Per-session capacity is [DEFAULT_CAPACITY_PER_SESSION] = 256. A
 * streaming session that emits PartDelta on every token will rotate the
 * buffer fast; a steady-state debug session shows the recent N events.
 * The buffer is process-scoped — it does NOT persist across restarts
 * (PermissionDecisions does, but that's a domain-significant log; bus
 * traces are debug ephemera and aren't worth the SQLite write
 * amplification).
 *
 * Sourced from `bus.events` via the standard recorder pattern (see
 * [io.talevia.core.permission.PermissionHistoryRecorder]).
 */
class BusEventTraceRecorder(
    bus: EventBus,
    private val scope: CoroutineScope,
    private val capacityPerSession: Int = DEFAULT_CAPACITY_PER_SESSION,
    private val clock: Clock = Clock.System,
) {

    /**
     * One captured event. `kind` is the event class's simple name
     * (e.g. `PartDelta`, `MessageUpdated`) — agents filter on it.
     * `summary` is `event.toString()` truncated to [SUMMARY_LIMIT] —
     * keeps each row small while preserving the discriminating fields.
     */
    @Serializable
    data class Entry(
        val sessionId: String,
        val kind: String,
        val epochMs: Long,
        val summary: String,
    )

    private val _records = MutableStateFlow<Map<String, List<Entry>>>(emptyMap())
    val records: StateFlow<Map<String, List<Entry>>> = _records.asStateFlow()

    private val ready = CompletableDeferred<Unit>()

    init {
        require(capacityPerSession > 0) {
            "capacityPerSession must be > 0 (got $capacityPerSession)"
        }
        scope.launch {
            bus.events
                .onSubscription { ready.complete(Unit) }
                .collect { event ->
                    if (event is BusEvent.SessionEvent) onEvent(event)
                }
        }
    }

    /**
     * Suspends until the bus collector is actively subscribed. Test
     * hook — production callers don't need this because trace is best-
     * effort and any pre-subscription publish would already have been
     * lost (matching the no-recorder baseline).
     */
    suspend fun awaitReady() {
        ready.await()
    }

    private fun onEvent(event: BusEvent.SessionEvent) {
        val entry = Entry(
            sessionId = event.sessionId.value,
            kind = event::class.simpleName ?: "Unknown",
            epochMs = clock.now().toEpochMilliseconds(),
            summary = event.toString().take(SUMMARY_LIMIT),
        )
        _records.update { prev ->
            val sid = entry.sessionId
            val existing = prev[sid].orEmpty()
            val updated = (existing + entry).takeLast(capacityPerSession)
            prev + (sid to updated)
        }
    }

    /**
     * Point-in-time trace for one session, oldest-first. Empty when
     * the session has had no events since the recorder attached.
     */
    fun snapshot(sessionId: String): List<Entry> = _records.value[sessionId].orEmpty()

    companion object {
        const val DEFAULT_CAPACITY_PER_SESSION: Int = 256
        /** Max chars per entry's `summary`. Long enough to keep discriminating fields, short enough that 256 entries stay under ~50KB. */
        const val SUMMARY_LIMIT: Int = 200

        /**
         * Convenience for composition roots that don't carry a dedicated
         * [CoroutineScope]. Mirrors
         * [io.talevia.core.provider.ProviderWarmupStats.Companion.withSupervisor].
         */
        fun withSupervisor(bus: EventBus): BusEventTraceRecorder =
            BusEventTraceRecorder(
                bus = bus,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
    }
}

typealias BusTraceRow = BusEventTraceRecorder.Entry
