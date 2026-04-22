package io.talevia.core.bus

import io.talevia.core.SessionId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Typed pubsub for [BusEvent]. Backed by a [MutableSharedFlow] so subscribers added after
 * `publish()` calls do not see historical events — subscribers are expected to bootstrap
 * their state from [io.talevia.core.session.SessionStore] reads, then follow the live stream.
 *
 * Buffer size leaves room for bursty stream-delta floods without dropping events; a slow
 * consumer will suspend `publish()` rather than silently lose events.
 */
class EventBus(extraBufferCapacity: Int = 256) {
    private val flow = MutableSharedFlow<BusEvent>(extraBufferCapacity = extraBufferCapacity)

    /**
     * Hot stream of all published events. Exposed as [SharedFlow] (not a plain
     * [Flow]) so subscribers that need `onSubscription {}` — notably the
     * [io.talevia.core.agent.Agent]'s cancel-request listener, which races
     * against freshly-published events at construction time — can block until
     * their collector is registered without a cast or workaround layer.
     */
    val events: SharedFlow<BusEvent> get() = flow.asSharedFlow()

    suspend fun publish(event: BusEvent) {
        flow.emit(event)
    }

    inline fun <reified E : BusEvent> subscribe(): Flow<E> = events.filterIsInstance<E>()

    fun forSession(sessionId: SessionId): Flow<BusEvent.SessionEvent> =
        events.filterIsInstance<BusEvent.SessionEvent>().filter { it.sessionId == sessionId }
}
