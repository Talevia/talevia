package io.talevia.core.provider.openai

import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ReplayFormatting
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Build the Chat Completions request body — model + sampling config +
 * tool-spec array + replayed message history. Extracted from
 * [OpenAiProvider] so the streaming loop stays focused on SSE consumption
 * + event mapping; this file owns the "what does the request look like"
 * half.
 *
 * Internal-visibility because only [OpenAiProvider] should call it — the
 * shape is tightly coupled to Chat Completions semantics.
 */
internal fun buildChatCompletionsBody(request: LlmRequest, json: Json): JsonObject = buildJsonObject {
    put("model", request.model.modelId)
    // OpenAI deprecated `max_tokens` in favour of `max_completion_tokens`; the
    // newer reasoning / GPT-5 families actively reject the old name with a
    // 400 ("unsupported_parameter"). `max_completion_tokens` works on every
    // current chat-completion model including legacy gpt-4o, so we send only
    // the new name.
    put("max_completion_tokens", request.maxTokens)
    request.temperature?.let { put("temperature", it) }
    put("stream", true)
    putJsonObject("stream_options") { put("include_usage", true) }
    // Stable cache-routing hint — identical keys route to the same replica so
    // that OpenAI's automatic prompt cache hits consistently instead of
    // bouncing between machines. Agent seeds this from `sessionId`.
    request.options.openaiPromptCacheKey?.takeIf { it.isNotEmpty() }?.let {
        put("prompt_cache_key", it)
    }
    if (request.tools.isNotEmpty()) {
        putJsonArray("tools") {
            request.tools.forEach { spec ->
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", spec.id)
                        put("description", spec.helpText)
                        put("parameters", spec.inputSchema)
                    }
                }
            }
        }
    }
    put("messages", buildMessages(request, json))
}

/**
 * Render the message history into the Chat Completions `messages` array.
 *
 * Two nuances that made the inline version hard to read and are now local
 * to this function:
 *
 * 1. **Assistant `content` required when no `tool_calls`.** Aborted prior
 *    turns leave assistant messages with neither text nor tool_calls; the
 *    API rejects those with "expected string, got null" unless we emit
 *    `""`. Turns that only contain tool calls omit `content` entirely,
 *    matching the OpenAI examples.
 * 2. **Every `role: tool` message must follow a preceding assistant
 *    `tool_calls` entry.** Running / Completed / Failed tool parts all
 *    emit a `role: tool` message (Failed carries the error text) and
 *    *must* appear in the same turn's `tool_calls` array to anchor them.
 *    Filter the assistant `tool_calls` set to the same predicate that
 *    drives the `role: tool` loop below.
 */
private fun buildMessages(request: LlmRequest, json: Json): JsonArray = buildJsonArray {
    request.systemPrompt?.let { sys ->
        addJsonObject {
            put("role", "system")
            put("content", sys)
        }
    }
    for (mwp in request.messages) when (mwp.message) {
        is Message.User -> addJsonObject {
            put("role", "user")
            put("content", mwp.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text })
        }
        is Message.Assistant -> {
            val toolParts = mwp.parts.filterIsInstance<Part.Tool>()
            addJsonObject {
                put("role", "assistant")
                val text = buildString {
                    mwp.parts.forEach { p ->
                        val rendered = when (p) {
                            is Part.Text -> p.text.takeIf { it.isNotEmpty() }
                            is Part.Reasoning -> p.text.takeIf { it.isNotEmpty() }?.let { ReplayFormatting.formatReasoning(p) }
                            is Part.TimelineSnapshot -> ReplayFormatting.formatTimelineSnapshot(p)
                            else -> null
                        } ?: return@forEach
                        if (isNotEmpty()) append('\n')
                        append(rendered)
                    }
                }
                val replayable = toolParts.filter {
                    it.state is ToolState.Running ||
                        it.state is ToolState.Completed ||
                        it.state is ToolState.Failed ||
                        it.state is ToolState.Cancelled
                }
                if (text.isNotEmpty()) {
                    put("content", text)
                } else if (replayable.isEmpty()) {
                    put("content", "")
                }
                if (replayable.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        for (p in replayable) addJsonObject {
                            put("id", p.callId.value)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", p.toolId)
                                val input = when (val s = p.state) {
                                    is ToolState.Running -> s.input
                                    is ToolState.Completed -> s.input
                                    // Failed may carry null input when the tool
                                    // errored pre-dispatch (schema parse,
                                    // missing required field). Replay as {} so
                                    // the entry is well-formed; the paired
                                    // `role: tool` message below still carries
                                    // the error text. Cancelled is the same
                                    // shape — null input → {}.
                                    is ToolState.Failed -> s.input ?: JsonObject(emptyMap())
                                    is ToolState.Cancelled -> s.input ?: JsonObject(emptyMap())
                                    else -> JsonObject(emptyMap())
                                }
                                put("arguments", json.encodeToString(JsonElement.serializer(), input))
                            }
                        }
                    }
                }
            }
            for (p in toolParts) {
                val s = p.state
                if (s is ToolState.Completed || s is ToolState.Failed || s is ToolState.Cancelled) {
                    addJsonObject {
                        put("role", "tool")
                        put("tool_call_id", p.callId.value)
                        put(
                            "content",
                            when (s) {
                                is ToolState.Completed -> s.outputForLlm
                                is ToolState.Failed -> s.message
                                is ToolState.Cancelled -> "cancelled: ${s.message}"
                                else -> ""
                            },
                        )
                    }
                }
            }
        }
    }
}
