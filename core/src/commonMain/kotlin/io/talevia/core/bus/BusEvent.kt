package io.talevia.core.bus

import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.session.Message
import io.talevia.core.session.Part

/**
 * Events the Core publishes to UI / persistence subscribers.
 *
 * Event taxonomy mirrors OpenCode (`packages/opencode/src/bus/index.ts:19-41` and
 * `session/message-v2.ts:488-496`) — UI consumes deltas for streaming updates and
 * full part snapshots once a part finalises.
 */
sealed interface BusEvent {

    sealed interface SessionEvent : BusEvent {
        val sessionId: SessionId
    }

    data class SessionCreated(override val sessionId: SessionId) : SessionEvent
    data class SessionUpdated(override val sessionId: SessionId) : SessionEvent
    data class SessionDeleted(override val sessionId: SessionId) : SessionEvent

    data class MessageUpdated(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val message: Message,
    ) : SessionEvent

    data class PartUpdated(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val partId: PartId,
        val part: Part,
    ) : SessionEvent

    /** Streaming append: `field` identifies which property is being incrementally built. */
    data class PartDelta(
        override val sessionId: SessionId,
        val messageId: MessageId,
        val partId: PartId,
        val field: String,
        val delta: String,
    ) : SessionEvent

    data class PermissionAsked(
        override val sessionId: SessionId,
        val requestId: String,
        val permission: String,
        val patterns: List<String>,
    ) : SessionEvent

    data class PermissionReplied(
        override val sessionId: SessionId,
        val requestId: String,
        val accepted: Boolean,
        val remembered: Boolean,
    ) : SessionEvent

    /** An in-flight Agent.run for this session was cancelled via [io.talevia.core.agent.Agent.cancel]. */
    data class SessionCancelled(override val sessionId: SessionId) : SessionEvent

    /**
     * Background agent runs (e.g. the server's fire-and-forget `agent.run` launch)
     * historically swallowed failures, leaving clients stuck on a 202 with no signal
     * that the run died. Publish this event instead so SSE subscribers see the
     * failure and the client can recover.
     */
    data class AgentRunFailed(
        override val sessionId: SessionId,
        val correlationId: String,
        val message: String,
    ) : SessionEvent
}
