package io.talevia.core.bus

import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
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

    /**
     * A message (and all of its parts) was deleted from the session — currently
     * fired by `SessionRevert.revertToMessage` after truncating later turns.
     * UI consumers should drop any cached state keyed on [messageId] (rendered
     * markdown, in-flight tool spinners, etc.) so the on-screen view matches
     * persistence.
     */
    data class MessageDeleted(
        override val sessionId: SessionId,
        val messageId: MessageId,
    ) : SessionEvent

    /**
     * A [io.talevia.core.session.SessionRevert] pass committed: messages after
     * [anchorMessageId] have been removed and, if [appliedSnapshotPartId] is
     * non-null, the project's timeline was rolled back to that snapshot.
     * Emitted once per revert, after the individual [MessageDeleted] events —
     * UI can treat it as a signal to refresh the whole turn list.
     */
    data class SessionReverted(
        override val sessionId: SessionId,
        val projectId: ProjectId,
        val anchorMessageId: MessageId,
        val deletedMessages: Int,
        val appliedSnapshotPartId: PartId?,
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
