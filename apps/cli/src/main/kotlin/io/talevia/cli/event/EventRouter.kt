package io.talevia.cli.event

import io.talevia.cli.repl.Renderer
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.session.Part
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Lives for the duration of the REPL. Subscribes to the [EventBus] once and
 * forwards the events we care about — filtered by the active [SessionId] — to
 * the [Renderer].
 *
 * [activeSessionId] is a producer so `/resume` and `/new` can switch the session
 * without tearing down the subscriptions.
 */
class EventRouter(
    private val bus: EventBus,
    private val renderer: Renderer,
    private val activeSessionId: () -> SessionId,
) {
    private val jobs = mutableListOf<Job>()

    fun start(scope: CoroutineScope) {
        jobs += scope.launch {
            bus.subscribe<BusEvent.PartDelta>()
                .filter { it.sessionId == activeSessionId() && it.field == "text" }
                .collect { ev -> renderer.streamAssistantDelta(ev.partId, ev.delta) }
        }
        jobs += scope.launch {
            bus.subscribe<BusEvent.PartUpdated>()
                .filter { it.sessionId == activeSessionId() }
                .collect { ev ->
                    when (val p = ev.part) {
                        is Part.Text -> renderer.ensureAssistantText(p.id, p.text)
                        else -> Unit
                    }
                }
        }
    }

    suspend fun stop() {
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
    }
}
