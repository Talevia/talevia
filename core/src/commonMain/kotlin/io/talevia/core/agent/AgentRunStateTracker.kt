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
 * [EventBus] and upserts the session's entry. Per-session keys grow
 * monotonically with session count — evict by session deletion if that
 * becomes load-bearing; today we do not (sessions are bounded to the
 * app's active workload).
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
) {
    private val _states = MutableStateFlow<Map<SessionId, AgentRunState>>(emptyMap())

    /** Snapshot of every tracked session's most recent state. */
    val states: StateFlow<Map<SessionId, AgentRunState>> = _states.asStateFlow()

    init {
        scope.launch {
            bus.events.collect { event ->
                if (event is BusEvent.AgentRunStateChanged) {
                    _states.update { prev -> prev + (event.sessionId to event.state) }
                }
            }
        }
    }

    /**
     * Most recent [AgentRunState] published for [sessionId], or null when
     * no agent run has started (or been tracked) for this session yet.
     */
    fun currentState(sessionId: SessionId): AgentRunState? = _states.value[sessionId]

    companion object {
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
