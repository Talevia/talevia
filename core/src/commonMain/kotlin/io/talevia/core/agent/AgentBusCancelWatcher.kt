package io.talevia.core.agent

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

/**
 * Side-channel that lets anyone on the bus request cancellation of an
 * in-flight session without holding an [Agent] reference. The CLI signal
 * handler, HTTP handler, and IDE abort button all publish
 * [BusEvent.SessionCancelRequested]; this watcher subscribes once at
 * construction and routes each event back to [Agent.cancel].
 *
 * Extracted from `Agent.kt` so the agent class itself stays focused on the
 * session loop. The watcher is otherwise invisible to callers.
 *
 * **Idle-session semantics.** When [cancelSession] returns `false`
 * (no in-flight run), the event is silently dropped — same shape as a
 * direct `Agent.cancel` against an idle session. `runCatching` around the
 * call swallows throwables so a cancel during shutdown never propagates
 * out of the background scope.
 *
 * **Race with publish-before-subscribe.** If a test publishes a cancel
 * event before the subscription is live, the event is dropped (SharedFlow
 * semantics). [awaitReady] exists for tests that need to synchronise the
 * publish against the subscription; production callers can ignore it.
 */
internal class AgentBusCancelWatcher(
    bus: EventBus,
    scope: CoroutineScope,
    cancelSession: suspend (SessionId) -> Unit,
) {
    private val ready = CompletableDeferred<Unit>()

    init {
        // Subscribe once at construction. `onSubscription` is only defined
        // on SharedFlow (which `EventBus.events` exposes), so it must
        // precede the `filterIsInstance` that downgrades the chain to a
        // plain Flow.
        scope.launch {
            bus.events
                .onSubscription { ready.complete(Unit) }
                .filterIsInstance<BusEvent.SessionCancelRequested>()
                .collect { ev ->
                    runCatching { cancelSession(ev.sessionId) }
                }
        }
    }

    /** Suspends until the subscription is actively collecting. Test hook. */
    suspend fun awaitReady() {
        ready.await()
    }
}
