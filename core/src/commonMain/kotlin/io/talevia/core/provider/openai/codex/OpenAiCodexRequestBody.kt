package io.talevia.core.provider.openai.codex

import io.talevia.core.provider.LlmRequest
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
 * Build the Responses API request body for the ChatGPT Codex backend
 * (`POST .../codex/responses`). Format follows codex-rs/codex-api/src/common.rs
 * `ResponsesApiRequest` plus codex-rs/protocol/src/models.rs `ResponseItem`.
 *
 * Key shape differences from Chat Completions:
 *  - top-level `instructions` instead of an in-array system message
 *  - `input` (not `messages`) is an array of typed `ResponseItem`s — message,
 *    function_call, function_call_output, etc.
 *  - tools omit the outer `function: { ... }` wrapper used by Chat Completions
 *  - `tool_choice` is a string ("auto" / "required" / "none") at top level
 */
internal fun buildResponsesApiBody(
    request: LlmRequest,
    sessionId: String,
    json: Json,
): JsonObject = buildJsonObject {
    put("model", request.model.modelId)
    put("instructions", request.systemPrompt.orEmpty())
    put("input", buildInputArray(request, json))
    put("tools", buildToolsArray(request))
    put("tool_choice", "auto")
    put("parallel_tool_calls", false)
    put("store", false)
    put("stream", true)
    putJsonArray("include") { /* keep empty — opt-in fields */ }
    put("prompt_cache_key", sessionId)
    // Every currently-listed Codex backend model is a reasoning model
    // (see codex-rs/models-manager/models.json). Always send a `reasoning`
    // block — the backend rejects requests on these slugs when it's absent.
    // Effort comes from ProviderOptions when set, falls back to "medium".
    // Summary stays "auto" so reasoning_text deltas stream into our SseParser.
    putJsonObject("reasoning") {
        put("effort", normalizeEffort(request.options.openaiReasoningEffort) ?: "medium")
        put("summary", "auto")
    }
}

/**
 * Coerce [ProviderOptions.openaiReasoningEffort] (free-form string) to one of
 * the values Codex backend accepts: `none / minimal / low / medium / high / xhigh`.
 * Unknown values map to null so the caller can fall back to "medium".
 */
private fun normalizeEffort(raw: String?): String? {
    val v = raw?.trim()?.lowercase() ?: return null
    return v.takeIf { it in setOf("none", "minimal", "low", "medium", "high", "xhigh") }
}

private fun buildToolsArray(request: LlmRequest): JsonArray = buildJsonArray {
    request.tools.forEach { spec ->
        addJsonObject {
            put("type", "function")
            put("name", spec.id)
            put("description", spec.helpText)
            put("strict", false)
            put("parameters", spec.inputSchema)
        }
    }
}

private fun buildInputArray(request: LlmRequest, json: Json): JsonArray = buildJsonArray {
    for (mwp in request.messages) when (mwp.message) {
        is Message.User -> addJsonObject {
            put("type", "message")
            put("role", "user")
            putJsonArray("content") {
                val text = mwp.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
                addJsonObject {
                    put("type", "input_text")
                    put("text", text)
                }
            }
        }
        is Message.Assistant -> {
            val toolParts = mwp.parts.filterIsInstance<Part.Tool>()
            // Assistant text — only emit the message item if the assistant actually said something.
            // (Pure tool turns must NOT include an empty assistant message.)
            val assistantText = mwp.parts
                .filterIsInstance<Part.Text>()
                .joinToString("\n") { it.text }
                .ifEmpty { null }
            if (assistantText != null) {
                addJsonObject {
                    put("type", "message")
                    put("role", "assistant")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "output_text")
                            put("text", assistantText)
                        }
                    }
                }
            }
            // Replay tool calls + outputs. Pending tool parts are skipped — they
            // didn't carry input yet, replaying them as a function_call would
            // mislead the model.
            for (p in toolParts) {
                val (input, output) = when (val s = p.state) {
                    is ToolState.Running -> s.input to null
                    is ToolState.Completed -> s.input to s.outputForLlm
                    is ToolState.Failed -> (s.input ?: JsonObject(emptyMap())) to s.message
                    is ToolState.Cancelled -> (s.input ?: JsonObject(emptyMap())) to "cancelled: ${s.message}"
                    is ToolState.Pending -> continue
                }
                addJsonObject {
                    put("type", "function_call")
                    put("name", p.toolId)
                    put("arguments", json.encodeToString(JsonElement.serializer(), input))
                    put("call_id", p.callId.value)
                }
                if (output != null) {
                    addJsonObject {
                        put("type", "function_call_output")
                        put("call_id", p.callId.value)
                        put("output", output)
                    }
                }
            }
        }
    }
}
