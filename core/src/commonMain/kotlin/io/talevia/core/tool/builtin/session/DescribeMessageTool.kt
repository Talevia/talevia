package io.talevia.core.tool.builtin.session

import io.talevia.core.MessageId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Drill-down into a single message — the describe-verb counterpart of
 * [ListMessagesTool]. Fetches the message via `SessionStore.getMessage`
 * plus its parts via `SessionStore.listParts`, and returns:
 *
 *  - Message metadata: role, timestamps, session + project, token usage
 *    / finish / error (assistant rows), parentId (assistant rows).
 *  - Per-part summary: one `PartSummary` row per Part. Each row has:
 *      - `kind` — discriminator (`"text"`, `"reasoning"`, `"tool"`,
 *        `"media"`, `"timeline-snapshot"`, `"render-progress"`,
 *        `"step-start"`, `"step-finish"`, `"compaction"`, `"todos"`),
 *      - `createdAt` + `compactedAt` — whether the part is still live
 *        in the LLM context or has been compacted,
 *      - a terse `preview` that is meaningful per kind:
 *          text / reasoning → first 80 chars,
 *          tool → toolId + state ("pending" / "running" / "completed" /
 *                 "error"),
 *          media → assetId,
 *          timeline-snapshot → clip count + producedByCallId,
 *          step-finish → finish + input/output tokens,
 *          compaction → "compacted N messages",
 *          todos → count of todos + counts by status.
 *
 * Deliberately does NOT return the full content of each part — a single
 * compaction summary or an entire timeline-snapshot would balloon the
 * output for a "what happened in this turn?" question. The agent has a
 * clear path to drill further (tool state has a `callId`, timeline-
 * snapshot has its own timeline fields, compaction has a summary readable
 * via a future part-level tool) but this tool is the orientation verb.
 *
 * Missing message id fails loudly with a `session_query(select=messages)` hint. Read-only;
 * permission `session.read`.
 */
class DescribeMessageTool(
    private val sessions: SessionStore,
) : Tool<DescribeMessageTool.Input, DescribeMessageTool.Output> {

    @Serializable data class Input(
        val messageId: String,
    )

    @Serializable data class PartSummary(
        val id: String,
        /** Discriminator matching the `@SerialName` of [Part] subtypes. */
        val kind: String,
        val createdAtEpochMs: Long,
        /** When non-null, this part has been compacted out of the LLM context. */
        val compactedAtEpochMs: Long? = null,
        /** Terse human summary, per-kind (see tool kdoc). */
        val preview: String,
    )

    @Serializable data class Output(
        val messageId: String,
        val sessionId: String,
        /** `"user"` | `"assistant"`. */
        val role: String,
        val createdAtEpochMs: Long,
        val modelProviderId: String,
        val modelId: String,
        // User-only:
        val agent: String? = null,
        // Assistant-only:
        val parentId: String? = null,
        val tokensInput: Long? = null,
        val tokensOutput: Long? = null,
        val finish: String? = null,
        val error: String? = null,
        val partCount: Int,
        val parts: List<PartSummary>,
    )

    override val id: String = "describe_message"
    override val helpText: String =
        "Describe a single message: metadata + a list of PartSummary rows per part with a terse " +
            "per-kind preview (first 80 chars for text, toolId+state for tool calls, clip count for " +
            "timeline snapshots, token usage for step-finish, etc.). Full part content is NOT " +
            "returned — this verb is for orientation within a turn. Use session_query(select=messages) first to find " +
            "the messageId."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("messageId") {
                put("type", "string")
                put("description", "Message id from session_query(select=messages).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("messageId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val mid = MessageId(input.messageId)
        val message = sessions.getMessage(mid)
            ?: error(
                "Message ${input.messageId} not found. Call session_query(select=messages) on the target session " +
                    "to discover valid message ids.",
            )
        val parts = sessions.listParts(mid)

        val summaries = parts.map { it.toSummary() }

        val out = when (message) {
            is Message.User -> Output(
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
            is Message.Assistant -> Output(
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

        val roleNote = if (out.role == "assistant" && out.finish != null) " (${out.finish})" else ""
        val summary = "${out.role}$roleNote message ${out.messageId} on session ${out.sessionId}: " +
            "${summaries.size} part(s) — " +
            summaries.take(5).joinToString("; ") { "${it.kind}:${it.preview.take(32)}" } +
            if (summaries.size > 5) "; …" else ""
        return ToolResult(
            title = "describe message ${out.messageId}",
            outputForLlm = summary,
            data = out,
        )
    }

    private fun Part.toSummary(): PartSummary {
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
        }
        val preview = when (this) {
            is Part.Text -> text.take(80)
            is Part.Reasoning -> text.take(80)
            is Part.Tool -> {
                val state = when (state) {
                    is ToolState.Pending -> "pending"
                    is ToolState.Running -> "running"
                    is ToolState.Completed -> "completed"
                    is ToolState.Failed -> "error"
                }
                "$toolId[$state]"
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
        }
        return PartSummary(
            id = id.value,
            kind = kind,
            createdAtEpochMs = createdAt.toEpochMilliseconds(),
            compactedAtEpochMs = compactedAt?.toEpochMilliseconds(),
            preview = preview,
        )
    }
}
