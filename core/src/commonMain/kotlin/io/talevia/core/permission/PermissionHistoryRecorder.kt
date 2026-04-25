package io.talevia.core.permission

import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
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

/**
 * Per-session ledger of permission decisions — pairs
 * [BusEvent.PermissionAsked] with its matching
 * [BusEvent.PermissionReplied] (keyed by `requestId`) and stores the
 * resolved entries in a ring buffer per session.
 *
 * Mirror of [io.talevia.core.provider.ProviderWarmupStats] for the
 * permission lane: same bus-aggregator + per-key ring + supervised-
 * scope companion factory pattern. Feeds
 * `session_query(select=permission_history)`.
 *
 * **Why a recorder rather than persisting on the session store**:
 * permissions are bus-only today (see [DefaultPermissionService]).
 * Folding them into the SQL store would mean a schema change + a
 * migration + Part-tier persistence work; for the agent's use case
 * ("did this session reject `network.fetch:*` already?") an in-
 * process aggregator is enough — the question is always scoped to
 * the running process. Cross-restart history is intentionally out of
 * scope.
 *
 * **State**:
 * - [pending]: `requestId -> Entry` for unmatched asks. Single-collector
 *   so a plain MutableMap is safe.
 * - [_records]: `sessionId -> List<Entry>` ring buffer; oldest drops
 *   first on overflow.
 *
 * **Capacity**: 256 entries per session by default — the same scale
 * [io.talevia.core.provider.ProviderWarmupStats] uses. A noisy session
 * with hundreds of permission asks would lose the oldest decisions
 * first, which matches the "remember what the user just said no to"
 * intent over "perfect long-running audit".
 */
class PermissionHistoryRecorder(
    bus: EventBus,
    scope: CoroutineScope,
    private val capacityPerSession: Int = DEFAULT_CAPACITY_PER_SESSION,
    private val clock: Clock = Clock.System,
) {

    /**
     * One permission round-trip captured for `permission_history`.
     *
     * `accepted` / `remembered` / `repliedEpochMs` stay null until the
     * matching `PermissionReplied` lands; an in-flight ask shows up
     * here too (e.g. operator hasn't answered yet) so the agent can
     * see "I'm waiting on permission for X".
     */
    data class Entry(
        val sessionId: String,
        val requestId: String,
        val permission: String,
        val patterns: List<String>,
        val askedEpochMs: Long,
        val accepted: Boolean? = null,
        val remembered: Boolean? = null,
        val repliedEpochMs: Long? = null,
    )

    private val pending: MutableMap<String, Entry> = mutableMapOf()

    private val _records = MutableStateFlow<Map<String, List<Entry>>>(emptyMap())
    val records: StateFlow<Map<String, List<Entry>>> = _records.asStateFlow()

    private val ready = CompletableDeferred<Unit>()

    init {
        scope.launch {
            bus.events
                .onSubscription { ready.complete(Unit) }
                .collect { event ->
                    when (event) {
                        is BusEvent.PermissionAsked -> onAsked(event)
                        is BusEvent.PermissionReplied -> onReplied(event)
                        else -> Unit
                    }
                }
        }
    }

    /**
     * Suspends until the bus collector is actively subscribed. Test
     * hook — production callers don't need this because asks land on
     * the bus monotonically and any pre-subscription publish would
     * already have been lost (matching pre-recorder behaviour).
     */
    suspend fun awaitReady() {
        ready.await()
    }

    private fun onAsked(event: BusEvent.PermissionAsked) {
        val entry = Entry(
            sessionId = event.sessionId.value,
            requestId = event.requestId,
            permission = event.permission,
            patterns = event.patterns,
            askedEpochMs = clock.now().toEpochMilliseconds(),
        )
        pending[event.requestId] = entry
        appendToSession(entry)
    }

    private fun onReplied(event: BusEvent.PermissionReplied) {
        val pendingEntry = pending.remove(event.requestId) ?: return
        val resolved = pendingEntry.copy(
            accepted = event.accepted,
            remembered = event.remembered,
            repliedEpochMs = clock.now().toEpochMilliseconds(),
        )
        replaceInSession(resolved)
    }

    private fun appendToSession(entry: Entry) {
        _records.update { prev ->
            val existing = prev[entry.sessionId].orEmpty()
            val next = if (existing.size >= capacityPerSession) {
                existing.drop(existing.size - capacityPerSession + 1) + entry
            } else {
                existing + entry
            }
            prev + (entry.sessionId to next)
        }
    }

    private fun replaceInSession(updated: Entry) {
        _records.update { prev ->
            val existing = prev[updated.sessionId].orEmpty()
            val replaced = existing.map { if (it.requestId == updated.requestId) updated else it }
            prev + (updated.sessionId to replaced)
        }
    }

    /**
     * Point-in-time history for one session, oldest-first. Empty when
     * the session has had no permission asks since the recorder was
     * attached. Each entry carries `accepted=null` while still pending.
     */
    fun snapshot(sessionId: String): List<Entry> = _records.value[sessionId].orEmpty()

    companion object {
        const val DEFAULT_CAPACITY_PER_SESSION: Int = 256

        /**
         * Convenience for composition roots that don't carry a dedicated
         * [CoroutineScope]. Mirrors
         * [io.talevia.core.provider.ProviderWarmupStats.Companion.withSupervisor]
         * so iOS / other non-JVM callers can skip the scope-construction
         * dance across the language boundary.
         */
        fun withSupervisor(bus: EventBus): PermissionHistoryRecorder =
            PermissionHistoryRecorder(bus, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}
