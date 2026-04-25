package io.talevia.core.provider.anthropic

import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ReplayFormatting
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.Part
import io.talevia.core.session.ToolState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Encode an [LlmRequest] into the Anthropic Messages API JSON payload.
 *
 * Extracted from [AnthropicProvider] so the provider class stays focused
 * on HTTP + SSE plumbing — wire-format transcoding (system prompt
 * caching, tool spec mapping, message history → assistant/user array)
 * has its own file for both readability and reuse from tests that want
 * to assert on the encoded body without spinning up an HTTP client.
 *
 * Behaviour is byte-identical to the previous private methods.
 */
internal fun buildAnthropicRequestBody(request: LlmRequest): JsonObject = buildJsonObject {
    put("model", request.model.modelId)
    put("max_tokens", request.maxTokens)
    request.temperature?.let { put("temperature", it) }
    put("stream", true)
    // Prompt caching: encode system as a block array with an ephemeral
    // cache_control breakpoint. The system prompt is typically stable
    // across turns, so this is the highest-ROI caching point.
    request.systemPrompt?.takeIf { it.isNotEmpty() }?.let { prompt ->
        put("system", buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", prompt)
                putJsonObject("cache_control") { put("type", "ephemeral") }
            }
        })
    }
    if (request.tools.isNotEmpty()) {
        putJsonArray("tools") {
            val lastIdx = request.tools.lastIndex
            request.tools.forEachIndexed { idx, spec ->
                addJsonObject {
                    put("name", spec.id)
                    put("description", spec.helpText)
                    put("input_schema", spec.inputSchema)
                    // A single breakpoint on the final tool caches the
                    // whole tool block (caching covers everything up to
                    // and including the marker).
                    if (idx == lastIdx) {
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                }
            }
        }
    }
    request.options.anthropicThinkingBudget?.let { budget ->
        putJsonObject("thinking") {
            put("type", "enabled")
            put("budget_tokens", budget)
        }
    }
    put("messages", buildAnthropicMessages(request.messages))
}

/**
 * Translate persisted [MessageWithParts] history into Anthropic's
 * `messages` array shape — alternating user / assistant blocks with
 * `tool_use` / `tool_result` pairs maintaining the call-id linkage
 * Anthropic requires.
 */
private fun buildAnthropicMessages(history: List<MessageWithParts>): JsonArray = buildJsonArray {
    for (mwp in history) when (mwp.message) {
        is Message.User -> addJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                for (p in mwp.parts) if (p is Part.Text) addJsonObject {
                    put("type", "text")
                    put("text", p.text)
                }
            })
        }
        is Message.Assistant -> {
            val toolParts = mwp.parts.filterIsInstance<Part.Tool>()
            addJsonObject {
                put("role", "assistant")
                put("content", buildJsonArray {
                    for (p in mwp.parts) when (p) {
                        is Part.Text -> if (p.text.isNotEmpty()) addJsonObject {
                            put("type", "text")
                            put("text", p.text)
                        }
                        is Part.Tool -> when (val s = p.state) {
                            // Failed / Cancelled tool calls MUST be
                            // replayed as tool_use too: the paired
                            // tool_result below references p.callId via
                            // tool_use_id, and Anthropic rejects any
                            // tool_result that doesn't resolve to a
                            // tool_use in the preceding assistant turn.
                            // Pending is still skipped — no callId yet
                            // on the wire.
                            is ToolState.Running,
                            is ToolState.Completed,
                            is ToolState.Failed,
                            is ToolState.Cancelled,
                            -> addJsonObject {
                                put("type", "tool_use")
                                put("id", p.callId.value)
                                put("name", p.toolId)
                                put("input", when (s) {
                                    is ToolState.Running -> s.input
                                    is ToolState.Completed -> s.input
                                    // Pre-dispatch failures can carry
                                    // null input (schema parse error).
                                    // Replay as {} so the tool_use is
                                    // well-formed; the paired tool_result
                                    // still surfaces the error via
                                    // is_error=true.
                                    is ToolState.Failed -> s.input ?: JsonObject(emptyMap())
                                    is ToolState.Cancelled -> s.input ?: JsonObject(emptyMap())
                                    else -> JsonObject(emptyMap())
                                })
                            }
                            else -> { /* pending tool calls aren't replayed as tool_use */ }
                        }
                        is Part.Reasoning -> if (p.text.isNotEmpty()) addJsonObject {
                            put("type", "text")
                            put("text", ReplayFormatting.formatReasoning(p))
                        }
                        is Part.TimelineSnapshot -> addJsonObject {
                            put("type", "text")
                            put("text", ReplayFormatting.formatTimelineSnapshot(p))
                        }
                        else -> { /* step-start/finish/media/compaction/render-progress not replayed */ }
                    }
                })
            }
            val results = toolParts.filter {
                it.state is ToolState.Completed ||
                    it.state is ToolState.Failed ||
                    it.state is ToolState.Cancelled
            }
            if (results.isNotEmpty()) addJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    for (p in results) addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", p.callId.value)
                        when (val s = p.state) {
                            is ToolState.Completed -> put("content", s.outputForLlm)
                            is ToolState.Failed -> {
                                put("content", s.message)
                                put("is_error", true)
                            }
                            is ToolState.Cancelled -> {
                                // Replay cancellation as a Failed-shape tool_result so
                                // Anthropic accepts the message; the agent on the next
                                // turn sees the cancel reason verbatim.
                                put("content", "cancelled: ${s.message}")
                                put("is_error", true)
                            }
                            else -> {}
                        }
                    }
                })
            }
        }
    }
}

/**
 * Map Anthropic's terminal `stop_reason` enum onto Talevia's normalised
 * [FinishReason]. Unknown / unmapped reasons fall back to
 * `FinishReason.STOP` — the safest default.
 */
internal fun mapAnthropicStopReason(raw: String?): FinishReason = when (raw) {
    "end_turn" -> FinishReason.END_TURN
    "max_tokens" -> FinishReason.MAX_TOKENS
    "stop_sequence" -> FinishReason.STOP
    "tool_use" -> FinishReason.TOOL_CALLS
    "refusal", "stop" -> FinishReason.STOP
    else -> FinishReason.STOP
}
