package io.talevia.core.session

import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import kotlinx.coroutines.flow.Flow

/**
 * Read/write API over persisted sessions, messages and parts.
 *
 * Implementations are platform-specific (SQLDelight + JVM/Native driver).
 * Mutating methods publish corresponding [io.talevia.core.bus.BusEvent]s on success.
 */
interface SessionStore {
    suspend fun createSession(session: Session)
    suspend fun updateSession(session: Session)
    suspend fun getSession(id: SessionId): Session?
    suspend fun deleteSession(id: SessionId)

    /**
     * List non-archived sessions, newest first. Optionally filter to a single
     * project. Returning the full [Session] model is cheap because rows are small
     * and fit in memory; paginate once we actually hit a performance ceiling.
     */
    suspend fun listSessions(projectId: ProjectId? = null): List<Session>

    /**
     * Like [listSessions] but includes archived rows. Distinct query so the
     * common non-archived path stays index-covered and the archived-inclusive
     * path is explicit at the call site. Archived sessions intersperse with
     * live ones in the returned list — callers filter by `Session.archived`
     * if they need to distinguish.
     */
    suspend fun listSessionsIncludingArchived(projectId: ProjectId? = null): List<Session>

    /**
     * Return sessions whose [Session.parentId] equals [parentId] — the
     * immediate forks of the given session. Oldest-first by `createdAt`
     * (the tree is usually walked depth-first from the root, so a stable
     * chronological ordering lets the caller render parent→child flow).
     *
     * Archived children are included — a fork that was later archived is
     * still part of the lineage. Callers that want to filter can check
     * `Session.archived` on each row.
     */
    suspend fun listChildSessions(parentId: SessionId): List<Session>

    suspend fun appendMessage(message: Message)
    suspend fun updateMessage(message: Message)
    suspend fun getMessage(id: MessageId): Message?
    suspend fun listMessages(sessionId: SessionId): List<Message>

    /**
     * Delete a single message and all of its parts. Publishes
     * [io.talevia.core.bus.BusEvent.MessageDeleted] on success.
     *
     * No-op if no row matches [id]. Used by [io.talevia.core.session.SessionRevert]
     * to truncate a session back to an earlier turn.
     */
    suspend fun deleteMessage(id: MessageId)

    /**
     * Delete every message in [sessionId] whose `createdAt` is strictly after
     * [anchorMessageId]'s — i.e. truncate the session back to the anchor,
     * keeping the anchor itself. Publishes a [io.talevia.core.bus.BusEvent.MessageDeleted]
     * for each removed message and returns the count.
     *
     * Returns 0 (and is a no-op) when [anchorMessageId] is the most recent
     * message in the session, or when it doesn't exist.
     */
    suspend fun deleteMessagesAfter(sessionId: SessionId, anchorMessageId: MessageId): Int

    suspend fun upsertPart(part: Part)
    suspend fun markPartCompacted(id: PartId, at: kotlinx.datetime.Instant)
    suspend fun getPart(id: PartId): Part?
    suspend fun listParts(messageId: MessageId): List<Part>
    suspend fun listSessionParts(sessionId: SessionId, includeCompacted: Boolean = true): List<Part>

    /**
     * Hydrated view: messages + their parts in chronological order.
     *
     * When [includeCompacted] is false, parts whose `compactedAt` is non-null are
     * omitted — the Agent loop wants the post-compaction view when building the
     * next LLM request, while UI code browsing history generally wants everything.
     */
    suspend fun listMessagesWithParts(
        sessionId: SessionId,
        includeCompacted: Boolean = true,
    ): List<MessageWithParts>

    /** Live stream of part-level changes for a single session. */
    fun observeSessionParts(sessionId: SessionId): Flow<Part>

    /**
     * Branch a session: create a new session whose `parentId` points at [parentId],
     * copying messages + parts with fresh IDs so the branch can diverge without
     * touching the parent's history. Returns the new session id.
     *
     * When [anchorMessageId] is non-null, only messages at-or-before the anchor in
     * `(createdAt, id)` order are copied — everything strictly after is dropped
     * from the branch. Useful for "fork from here" flows that want a clean
     * continuation point without polluting the new session with the tangent that
     * followed the anchor in the parent. Throws if the anchor isn't in [parentId].
     */
    suspend fun fork(
        parentId: SessionId,
        newTitle: String? = null,
        anchorMessageId: MessageId? = null,
    ): SessionId
}
