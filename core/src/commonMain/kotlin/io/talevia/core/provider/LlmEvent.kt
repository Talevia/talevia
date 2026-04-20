package io.talevia.core.provider

import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.session.FinishReason
import io.talevia.core.session.TokenUsage
import kotlinx.serialization.json.JsonElement

/**
 * Normalised event stream emitted by every [LlmProvider]. Each provider implementation
 * adapts its native protocol (Anthropic SSE, OpenAI SSE, ...) into this set so the
 * Agent loop and downstream subscribers do not need provider-specific code.
 *
 * IDs:
 *  - `partId` is provider-allocated (UUID); the Agent uses it directly as a PartId.
 *  - `callId` is the LLM-supplied tool call identifier (`tool_use.id` for Anthropic,
 *    `tool_calls[].id` for OpenAI). Identical to what the Agent must echo back as
 *    `tool_result.tool_use_id` / `tool_call_id`.
 */
sealed interface LlmEvent {

    data class TextStart(val partId: PartId) : LlmEvent
    data class TextDelta(val partId: PartId, val text: String) : LlmEvent
    data class TextEnd(val partId: PartId, val finalText: String) : LlmEvent

    data class ReasoningStart(val partId: PartId) : LlmEvent
    data class ReasoningDelta(val partId: PartId, val text: String) : LlmEvent
    data class ReasoningEnd(val partId: PartId, val finalText: String) : LlmEvent

    data class ToolCallStart(val partId: PartId, val callId: CallId, val toolId: String) : LlmEvent
    data class ToolCallInputDelta(val partId: PartId, val callId: CallId, val jsonDelta: String) : LlmEvent
    data class ToolCallReady(val partId: PartId, val callId: CallId, val toolId: String, val input: JsonElement) : LlmEvent

    data object StepStart : LlmEvent
    data class StepFinish(val finish: FinishReason, val usage: TokenUsage) : LlmEvent

    /**
     * Terminal error for the current provider stream. [retriable] is the provider's
     * own hint (e.g. HTTP 5xx, 429 with Retry-After); the Agent's [io.talevia.core.agent.RetryClassifier]
     * makes the final call — some providers don't bother setting this flag and the
     * classifier recovers by scanning the message. [retryAfterMs] mirrors the
     * `Retry-After` / `retry-after-ms` response header when present.
     */
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val retriable: Boolean = false,
        val retryAfterMs: Long? = null,
    ) : LlmEvent
}
