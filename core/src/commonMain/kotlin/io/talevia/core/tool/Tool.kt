package io.talevia.core.tool

import io.talevia.core.CallId
import io.talevia.core.MessageId
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

/** Placeholder for tool-attached media (e.g. an exported video). Filled out in M2. */
data class MediaAttachment(
    val mimeType: String,
    val source: String,
)
