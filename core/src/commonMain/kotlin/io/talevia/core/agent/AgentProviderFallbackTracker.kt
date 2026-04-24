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
 * Sibling of [AgentRunStateTracker] for provider-fallback events.
 * [io.talevia.core.bus.BusEvent.AgentProviderFallback] is bus-only by default;
 * late subscribers (UI cold-boot, `session_query(select=run_failure)`
 * post-mortem readers) need a snapshot view of "what fallback chain
 * happened on this session" without having tailed the stream.
 *
 * Per-session ring buffer of [FallbackHop]s keyed by [SessionId]. Capped
 * per session to [historyCap]; the map also evicts on
 * [BusEvent.SessionDeleted] so a long-lived process doesn't accumulate
 * entries across an indefinite stream of session create / delete cycles
 * (same memory-bounding audit that `AgentRunStateTracker` documents in
 * `docs/decisions/2026-04-23-debt-audit-unbounded-mutable-collections.md`).
 *
 * Reads are non-suspending: [hops] hits a [MutableStateFlow]'s atomic
 * `.value`. The background collector tails every [EventBus] event and
 * upserts on [BusEvent.AgentProviderFallback]. Clock is injected for
 * tests; defaults to [Clock.System].
 *
 * Decoupled from [Agent] deliberately: any future publisher of
 * `AgentProviderFallback` (sub-agents, MCP clients, proxy loggers) is
 * picked up automatically.
 */
class AgentProviderFallbackTracker(
    bus: EventBus,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    /**
     * Max hops to retain per session. A chain traversing every configured
     * provider is at most `n_providers - 1` hops; 32 is wildly above any
     * realistic value and still bounds memory in pathological misconfig
     * scenarios (a loop that accidentally re-hops the same pair).
     */
    private val historyCap: Int = DEFAULT_HISTORY_CAP,
) {
    private val _hops = MutableStateFlow<Map<SessionId, List<FallbackHop>>>(emptyMap())

    /** Read-only snapshot of every session's recent fallback hops. */
    val hops: StateFlow<Map<SessionId, List<FallbackHop>>> = _hops.asStateFlow()

    init {
        scope.launch {
            bus.events.collect { event ->
                when (event) {
                    is BusEvent.AgentProviderFallback -> {
                        val hop = FallbackHop(
                            fromProviderId = event.fromProviderId,
                            toProviderId = event.toProviderId,
                            reason = event.reason,
                            epochMs = clock.now().toEpochMilliseconds(),
                        )
                        _hops.update { prev ->
                            val existing = prev[event.sessionId].orEmpty()
                            val next = if (existing.size >= historyCap) {
                                existing.drop(existing.size - historyCap + 1) + hop
                            } else {
                                existing + hop
                            }
                            prev + (event.sessionId to next)
                        }
                    }
                    is BusEvent.SessionDeleted -> {
                        _hops.update { prev -> prev - event.sessionId }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Ring-buffer history of fallback hops observed for [sessionId],
     * oldest first. Empty when nothing has been tracked (either no
     * fallback ever fired on this session, or the session was never
     * seen / was deleted).
     */
    fun hops(sessionId: SessionId): List<FallbackHop> = _hops.value[sessionId].orEmpty()

    companion object {
        /**
         * Default per-session cap. Picked to comfortably cover any
         * realistic fallback chain (a process wiring ~4 providers
         * would see at most 3 hops per run) with enough slack for
         * runaway-misconfig scenarios.
         */
        const val DEFAULT_HISTORY_CAP: Int = 32

        /**
         * Convenience for composition roots that don't already manage a
         * long-lived [CoroutineScope]. Mints a supervisor scope so a
         * crash in the collector doesn't propagate up. Mirrors
         * [AgentRunStateTracker.withSupervisor] for iOS / Kotlin-Native
         * Swift callers.
         */
        fun withSupervisor(bus: EventBus): AgentProviderFallbackTracker =
            AgentProviderFallbackTracker(bus, CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }
}

/**
 * One observed provider-fallback hop. Corresponds one-to-one with a
 * `BusEvent.AgentProviderFallback` publication; `epochMs` is when the
 * tracker observed it (bus events don't carry their own timestamp yet).
 */
data class FallbackHop(
    val fromProviderId: String,
    val toProviderId: String,
    val reason: String,
    val epochMs: Long,
)
