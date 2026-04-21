package io.talevia.core.tool

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.Part
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

/**
 * A Tool is the unit of action the agent can dispatch. Generic over typed input/output
 * to give Kotlin call sites compile-time safety, while exposing JSON Schema to the LLM.
 *
 * Inspired by OpenCode's `Tool.Def` (`packages/opencode/src/tool/tool.ts`).
 */
interface Tool<I : Any, O : Any> {
    val id: String
    /** Human-readable help sent to the LLM as the tool's `description` field. */
    val helpText: String
    val inputSchema: JsonObject
    val inputSerializer: KSerializer<I>
    val outputSerializer: KSerializer<O>
    val permission: PermissionSpec

    suspend fun execute(input: I, ctx: ToolContext): ToolResult<O>
}

class ToolContext(
    val sessionId: SessionId,
    val messageId: MessageId,
    val callId: CallId,
    val askPermission: suspend (PermissionRequest) -> PermissionDecision,
    /** Publish an intermediate Part (e.g. RenderProgress) that the UI can stream. */
    val emitPart: suspend (Part) -> Unit,
    /** Read-only history snapshot at the moment dispatch began. */
    val messages: List<MessageWithParts>,
    /**
     * The session's `currentProjectId` at dispatch time, or `null` if the
     * session isn't yet bound to a project (VISION §5.4). Tools whose input
     * carries a `projectId` can default from this when the agent omits the
     * arg — this cycle keeps tool input shapes unchanged, so this is purely
     * informational for now. Defaulted to `null` so existing `ToolContext(…)`
     * call sites keep compiling without change.
     */
    val currentProjectId: ProjectId? = null,
)

data class ToolResult<O>(
    val title: String,
    /** String passed back to the LLM as `tool_result.content`. */
    val outputForLlm: String,
    /** Typed payload for UI consumption. */
    val data: O,
    val attachments: List<MediaAttachment> = emptyList(),
    val metadata: JsonObject? = null,
)

/**
 * Typed reference to a media artifact produced by a tool — e.g. the mp4 an
 * ExportTool writes. Consumers (UI previewers, subsequent tools, telemetry)
 * can act on it without re-probing the file.
 *
 * `source` is typically a filesystem path or platform URI; `widthPx`/`heightPx`/
 * `durationMs`/`sizeBytes` are best-effort and may be null when unknown.
 */
data class MediaAttachment(
    val mimeType: String,
    val source: String,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
)
