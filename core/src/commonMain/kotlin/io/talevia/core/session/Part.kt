package io.talevia.core.session

import io.talevia.core.AssetId
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.domain.Timeline
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
sealed class Part {
    abstract val id: PartId
    abstract val messageId: MessageId
    abstract val sessionId: SessionId
    abstract val createdAt: Instant
    abstract val compactedAt: Instant?

    @Serializable @SerialName("text")
    data class Text(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val text: String,
        val synthetic: Boolean = false,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("reasoning")
    data class Reasoning(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val text: String,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("tool")
    data class Tool(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val callId: CallId,
        val toolId: String,
        val state: ToolState,
        val title: String? = null,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("media")
    data class Media(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val assetId: AssetId,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("timeline-snapshot")
    data class TimelineSnapshot(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val timeline: Timeline,
        // null = "before tool", non-null = "after tool callId"
        val producedByCallId: CallId? = null,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("render-progress")
    data class RenderProgress(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val jobId: String,
        val ratio: Float,
        val message: String? = null,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("step-start")
    data class StepStart(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("step-finish")
    data class StepFinish(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val tokens: TokenUsage,
        val finish: FinishReason,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    @Serializable @SerialName("compaction")
    data class Compaction(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val replacedFromMessageId: MessageId,
        val replacedToMessageId: MessageId,
        val summary: String,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()

    /**
     * Agent scratchpad for multi-step work. Emitted by the `todowrite` tool; each
     * call supersedes the previous list — the most recent `Part.Todos` in the
     * session is the current plan. OpenCode stores the same shape in its
     * `TodoTable` (`packages/opencode/src/session/todo.ts`); we ride the existing
     * Parts JSON-blob schema instead of minting a new table.
     */
    @Serializable @SerialName("todos")
    data class Todos(
        override val id: PartId,
        override val messageId: MessageId,
        override val sessionId: SessionId,
        override val createdAt: Instant,
        override val compactedAt: Instant? = null,
        val todos: List<TodoInfo>,
        /** Forward-compat discriminator. See [PartSchema]. */
        val schemaVersion: Int = PartSchema.CURRENT,
    ) : Part()
}

@Serializable
data class TodoInfo(
    val content: String,
    val status: TodoStatus = TodoStatus.PENDING,
    val priority: TodoPriority = TodoPriority.MEDIUM,
)

@Serializable
enum class TodoStatus {
    @SerialName("pending") PENDING,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
enum class TodoPriority {
    @SerialName("high") HIGH,
    @SerialName("medium") MEDIUM,
    @SerialName("low") LOW,
}

@Serializable
sealed class ToolState {
    @Serializable @SerialName("pending")
    data object Pending : ToolState()

    @Serializable @SerialName("running")
    data class Running(val input: JsonElement) : ToolState()

    @Serializable @SerialName("completed")
    data class Completed(
        val input: JsonElement,
        val outputForLlm: String,
        val data: JsonElement,
    ) : ToolState()

    @Serializable @SerialName("error")
    data class Failed(val input: JsonElement?, val message: String) : ToolState()
}
