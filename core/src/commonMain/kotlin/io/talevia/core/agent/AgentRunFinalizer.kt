package io.talevia.core.agent

import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.session.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Side-effect bundles for [Agent.run]'s terminal catch chain.
 *
 * Both finalisers run in the cancellation / error path of `Agent.run`
 * — the place where bus emissions must complete even if the run is
 * being torn down. The `cancelled` flow wraps in [NonCancellable] so
 * the bus publishes survive the cancel that triggered them; the
 * `failed` flow stays cancellable because the throwable will already
 * have stopped the run, so we just need the state-change event before
 * re-throwing.
 *
 * Extracted from [Agent] (see `debt-split-agent-kt` commit body) so
 * the run() body stays focused on the try-block control flow; each
 * branch's bus / store fan-out lives next door.
 */

/**
 * Cancel-path finaliser. Stamps the in-flight assistant message + tool
 * parts as [io.talevia.core.session.FinishReason.CANCELLED] and
 * publishes [BusEvent.SessionCancelled] + a terminal
 * [BusEvent.AgentRunStateChanged] (Cancelled) so subscribers see the
 * run's exit cleanly.
 *
 * Wraps in [NonCancellable] so the publishes complete even though the
 * outer coroutine is being cancelled. The caller still rethrows the
 * [CancellationException] so cooperative cancellation propagates up.
 */
internal suspend fun finalizeCancelledRun(
    store: SessionStore,
    bus: EventBus,
    sessionId: SessionId,
    handle: AgentRunHandle,
    cause: CancellationException,
) {
    withContext(NonCancellable) {
        finalizeCancelled(store, handle.currentAssistantId, cause.message)
        bus.publish(BusEvent.SessionCancelled(sessionId))
        bus.publish(
            BusEvent.AgentRunStateChanged(
                sessionId,
                AgentRunState.Cancelled,
                retryAttempt = handle.lastRetryAttempt,
            ),
        )
    }
}

/**
 * Error-path finaliser. Publishes a terminal
 * [BusEvent.AgentRunStateChanged] with
 * [AgentRunState.Failed], carrying the throwable's message (or class
 * name if the message is null — happens for `error("…")` etc.).
 *
 * The throwable itself is rethrown by the caller after this runs;
 * this function only handles the bus-side tear-down so the catch
 * block in [Agent.run] reads as a single line per branch.
 */
internal suspend fun finalizeFailedRun(
    bus: EventBus,
    sessionId: SessionId,
    handle: AgentRunHandle,
    cause: Throwable,
) {
    bus.publish(
        BusEvent.AgentRunStateChanged(
            sessionId,
            AgentRunState.Failed(cause.message ?: cause::class.simpleName ?: "unknown"),
            retryAttempt = handle.lastRetryAttempt,
        ),
    )
}
