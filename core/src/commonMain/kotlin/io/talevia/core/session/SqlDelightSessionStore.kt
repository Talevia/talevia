package io.talevia.core.session

import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    private val clock: Clock = Clock.System,
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

    override suspend fun listSessions(projectId: ProjectId?): List<Session> {
        val rows = if (projectId == null) db.sessionsQueries.selectAll().executeAsList()
        else db.sessionsQueries.selectByProject(projectId.value).executeAsList()
        return rows.map { json.decodeFromString(Session.serializer(), it.data_) }
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
        touchSession(message.sessionId, message.createdAt)
        bus.publish(BusEvent.MessageUpdated(message.sessionId, message.id, message))
    }

    override suspend fun updateMessage(message: Message) {
        db.messagesQueries.update(
            data_ = json.encodeToString(Message.serializer(), message),
            id = message.id.value,
        )
        touchSession(message.sessionId, clock.now())
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
        touchSession(part.sessionId, part.createdAt)
        bus.publish(BusEvent.PartUpdated(part.sessionId, part.messageId, part.id, part))
    }

    override suspend fun markPartCompacted(id: PartId, at: Instant) {
        db.partsQueries.markCompacted(time_compacted = at.toEpochMilliseconds(), id = id.value)
    }

    private suspend fun touchSession(sessionId: SessionId, at: Instant) {
        val row = db.sessionsQueries.selectById(sessionId.value).executeAsOneOrNull() ?: return
        val current = json.decodeFromString(Session.serializer(), row.data_)
        if (at <= current.updatedAt) return
        writeSession(current.copy(updatedAt = at))
        bus.publish(BusEvent.SessionUpdated(sessionId))
    }

    override suspend fun getPart(id: PartId): Part? =
        db.partsQueries.selectById(id.value).executeAsOneOrNull()
            ?.let { decodePart(it.data_, it.time_compacted) }

    override suspend fun listParts(messageId: MessageId): List<Part> =
        db.partsQueries.selectByMessage(messageId.value).executeAsList()
            .map { decodePart(it.data_, it.time_compacted) }

    override suspend fun listSessionParts(sessionId: SessionId, includeCompacted: Boolean): List<Part> {
        val rows = if (includeCompacted) db.partsQueries.selectBySession(sessionId.value).executeAsList()
        else db.partsQueries.selectActiveBySession(sessionId.value).executeAsList()
        return rows.map { decodePart(it.data_, it.time_compacted) }
    }

    /**
     * Reconstruct a [Part] from its JSON blob, overlaying the `time_compacted`
     * column. [markPartCompacted] only updates the column (for cheap set-once
     * writes); without this overlay, callers that read the Part back see the
     * stale `compactedAt = null` from the JSON blob.
     */
    private fun decodePart(dataJson: String, timeCompactedMs: Long?): Part {
        val decoded = json.decodeFromString(Part.serializer(), dataJson)
        if (timeCompactedMs == null || decoded.compactedAt != null) return decoded
        val at = Instant.fromEpochMilliseconds(timeCompactedMs)
        return when (decoded) {
            is Part.Text -> decoded.copy(compactedAt = at)
            is Part.Reasoning -> decoded.copy(compactedAt = at)
            is Part.Tool -> decoded.copy(compactedAt = at)
            is Part.Media -> decoded.copy(compactedAt = at)
            is Part.TimelineSnapshot -> decoded.copy(compactedAt = at)
            is Part.RenderProgress -> decoded.copy(compactedAt = at)
            is Part.StepStart -> decoded.copy(compactedAt = at)
            is Part.StepFinish -> decoded.copy(compactedAt = at)
            is Part.Compaction -> decoded.copy(compactedAt = at)
            is Part.Todos -> decoded.copy(compactedAt = at)
        }
    }

    override suspend fun listMessagesWithParts(
        sessionId: SessionId,
        includeCompacted: Boolean,
    ): List<MessageWithParts> {
        val messages = listMessages(sessionId)
        val partsByMessage = listSessionParts(sessionId, includeCompacted).groupBy { it.messageId }
        return messages.map { MessageWithParts(it, partsByMessage[it.id].orEmpty()) }
    }

    override fun observeSessionParts(sessionId: SessionId): Flow<Part> =
        bus.events
            .filterIsInstance<BusEvent.PartUpdated>()
            .filter { it.sessionId == sessionId }
            .map { it.part }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun fork(parentId: SessionId, newTitle: String?): SessionId {
        val parent = getSession(parentId) ?: error("Cannot fork unknown session $parentId")
        val now = Clock.System.now()
        val newId = SessionId(Uuid.random().toString())
        val branch = parent.copy(
            id = newId,
            parentId = parentId,
            title = newTitle ?: "${parent.title} (fork)",
            createdAt = now,
            updatedAt = now,
            archived = false,
        )
        createSession(branch)

        val parentMessages = listMessagesWithParts(parentId, includeCompacted = true)
        val messageIdRemap = mutableMapOf<MessageId, MessageId>()
        for (mwp in parentMessages) {
            val newMid = MessageId(Uuid.random().toString())
            messageIdRemap[mwp.message.id] = newMid
            val newMessage = when (val m = mwp.message) {
                is Message.User -> m.copy(id = newMid, sessionId = newId)
                is Message.Assistant -> m.copy(
                    id = newMid,
                    sessionId = newId,
                    parentId = messageIdRemap[m.parentId] ?: m.parentId,
                )
            }
            appendMessage(newMessage)
            for (part in mwp.parts) {
                upsertPart(rebindPart(part, newId, newMid))
            }
        }
        return newId
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun rebindPart(p: Part, newSession: SessionId, newMessage: MessageId): Part = when (p) {
        is Part.Text -> p.copy(id = PartId(Uuid.random().toString()), sessionId = newSession, messageId = newMessage)
        is Part.Reasoning -> p.copy(id = PartId(Uuid.random().toString()), sessionId = newSession, messageId = newMessage)
        is Part.Tool -> p.copy(id = PartId(Uuid.random().toString()), sessionId = newSession, messageId = newMessage)
        is Part.Media -> p.copy(id = PartId(Uuid.random().toString()), sessionId = newSession, messageId = newMessage)
        is Part.TimelineSnapshot -> p.copy(id = PartId(Uuid.random().toString()), sessionId = newSession, messageId = newMessage)
        is Part.RenderProgress -> p.copy(id = PartId(Uuid.random().toString()), sessionId = newSession, messageId = newMessage)
        is Part.StepStart -> p.copy(id = PartId(Uuid.random().toString()), sessionId = newSession, messageId = newMessage)
        is Part.StepFinish -> p.copy(id = PartId(Uuid.random().toString()), sessionId = newSession, messageId = newMessage)
        is Part.Compaction -> p.copy(id = PartId(Uuid.random().toString()), sessionId = newSession, messageId = newMessage)
        is Part.Todos -> p.copy(id = PartId(Uuid.random().toString()), sessionId = newSession, messageId = newMessage)
    }

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
        is Part.Todos -> "todos"
    }
}
