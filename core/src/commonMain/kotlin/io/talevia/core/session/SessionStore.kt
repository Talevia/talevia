package io.talevia.core.session

import io.talevia.core.MessageId
import io.talevia.core.PartId
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

    suspend fun appendMessage(message: Message)
    suspend fun updateMessage(message: Message)
    suspend fun getMessage(id: MessageId): Message?
    suspend fun listMessages(sessionId: SessionId): List<Message>

    suspend fun upsertPart(part: Part)
    suspend fun markPartCompacted(id: PartId, at: kotlinx.datetime.Instant)
    suspend fun listParts(messageId: MessageId): List<Part>
    suspend fun listSessionParts(sessionId: SessionId): List<Part>

    /** Hydrated view: messages + their parts in chronological order. */
    suspend fun listMessagesWithParts(sessionId: SessionId): List<MessageWithParts>

    /** Live stream of part-level changes for a single session. */
    fun observeSessionParts(sessionId: SessionId): Flow<Part>
}
