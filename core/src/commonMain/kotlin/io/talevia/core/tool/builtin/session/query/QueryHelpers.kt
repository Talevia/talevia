package io.talevia.core.tool.builtin.session.query

import io.talevia.core.JsonConfig
import io.talevia.core.SessionId
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Package-private helpers shared by the per-select query files
 * (sessions / messages / parts / forks / ancestors / tool_calls).
 */
internal val VALID_ROLES: Set<String> = setOf("user", "assistant")
internal val VALID_PART_KINDS: Set<String> = setOf(
    "text", "reasoning", "tool", "media", "timeline-snapshot",
    "render-progress", "step-start", "step-finish", "compaction", "todos",
)
internal const val PREVIEW_CHARS = 80

internal fun <T> encodeRows(serializer: KSerializer<List<T>>, rows: List<T>): JsonArray =
    JsonConfig.default.encodeToJsonElement(serializer, rows) as JsonArray

internal suspend fun requireSession(sessions: SessionStore, sessionId: String?, select: String) =
    sessions.getSession(
        SessionId(
            sessionId
                ?: error(
                    "select='$select' requires sessionId. Call session_query(select=sessions) to discover valid ids.",
                ),
        ),
    ) ?: error(
        "Session $sessionId not found. Call session_query(select=sessions) to discover valid session ids.",
    )

internal fun Part.kindDiscriminator(): String = when (this) {
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

internal fun Part.preview(): String = when (this) {
    is Part.Text -> text.take(PREVIEW_CHARS)
    is Part.Reasoning -> text.take(PREVIEW_CHARS)
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
    is Part.Compaction ->
        "compacted ${replacedFromMessageId.value}→${replacedToMessageId.value}"
    is Part.Todos -> {
        val pending = todos.count { it.status.name == "PENDING" }
        val inProgress = todos.count { it.status.name == "IN_PROGRESS" }
        val done = todos.count { it.status.name == "COMPLETED" }
        "${todos.size} todo(s) pending=$pending in_progress=$inProgress done=$done"
    }
}
