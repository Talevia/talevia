package io.talevia.core.tool.builtin.session.query

import io.talevia.core.MessageId
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable data class MessagePartSummary(
    val id: String,
    /** Discriminator matching the `@SerialName` of the `Part` subtype. */
    val kind: String,
    val createdAtEpochMs: Long,
    /** When non-null, this part has been compacted out of the LLM context. */
    val compactedAtEpochMs: Long? = null,
    /** Terse human summary, per-kind. */
    val preview: String,
)

/**
 * `select=message` — one row per drilled message with metadata + part
 * previews. Replaces the deleted `describe_message` tool. Preview
 * strategy matches the old tool's rendering (text first 80 chars,
 * tool toolId+state, media assetId, timeline-snapshot clip count,
 * etc.).
 */
@Serializable data class MessageDetailRow(
    val messageId: String,
    val sessionId: String,
    /** `"user"` | `"assistant"`. */
    val role: String,
    val createdAtEpochMs: Long,
    val modelProviderId: String,
    val modelId: String,
    /** User-only; null on assistant rows. */
    val agent: String? = null,
    /** Assistant-only; null on user rows. */
    val parentId: String? = null,
    val tokensInput: Long? = null,
    val tokensOutput: Long? = null,
    val finish: String? = null,
    val error: String? = null,
    val partCount: Int,
    val parts: List<MessagePartSummary>,
)

/**
 * `select=message` — single-row drill-down replacing the deleted
 * `describe_message` tool. Returns message metadata + a per-part
 * summary list. The per-kind preview strategy matches the old tool
 * verbatim: text/reasoning first 80 chars, tool toolId+state, media
 * assetId, timeline-snapshot clip count, step-finish token usage,
 * compaction replaced-range, todos status counts.
 *
 * Consolidated under `session_query` per the
 * `debt-consolidate-session-describe-queries` backlog bullet.
 */
internal suspend fun runMessageDetailQuery(
    sessions: SessionStore,
    input: SessionQueryTool.Input,
): ToolResult<SessionQueryTool.Output> {
    val messageId = input.messageId
        ?: error(
            "select='${SessionQueryTool.SELECT_MESSAGE}' requires messageId. Call " +
                "session_query(select=messages, sessionId=...) to discover valid ids.",
        )
    val mid = MessageId(messageId)
    val message = sessions.getMessage(mid)
        ?: error(
            "Message ${mid.value} not found. Call session_query(select=messages) on the target session " +
                "to discover valid message ids.",
        )
    val parts = sessions.listParts(mid)
    val summaries = parts.map { it.toSummary() }

    val row = when (message) {
        is Message.User -> MessageDetailRow(
            messageId = message.id.value,
            sessionId = message.sessionId.value,
            role = "user",
            createdAtEpochMs = message.createdAt.toEpochMilliseconds(),
            modelProviderId = message.model.providerId,
            modelId = message.model.modelId,
            agent = message.agent,
            partCount = summaries.size,
            parts = summaries,
        )
        is Message.Assistant -> MessageDetailRow(
            messageId = message.id.value,
            sessionId = message.sessionId.value,
            role = "assistant",
            createdAtEpochMs = message.createdAt.toEpochMilliseconds(),
            modelProviderId = message.model.providerId,
            modelId = message.model.modelId,
            parentId = message.parentId.value,
            tokensInput = message.tokens.input,
            tokensOutput = message.tokens.output,
            finish = message.finish?.name?.lowercase(),
            error = message.error,
            partCount = summaries.size,
            parts = summaries,
        )
    }
    val rows = encodeRows(
        ListSerializer(MessageDetailRow.serializer()),
        listOf(row),
    )

    val roleNote = if (row.role == "assistant" && row.finish != null) " (${row.finish})" else ""
    val summary = "${row.role}$roleNote message ${row.messageId} on session ${row.sessionId}: " +
        "${summaries.size} part(s) — " +
        summaries.take(5).joinToString("; ") { "${it.kind}:${it.preview.take(32)}" } +
        if (summaries.size > 5) "; …" else ""
    return ToolResult(
        title = "session_query message ${row.messageId}",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_MESSAGE,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}

private fun Part.toSummary(): MessagePartSummary {
    val kind = when (this) {
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
        is Part.Plan -> "plan"
    }
    val preview = when (this) {
        is Part.Text -> text.take(80)
        is Part.Reasoning -> text.take(80)
        is Part.Tool -> {
            val st = when (state) {
                is ToolState.Pending -> "pending"
                is ToolState.Running -> "running"
                is ToolState.Completed -> "completed"
                is ToolState.Failed -> "error"
            }
            "$toolId[$st]"
        }
        is Part.Media -> assetId.value
        is Part.TimelineSnapshot -> {
            val clips = timeline.tracks.sumOf { it.clips.size }
            val source = producedByCallId?.let { " after ${it.value}" } ?: " baseline"
            "$clips clip(s)$source"
        }
        is Part.RenderProgress -> "job=$jobId ratio=${(ratio * 100).toInt()}%"
        is Part.StepStart -> "step start"
        is Part.StepFinish -> "${finish.name.lowercase()} input=${tokens.input} output=${tokens.output}"
        is Part.Compaction -> "compacted ${replacedFromMessageId.value}→${replacedToMessageId.value}"
        is Part.Todos -> {
            val pending = todos.count { it.status.name == "PENDING" }
            val inProgress = todos.count { it.status.name == "IN_PROGRESS" }
            val done = todos.count { it.status.name == "COMPLETED" }
            "${todos.size} todo(s) pending=$pending in_progress=$inProgress done=$done"
        }
        is Part.Plan -> {
            val pending = steps.count { it.status.name == "PENDING" }
            val done = steps.count { it.status.name == "COMPLETED" }
            val failed = steps.count { it.status.name == "FAILED" }
            val approval = approvalStatus.name.lowercase()
            "${steps.size} step(s) pending=$pending done=$done failed=$failed [$approval]"
        }
    }
    return MessagePartSummary(
        id = id.value,
        kind = kind,
        createdAtEpochMs = createdAt.toEpochMilliseconds(),
        compactedAtEpochMs = compactedAt?.toEpochMilliseconds(),
        preview = preview,
    )
}
