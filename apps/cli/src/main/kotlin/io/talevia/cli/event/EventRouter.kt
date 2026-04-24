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
            // Producer-variant of sessionScopedSubscribe: [activeSessionId]
            // is re-invoked per event so `/resume` / `/new` swaps take effect
            // without tearing down this subscription. Outer `.filter` still
            // needed for the `field == "text"` narrowing — the typed helper
            // only does session + event-type filtering.
            bus.sessionScopedSubscribe<BusEvent.PartDelta>(activeSessionId)
                .filter { it.field == "text" }
                .collect { ev -> renderer.streamAssistantDelta(ev.partId, ev.delta) }
        }
        jobs += scope.launch {
            bus.sessionScopedSubscribe<BusEvent.AgentRetryScheduled>(activeSessionId)
                .collect { ev -> renderer.retryNotice(ev.attempt, ev.waitMs, ev.reason) }
        }
        jobs += scope.launch {
            bus.sessionScopedSubscribe<BusEvent.SessionCompacted>(activeSessionId)
                .collect { ev -> renderer.compactedNotice(ev.prunedCount, ev.summaryLength) }
        }
        jobs += scope.launch {
            // AssetsMissing is a project-scope event, not session-scope — every
            // open in this CLI run surfaces its warning regardless of which
            // session is active. It is NOT a BusEvent.SessionEvent, so it
            // can't go through `sessionScopedSubscribe` (that's the point of
            // the typed helper's `E : SessionEvent` bound). Fires once per
            // openAt that detects dangling File sources; downstream
            // `relink_asset` calls don't re-fire it (by design in
            // FileProjectStore), so the operator sees exactly one warning per
            // stale load.
            bus.subscribe<BusEvent.AssetsMissing>()
                .collect { ev ->
                    renderer.assetsMissingNotice(ev.missing.map { it.originalPath })
                }
        }
        jobs += scope.launch {
            bus.sessionScopedSubscribe<BusEvent.PartUpdated>(activeSessionId)
                .collect { ev ->
                    when (val p = ev.part) {
                        is Part.Text -> {
                            // Only render parts that belong to assistant messages — user
                            // messages have Part.Text too (the prompt) and rendering them
                            // echoes the user's input back into the transcript. Use the
                            // finalize path: Agent fires PartUpdated on TextEnd with the
                            // canonical full text, which is exactly when we want to
                            // repaint the streamed deltas with rendered markdown.
                            //
                            // Skip the empty-text PartUpdated fired on TextStart —
                            // otherwise the renderer marks the part finalised before any
                            // delta streams, the race with the PartDelta subscriber silently
                            // drops most or all of the response, and the user sees a
                            // truncated (or blank) assistant message with a non-zero
                            // out= token count.
                            if (p.text.isEmpty()) return@collect
                            val parent = runCatching { sessions.getMessage(ev.messageId) }.getOrNull()
                            if (parent is Message.Assistant) {
                                renderer.finalizeAssistantText(p.id, p.text)
                            }
                        }
                        is Part.Tool -> when (val s = p.state) {
                            is ToolState.Running -> renderer.toolRunning(p.id, p.toolId)
                            is ToolState.Completed -> renderer.toolCompleted(p.id, p.toolId, s.outputForLlm)
                            is ToolState.Failed -> renderer.toolFailed(p.id, p.toolId, s.message)
                            ToolState.Pending -> Unit
                        }
                        is Part.RenderProgress -> renderer.renderProgress(
                            jobId = p.jobId,
                            ratio = p.ratio,
                            message = p.message,
                            thumbnailPath = p.thumbnailPath,
                        )
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
