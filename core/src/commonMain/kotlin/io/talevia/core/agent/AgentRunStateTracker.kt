package io.talevia.core.agent

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Snapshot view of the most recent [AgentRunState] per session, so
 * subscribers that attach *after* a state transition (UI cold-boot,
 * new process attaches via SSE, a tool dispatched mid-run querying
 * "what's going on?") can ask for the current state instead of
 * relying on having tailed every [BusEvent.AgentRunStateChanged] since
 * the beginning.
 *
 * Reads are non-suspending: [currentState] hits a [MutableStateFlow]'s
 * atomic `.value`. Writes are driven by a background collector spawned
 * in `init` that tails every [AgentRunState] publish off the given
 * [EventBus] and upserts the session's entry. A second collector on
 * the same bus tails [BusEvent.SessionDeleted] and evicts the deleted
 * session's entry from both maps so a long-lived process doesn't
 * accumulate stale state across an indefinite stream of session
 * create / delete cycles (`debt-bound-agent-run-state-tracker-evict-on-delete`,
 * audit finding B2 in
 * `docs/decisions/2026-04-23-debt-audit-unbounded-mutable-collections.md`).
 *
 * Terminal states ([AgentRunState.Cancelled], [AgentRunState.Failed],
 * [AgentRunState.Idle] at the end of a run) are kept — "what was the
 * agent doing when it stopped?" is the other half of the query need.
 *
 * Thread-safety: delegated to [MutableStateFlow.update], which is
 * already lock-free atomic.
 *
 * Decoupled from [Agent] deliberately: any future publisher of
 * [BusEvent.AgentRunStateChanged] (sub-agents, MCP clients) is picked
 * up automatically without changing the tracker.
 */
class AgentRunStateTracker(
    bus: EventBus,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    /**
     * Max transitions to keep per session in the [history] ring buffer.
     * Older entries drop out when a session exceeds this cap —
     * `session_query(select=run_statehistoryFlowInternal)` can always read up to
     * this many back. Picked to comfortably cover a long agent run's
     * Compacting / Generating / AwaitingTool loops (~50 turns × a few
     * state flips = a few hundred) while keeping the per-session
     * memory footprint bounded. Unused across AgentRunStateTracker's
     * existing API — purely controls history retention.
     */
    private val historyCap: Int = DEFAULT_HISTORY_CAP,
) {
    private val _states = MutableStateFlow<Map<SessionId, AgentRunState>>(emptyMap())

    /** Snapshot of every tracked session's most recent state. */
    val states: StateFlow<Map<SessionId, AgentRunState>> = _states.asStateFlow()

    /**
     * Per-session ring buffer of [StateTransition]s. `FIFO`: when a
     * session exceeds [historyCap] entries, the oldest drops off. Kept
     * alongside [states] so both "current" and "recent history" reads
     * are non-suspending `.value` hits. The map grows monotonically
     * with session count; same retention trade-off as `_states`.
     */
    private val historyFlowInternal = MutableStateFlow<Map<SessionId, List<StateTransition>>>(emptyMap())

    /** Read-only snapshot of every session's recent [StateTransition] ring buffer. */
    val historyFlow: StateFlow<Map<SessionId, List<StateTransition>>> = historyFlowInternal.asStateFlow()

    init {
        scope.launch {
            bus.events.collect { event ->
                when (event) {
                    is BusEvent.AgentRunStateChanged -> {
                        val now = clock.now().toEpochMilliseconds()
                        val transition = StateTransition(epochMs = now, state = event.state)
                        _states.update { prev -> prev + (event.sessionId to event.state) }
                        historyFlowInternal.update { prev ->
                            val existing = prev[event.sessionId].orEmpty()
                            val next = if (existing.size >= historyCap) {
                                existing.drop(existing.size - historyCap + 1) + transition
                            } else {
                                existing + transition
                            }
                            prev + (event.sessionId to next)
                        }
                    }
                    is BusEvent.SessionDeleted -> {
                        // Evict on session deletion so the two maps don't grow
                        // monotonically with lifetime session count. No-op when
                        // the session was never tracked (either because no run
                        // ever started, or because a previous delete already
                        // cleaned it up) — `Map.minus(key)` is no-op for absent
                        // keys, so `update` on an absent entry short-circuits
                        // to the same map and StateFlow skips a downstream
                        // emission.
                        _states.update { prev -> prev - event.sessionId }
                        historyFlowInternal.update { prev -> prev - event.sessionId }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Most recent [AgentRunState] published for [sessionId], or null when
     * no agent run has started (or been tracked) for this session yet.
     */
    fun currentState(sessionId: SessionId): AgentRunState? = _states.value[sessionId]

    /**
     * Ring-buffer history of every [AgentRunState] transition observed
     * for [sessionId], oldest first. Empty when no run has been tracked.
     * Capped at [historyCap] entries — older transitions are dropped.
     *
     * `since` filters to transitions with `epochMs >= since`. Null
     * returns the full buffer (within cap).
     */
    fun history(sessionId: SessionId, since: Long? = null): List<StateTransition> {
        val all = historyFlowInternal.value[sessionId].orEmpty()
        return if (since == null) all else all.filter { it.epochMs >= since }
    }

    companion object {
        /**
         * Default per-session history cap. Sized for long agent runs
         * (~50 turns × a handful of state flips) without letting a
         * runaway session balloon memory.
         */
        const val DEFAULT_HISTORY_CAP: Int = 256

        /**
         * Convenience for composition roots that don't already have a
         * dedicated [CoroutineScope] they want to hand out — mints an
         * independent supervisor scope so a crash in the collector doesn't
         * propagate up into the rest of the app. Kotlin/Native Swift
         * callers (iOS) use this to avoid building a [CoroutineScope]
         * across the language boundary.
         */
        fun withSupervisor(bus: EventBus): AgentRunStateTracker =
            AgentRunStateTracker(bus, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}

/**
 * One entry in [AgentRunStateTracker.history]. `epochMs` is the wall-
 * clock time when the tracker observed the transition (computed via
 * [Clock.now], not the bus event's own timestamp — bus events don't
 * carry one today). Pair with the target [state]; the "previous"
 * state of a given transition is the prior entry's `state`
 * (effectively the same as the run state published at that point).
 */
data class StateTransition(
    val epochMs: Long,
    val state: AgentRunState,
)
