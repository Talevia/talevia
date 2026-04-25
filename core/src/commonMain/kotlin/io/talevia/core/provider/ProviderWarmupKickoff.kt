package io.talevia.core.provider

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.logging.Loggers
import io.talevia.core.logging.warn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Synthetic session id used by container-init eager warmup. Real
 * sessions never collide with this since session ids are UUIDs;
 * `__container_init__` is reserved here so [ProviderWarmupStats]
 * pairs the eager Starting/Ready events into one bucket separate
 * from any real session's first call.
 */
val EAGER_WARMUP_SESSION_ID: SessionId = SessionId("__container_init__")

/**
 * Pre-warm every configured LLM provider at container init so the
 * first real AIGC dispatch doesn't pay TLS + auth + model-handshake
 * cold-start latency. Each provider gets one async kickoff:
 *
 * 1. Publish `BusEvent.ProviderWarmup(Phase.Starting, providerId, …)`.
 * 2. Call `provider.listModels()` — the cheapest endpoint every
 *    provider exposes (returns the catalog, no model selection,
 *    no streaming).
 * 3. Publish `BusEvent.ProviderWarmup(Phase.Ready, providerId, …)`.
 *
 * Failures (network down, 401 auth) are logged and swallowed —
 * eager warmup is best-effort. The first real call will retry the
 * same operation through the agent loop's normal retry path. We do
 * NOT publish a Failed phase because [BusEvent.ProviderWarmup.Phase]
 * is a 2-value enum and the existing CLI / Desktop UX is built
 * around the Starting/Ready pair; a third state would be a
 * surface-area expansion for a guarded happy-path optimisation.
 *
 * Uses [EAGER_WARMUP_SESSION_ID] so [ProviderWarmupStats]'s
 * `(providerId, sessionId)` pairing keeps the eager latency
 * measurement separate from real sessions' first-call latency.
 *
 * Caller owns [scope]'s lifecycle; cancelling the scope cancels
 * any in-flight warmup. Container `init {}` blocks already create
 * a `SupervisorJob + Dispatchers.Default` scope for the bus
 * recorders — reuse it here.
 */
/**
 * Convenience that wraps [kickoffEagerProviderWarmup] in a fresh
 * `SupervisorJob + Dispatchers.Default` scope. Mirrors
 * [io.talevia.core.provider.ProviderWarmupStats.Companion.withSupervisor]
 * — handy from iOS where each call site otherwise re-constructs the
 * same scope.
 */
fun kickoffEagerProviderWarmupWithSupervisor(
    providers: ProviderRegistry,
    bus: EventBus,
    clock: Clock = Clock.System,
) = kickoffEagerProviderWarmup(
    providers = providers,
    bus = bus,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    clock = clock,
)

fun kickoffEagerProviderWarmup(
    providers: ProviderRegistry,
    bus: EventBus,
    scope: CoroutineScope,
    clock: Clock = Clock.System,
) {
    for (provider in providers.all()) {
        scope.launch {
            try {
                bus.publish(
                    BusEvent.ProviderWarmup(
                        sessionId = EAGER_WARMUP_SESSION_ID,
                        providerId = provider.id,
                        phase = BusEvent.ProviderWarmup.Phase.Starting,
                        epochMs = clock.now().toEpochMilliseconds(),
                    ),
                )
                provider.listModels()
                bus.publish(
                    BusEvent.ProviderWarmup(
                        sessionId = EAGER_WARMUP_SESSION_ID,
                        providerId = provider.id,
                        phase = BusEvent.ProviderWarmup.Phase.Ready,
                        epochMs = clock.now().toEpochMilliseconds(),
                    ),
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Loggers.get("provider.warmup").warn(
                    "eager-warmup failed",
                    "provider" to provider.id,
                    "error" to (t.message ?: t::class.simpleName ?: "unknown"),
                )
                // Don't publish Ready on failure — let the
                // pairing in ProviderWarmupStats time out / be
                // overwritten by a real session's call. The agent
                // loop will retry through its normal path on
                // first dispatch.
            }
        }
    }
}
