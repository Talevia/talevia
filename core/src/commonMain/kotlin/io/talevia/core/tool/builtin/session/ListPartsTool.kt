package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionSpec
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
 * Enumerate a session's [Part]s with an optional kind filter. Generalises
 * the [ListToolCallsTool] shape — that tool hard-codes `Part.Tool` and
 * surfaces tool-specific fields (toolId, state, callId); this tool accepts
 * any kind discriminator and returns a compact per-kind summary.
 *
 * Use this for audit flows where the kind varies:
 *  - "show me every TimelineSnapshot part" — inspect which tool calls
 *    created snapshots for undo points.
 *  - "show me every Compaction part" — audit where the Compactor has
 *    run; pair with `read_part` for the full summary text.
 *  - "show me every Todos update" — trace how the agent's scratchpad
 *    evolved across the session.
 *
 * Accepts the `@SerialName` discriminator for the kind filter — the
 * same string the wire shape uses (e.g. `"timeline-snapshot"`,
 * `"step-finish"`). Unknown kinds are rejected loudly so typos surface.
 * Omit `kind` to list every part.
 *
 * Read-only, `session.read`. Most-recent-first by createdAt. Default
 * limit 100, max 1000 — same cap as list_tool_calls.
 */
class ListPartsTool(
    private val sessions: SessionStore,
) : Tool<ListPartsTool.Input, ListPartsTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
        /**
         * Optional kind filter — the `@SerialName` discriminator from
         * [Part]: `"text"`, `"reasoning"`, `"tool"`, `"media"`,
         * `"timeline-snapshot"`, `"render-progress"`, `"step-start"`,
         * `"step-finish"`, `"compaction"`, `"todos"`.
         */
        val kind: String? = null,
        /** Include compacted parts? Default true (full audit view). */
        val includeCompacted: Boolean = true,
        /** Cap on returned rows. Default 100, max 1000. */
        val limit: Int? = null,
    )

    @Serializable data class Summary(
        val partId: String,
        val kind: String,
        val messageId: String,
        val createdAtEpochMs: Long,
        val compactedAtEpochMs: Long? = null,
        /** Per-kind terse excerpt. First 80 chars for text/reasoning, tool
         *  state summary, clip count for snapshots, finish+tokens for
         *  step-finish, etc. See the kdoc of [DescribeMessageTool] for
         *  the full preview vocabulary. */
        val preview: String,
    )

    @Serializable data class Output(
        val sessionId: String,
        val totalParts: Int,
        val returnedParts: Int,
        val parts: List<Summary>,
    )

    override val id: String = "list_parts"
    override val helpText: String =
        "List session parts filterable by kind. Kinds: text, reasoning, tool, media, " +
            "timeline-snapshot, render-progress, step-start, step-finish, compaction, todos. " +
            "Each row carries a per-kind ±80-char preview; use read_part for full content. " +
            "list_tool_calls is the tool-specific variant with state / toolId / callId surfaced."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Session id from list_sessions.")
            }
            putJsonObject("kind") {
                put("type", "string")
                put(
                    "description",
                    "Optional kind discriminator. One of: text, reasoning, tool, media, " +
                        "timeline-snapshot, render-progress, step-start, step-finish, compaction, todos.",
                )
            }
            putJsonObject("includeCompacted") {
                put("type", "boolean")
                put("description", "Include compacted parts? Default true.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Cap on returned rows (default 100, max 1000).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("sessionId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val sid = SessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${input.sessionId} not found. Call list_sessions to discover valid session ids.",
            )

        val requestedKind = input.kind?.takeIf { it.isNotBlank() }
        if (requestedKind != null) {
            require(requestedKind in VALID_KINDS) {
                "kind must be one of ${VALID_KINDS.joinToString(", ")} (got '$requestedKind')"
            }
        }

        val allParts = sessions.listSessionParts(sid, includeCompacted = input.includeCompacted)
        val filtered = if (requestedKind == null) allParts
        else allParts.filter { it.kindDiscriminator() == requestedKind }

        val cap = (input.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val capped = filtered.sortedByDescending { it.createdAt.toEpochMilliseconds() }.take(cap)

        val rows = capped.map { p ->
            Summary(
                partId = p.id.value,
                kind = p.kindDiscriminator(),
                messageId = p.messageId.value,
                createdAtEpochMs = p.createdAt.toEpochMilliseconds(),
                compactedAtEpochMs = p.compactedAt?.toEpochMilliseconds(),
                preview = p.preview(),
            )
        }

        val out = Output(
            sessionId = session.id.value,
            totalParts = filtered.size,
            returnedParts = rows.size,
            parts = rows,
        )
        val scope = requestedKind?.let { " kind=$it" } ?: ""
        val summary = if (rows.isEmpty()) {
            "No parts on session ${session.id.value}$scope."
        } else {
            "${rows.size} of ${filtered.size} part(s)$scope on ${session.id.value}, " +
                "most recent first: " +
                rows.take(5).joinToString("; ") { "${it.kind}:${it.preview.take(24)}" } +
                if (rows.size > 5) "; …" else ""
        }
        return ToolResult(
            title = "list parts (${rows.size})",
            outputForLlm = summary,
            data = out,
        )
    }

    private fun Part.kindDiscriminator(): String = when (this) {
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

    private fun Part.preview(): String = when (this) {
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

    private companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 1000
        const val PREVIEW_CHARS = 80
        val VALID_KINDS = setOf(
            "text", "reasoning", "tool", "media", "timeline-snapshot",
            "render-progress", "step-start", "step-finish", "compaction", "todos",
        )
    }
}
