package io.talevia.core.session

import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

/**
 * SQLDelight-backed [SessionStore]. Sessions/Messages/Parts are stored as JSON blobs;
 * top-level columns mirror the fields the indices need (session_id, time_created, etc.).
 *
 * Why JSON blobs instead of normalised columns: the message-part hierarchy is sealed
 * and evolves quickly during early development. Storing the canonical Kotlin model as
 * JSON keeps schema migrations cheap and matches OpenCode's `data` column approach
 * (`packages/opencode/src/session/session.sql.ts:47-76`).
 */
class SqlDelightSessionStore(
    private val db: TaleviaDb,
    private val bus: EventBus,
    private val json: Json = JsonConfig.default,
) : SessionStore {

    override suspend fun createSession(session: Session) {
        writeSession(session)
        bus.publish(BusEvent.SessionCreated(session.id))
    }

    override suspend fun updateSession(session: Session) {
        writeSession(session)
        bus.publish(BusEvent.SessionUpdated(session.id))
    }

    private fun writeSession(s: Session) {
        db.sessionsQueries.upsert(
            id = s.id.value,
            project_id = s.projectId.value,
            parent_id = s.parentId?.value,
            title = s.title,
            archived = if (s.archived) 1 else 0,
            data_ = json.encodeToString(Session.serializer(), s),
            time_created = s.createdAt.toEpochMilliseconds(),
            time_updated = s.updatedAt.toEpochMilliseconds(),
        )
    }

    override suspend fun getSession(id: SessionId): Session? =
        db.sessionsQueries.selectById(id.value).executeAsOneOrNull()
            ?.let { json.decodeFromString(Session.serializer(), it.data_) }

    override suspend fun deleteSession(id: SessionId) {
        db.sessionsQueries.delete(id.value)
        bus.publish(BusEvent.SessionDeleted(id))
    }

    override suspend fun appendMessage(message: Message) {
        db.messagesQueries.insert(
            id = message.id.value,
            session_id = message.sessionId.value,
            role = when (message) {
                is Message.User -> "user"
                is Message.Assistant -> "assistant"
            },
            data_ = json.encodeToString(Message.serializer(), message),
            time_created = message.createdAt.toEpochMilliseconds(),
        )
        bus.publish(BusEvent.MessageUpdated(message.sessionId, message.id, message))
    }

    override suspend fun updateMessage(message: Message) {
        db.messagesQueries.update(
            data_ = json.encodeToString(Message.serializer(), message),
            id = message.id.value,
        )
        bus.publish(BusEvent.MessageUpdated(message.sessionId, message.id, message))
    }

    override suspend fun getMessage(id: MessageId): Message? =
        db.messagesQueries.selectById(id.value).executeAsOneOrNull()
            ?.let { json.decodeFromString(Message.serializer(), it.data_) }

    override suspend fun listMessages(sessionId: SessionId): List<Message> =
        db.messagesQueries.selectBySession(sessionId.value).executeAsList()
            .map { json.decodeFromString(Message.serializer(), it.data_) }

    override suspend fun upsertPart(part: Part) {
        db.partsQueries.upsert(
            id = part.id.value,
            message_id = part.messageId.value,
            session_id = part.sessionId.value,
            kind = kindOf(part),
            data_ = json.encodeToString(Part.serializer(), part),
            time_created = part.createdAt.toEpochMilliseconds(),
            time_compacted = part.compactedAt?.toEpochMilliseconds(),
        )
        bus.publish(BusEvent.PartUpdated(part.sessionId, part.messageId, part.id, part))
    }

    override suspend fun markPartCompacted(id: PartId, at: Instant) {
        db.partsQueries.markCompacted(time_compacted = at.toEpochMilliseconds(), id = id.value)
    }

    override suspend fun listParts(messageId: MessageId): List<Part> =
        db.partsQueries.selectByMessage(messageId.value).executeAsList()
            .map { json.decodeFromString(Part.serializer(), it.data_) }

    override suspend fun listSessionParts(sessionId: SessionId): List<Part> =
        db.partsQueries.selectBySession(sessionId.value).executeAsList()
            .map { json.decodeFromString(Part.serializer(), it.data_) }

    override suspend fun listMessagesWithParts(sessionId: SessionId): List<MessageWithParts> {
        val messages = listMessages(sessionId)
        val partsByMessage = listSessionParts(sessionId).groupBy { it.messageId }
        return messages.map { MessageWithParts(it, partsByMessage[it.id].orEmpty()) }
    }

    override fun observeSessionParts(sessionId: SessionId): Flow<Part> =
        bus.events
            .filterIsInstance<BusEvent.PartUpdated>()
            .filter { it.sessionId == sessionId }
            .map { it.part }

    private fun kindOf(p: Part): String = when (p) {
        is Part.Text -> "text"
        is Part.Reasoning -> "reasoning"
        is Part.Tool -> "tool"
        is Part.Media -> "media"
        is Part.TimelineSnapshot -> "timeline-snapshot"
        is Part.RenderProgress -> "render-progress"
        is Part.StepStart -> "step-start"
        is Part.StepFinish -> "step-finish"
        is Part.Compaction -> "compaction"
    }
}
