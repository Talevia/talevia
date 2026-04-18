package io.talevia.core.provider.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.PartId
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.provider.sseEvents
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.Part
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Anthropic Messages API streaming provider.
 *
 * Translates the Anthropic SSE event stream
 * (`message_start` / `content_block_*` / `message_delta` / `message_stop`)
 * into the normalised [LlmEvent] flow consumed by [io.talevia.core.agent.Agent].
 *
 * Reference: https://docs.anthropic.com/en/api/messages-streaming
 */
@OptIn(ExperimentalUuidApi::class)
class AnthropicProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val anthropicVersion: String = "2023-06-01",
    private val json: Json = JsonConfig.default,
) : LlmProvider {

    override val id: String = "anthropic"

    override suspend fun listModels(): List<ModelInfo> = listOf(
        ModelInfo("claude-opus-4-7", "Claude Opus 4.7", contextWindow = 200_000, supportsTools = true, supportsThinking = true, supportsImages = true),
        ModelInfo("claude-sonnet-4-6", "Claude Sonnet 4.6", contextWindow = 200_000, supportsTools = true, supportsThinking = true, supportsImages = true),
        ModelInfo("claude-haiku-4-5-20251001", "Claude Haiku 4.5", contextWindow = 200_000, supportsTools = true, supportsImages = true),
    )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        send(LlmEvent.StepStart)

        val body = buildRequestBody(request)
        val response = httpClient.preparePost("$baseUrl/v1/messages") {
            headers {
                append("x-api-key", apiKey)
                append("anthropic-version", anthropicVersion)
            }
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }

        val partIdByIndex = mutableMapOf<Int, PartId>()
        val callIdByIndex = mutableMapOf<Int, CallId>()
        val toolNameByIndex = mutableMapOf<Int, String>()
        val toolInputByIndex = mutableMapOf<Int, StringBuilder>()

        var inputTokens = 0L
        var outputTokens = 0L
        var cacheReadTokens = 0L
        var cacheCreationTokens = 0L
        var stopReason: String? = null

        response.execute { http ->
            http.sseEvents().collect { sse ->
                val payload = runCatching { json.parseToJsonElement(sse.data).jsonObject }.getOrNull() ?: return@collect
                when (sse.event) {
                    "message_start" -> {
                        val usage = payload["message"]?.jsonObject?.get("usage")?.jsonObject
                        usage?.let {
                            inputTokens = it["input_tokens"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0
                            cacheReadTokens = it["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0
                            cacheCreationTokens = it["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0
                        }
                    }
                    "content_block_start" -> {
                        val index = payload["index"]!!.jsonPrimitive.int
                        val block = payload["content_block"]!!.jsonObject
                        val partId = PartId(Uuid.random().toString())
                        partIdByIndex[index] = partId
                        when (block["type"]?.jsonPrimitive?.contentOrNull) {
                            "text" -> send(LlmEvent.TextStart(partId))
                            "thinking" -> send(LlmEvent.ReasoningStart(partId))
                            "tool_use" -> {
                                val callId = CallId(block["id"]!!.jsonPrimitive.content)
                                val name = block["name"]!!.jsonPrimitive.content
                                callIdByIndex[index] = callId
                                toolNameByIndex[index] = name
                                toolInputByIndex[index] = StringBuilder()
                                send(LlmEvent.ToolCallStart(partId, callId, name))
                            }
                        }
                    }
                    "content_block_delta" -> {
                        val index = payload["index"]!!.jsonPrimitive.int
                        val delta = payload["delta"]!!.jsonObject
                        val partId = partIdByIndex[index] ?: return@collect
                        when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                            "text_delta" -> send(LlmEvent.TextDelta(partId, delta["text"]!!.jsonPrimitive.content))
                            "thinking_delta" -> send(LlmEvent.ReasoningDelta(partId, delta["thinking"]!!.jsonPrimitive.content))
                            "input_json_delta" -> {
                                val callId = callIdByIndex[index] ?: return@collect
                                val chunk = delta["partial_json"]!!.jsonPrimitive.content
                                toolInputByIndex[index]!!.append(chunk)
                                send(LlmEvent.ToolCallInputDelta(partId, callId, chunk))
                            }
                        }
                    }
                    "content_block_stop" -> {
                        val index = payload["index"]!!.jsonPrimitive.int
                        val partId = partIdByIndex[index] ?: return@collect
                        if (callIdByIndex.containsKey(index)) {
                            val raw = toolInputByIndex[index]?.toString().orEmpty()
                            val parsed = if (raw.isBlank()) JsonObject(emptyMap())
                            else runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonObject(emptyMap()) }
                            send(
                                LlmEvent.ToolCallReady(
                                    partId,
                                    callIdByIndex[index]!!,
                                    toolNameByIndex[index]!!,
                                    parsed,
                                ),
                            )
                        }
                    }
                    "message_delta" -> {
                        val delta = payload["delta"]?.jsonObject
                        delta?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let { stopReason = it }
                        val usage = payload["usage"]?.jsonObject
                        usage?.get("output_tokens")?.jsonPrimitive?.intOrNull?.toLong()?.let { outputTokens = it }
                    }
                    "message_stop" -> send(
                        LlmEvent.StepFinish(
                            finish = mapStopReason(stopReason),
                            usage = TokenUsage(
                                input = inputTokens,
                                output = outputTokens,
                                cacheRead = cacheReadTokens,
                                cacheWrite = cacheCreationTokens,
                            ),
                        ),
                    )
                    "error" -> send(
                        LlmEvent.Error(
                            message = payload["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                ?: "anthropic stream error",
                            retriable = false,
                        ),
                    )
                }
            }
        }
    }

    private fun mapStopReason(raw: String?): FinishReason = when (raw) {
        "end_turn" -> FinishReason.END_TURN
        "max_tokens" -> FinishReason.MAX_TOKENS
        "stop_sequence" -> FinishReason.STOP
        "tool_use" -> FinishReason.TOOL_CALLS
        "refusal", "stop" -> FinishReason.STOP
        else -> FinishReason.STOP
    }

    internal fun buildRequestBody(request: LlmRequest): JsonObject = buildJsonObject {
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
        put("messages", buildMessages(request.messages))
    }

    private fun buildMessages(history: List<MessageWithParts>): JsonArray = buildJsonArray {
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
                                is ToolState.Running, is ToolState.Completed -> addJsonObject {
                                    put("type", "tool_use")
                                    put("id", p.callId.value)
                                    put("name", p.toolId)
                                    put("input", when (s) {
                                        is ToolState.Running -> s.input
                                        is ToolState.Completed -> s.input
                                        else -> JsonObject(emptyMap())
                                    })
                                }
                                else -> { /* pending/failed tool calls aren't replayed as tool_use */ }
                            }
                            else -> { /* reasoning/snapshots/etc not yet replayed */ }
                        }
                    })
                }
                val results = toolParts.filter { it.state is ToolState.Completed || it.state is ToolState.Failed }
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
                                else -> {}
                            }
                        }
                    })
                }
            }
        }
    }
}
