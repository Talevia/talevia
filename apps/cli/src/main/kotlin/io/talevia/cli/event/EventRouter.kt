package io.talevia.cli.event

import io.talevia.cli.repl.Renderer
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState
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
    private val sessions: SessionStore,
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
                        is Part.Text -> {
                            // Only render parts that belong to assistant messages — user
                            // messages have Part.Text too (the prompt) and rendering them
                            // echoes the user's input back into the transcript.
                            val parent = runCatching { sessions.getMessage(ev.messageId) }.getOrNull()
                            if (parent is Message.Assistant) {
                                renderer.ensureAssistantText(p.id, p.text)
                            }
                        }
                        is Part.Tool -> when (val s = p.state) {
                            is ToolState.Running -> renderer.toolRunning(p.id, p.toolId)
                            is ToolState.Completed -> renderer.toolCompleted(p.id, p.toolId, s.outputForLlm)
                            is ToolState.Failed -> renderer.toolFailed(p.id, p.toolId, s.message)
                            ToolState.Pending -> Unit
                        }
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
