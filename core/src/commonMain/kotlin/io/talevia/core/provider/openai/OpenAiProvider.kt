package io.talevia.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.PartId
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.provider.ReplayFormatting
import io.talevia.core.provider.sseEvents
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * OpenAI Chat Completions streaming provider. Uses the legacy Chat Completions
 * endpoint (`/v1/chat/completions`) with `stream=true` because it has the broadest
 * compatibility (OpenAI-compatible local servers, vLLM, OpenRouter, etc.).
 *
 * Reference: https://platform.openai.com/docs/api-reference/chat/streaming
 */
@OptIn(ExperimentalUuidApi::class)
class OpenAiProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com",
    private val json: Json = JsonConfig.default,
) : LlmProvider {

    override val id: String = "openai"

    override suspend fun listModels(): List<ModelInfo> = listOf(
        ModelInfo("gpt-4o", "GPT-4o", contextWindow = 128_000, supportsTools = true, supportsImages = true),
        ModelInfo("gpt-4o-mini", "GPT-4o Mini", contextWindow = 128_000, supportsTools = true, supportsImages = true),
        ModelInfo("gpt-4.1", "GPT-4.1", contextWindow = 1_000_000, supportsTools = true, supportsImages = true),
    )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        send(LlmEvent.StepStart)

        val body = buildRequestBody(request)
        val encoded = json.encodeToString(JsonElement.serializer(), body)
        val response = httpClient.preparePost("$baseUrl/v1/chat/completions") {
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            setBody(encoded)
        }

        // Per-tool-call accumulators keyed by `index` (the field OpenAI uses to disambiguate
        // chunks belonging to the same call within a single assistant turn).
        data class ToolBuf(val partId: PartId, var callId: CallId? = null, var name: String? = null, val args: StringBuilder = StringBuilder())
        val tools = mutableMapOf<Int, ToolBuf>()

        var textPartId: PartId? = null
        var promptTokens = 0L
        var completionTokens = 0L
        var finishRaw: String? = null
        var aborted = false

        response.execute { http ->
            // OpenAI returns 4xx/5xx with a JSON `{"error": {...}}` body, NOT an SSE
            // stream. If we let sseEvents() chew on that, it just yields nothing and
            // the caller sees a phantom "STOP / 0 tokens" finish. Surface it as Error.
            if (!http.status.isSuccess()) {
                val raw = runCatching { http.bodyAsText() }.getOrElse { "<no body>" }
                val parsed = runCatching {
                    val obj = json.parseToJsonElement(raw).jsonObject
                    val err = obj["error"] as? JsonObject
                    val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull
                    val type = (err?.get("type") as? JsonPrimitive)?.contentOrNull
                    val code = (err?.get("code") as? JsonPrimitive)?.contentOrNull
                    listOfNotNull(type, code, msg).joinToString(": ").ifBlank { raw }
                }.getOrElse { raw }
                // On error, also surface the request payload at DEBUG so you can
                // see exactly which message tripped the schema check.
                io.talevia.core.logging.Loggers.get("provider.openai").log(
                    io.talevia.core.logging.LogLevel.DEBUG,
                    "request.dump",
                    mapOf("body" to encoded),
                )
                send(LlmEvent.Error("openai HTTP ${http.status.value}: $parsed"))
                send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
                aborted = true
                return@execute
            }
            http.sseEvents().collect { sse ->
                if (sse.data == "[DONE]") return@collect
                val payload = runCatching { json.parseToJsonElement(sse.data).jsonObject }.getOrElse { cause ->
                    io.talevia.core.provider.logMalformedSse("openai", sse.event, sse.data, cause)
                    return@collect
                }

                (payload["usage"] as? JsonObject)?.let { usage ->
                    promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.toLong() ?: promptTokens
                    completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull?.toLong() ?: completionTokens
                }

                // OpenAI may emit `"choices": []` on usage-only final chunks (when
                // stream_options.include_usage=true), and individual fields like
                // `delta` / `tool_calls` may arrive as JsonNull rather than absent.
                val choice = (payload["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@collect
                val delta = choice["delta"] as? JsonObject

                (delta?.get("content") as? JsonPrimitive)?.contentOrNull?.let { text ->
                    if (text.isNotEmpty()) {
                        val pid = textPartId ?: PartId(Uuid.random().toString()).also {
                            textPartId = it
                            send(LlmEvent.TextStart(it))
                        }
                        send(LlmEvent.TextDelta(pid, text))
                    }
                }

                (delta?.get("tool_calls") as? JsonArray)?.forEach { tcEl ->
                    val tc = tcEl as? JsonObject ?: return@forEach
                    val index = (tc["index"] as? JsonPrimitive)?.intOrNull ?: 0
                    val buf = tools.getOrPut(index) { ToolBuf(PartId(Uuid.random().toString())) }
                    val function = tc["function"] as? JsonObject
                    val newId = (tc["id"] as? JsonPrimitive)?.contentOrNull
                    val newName = (function?.get("name") as? JsonPrimitive)?.contentOrNull
                    if (buf.callId == null && newId != null) {
                        buf.callId = CallId(newId)
                        buf.name = newName
                        send(LlmEvent.ToolCallStart(buf.partId, buf.callId!!, buf.name ?: "unknown"))
                    } else if (newName != null && buf.name == null) {
                        buf.name = newName
                    }
                    (function?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { chunk ->
                        if (chunk.isNotEmpty()) {
                            buf.args.append(chunk)
                            buf.callId?.let { id -> send(LlmEvent.ToolCallInputDelta(buf.partId, id, chunk)) }
                        }
                    }
                }

                (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.let { finishRaw = it }
            }
        }

        if (aborted) return@channelFlow

        textPartId?.let { send(LlmEvent.TextEnd(it, "")) /* delta-summed text already sent */ }
        for ((_, buf) in tools) {
            val callId = buf.callId ?: continue
            val rawArgs = buf.args.toString()
            val parsed = if (rawArgs.isBlank()) JsonObject(emptyMap())
            else runCatching { json.parseToJsonElement(rawArgs) }.getOrElse { JsonObject(emptyMap()) }
            send(LlmEvent.ToolCallReady(buf.partId, callId, buf.name ?: "unknown", parsed))
        }

        send(
            LlmEvent.StepFinish(
                finish = mapFinish(finishRaw),
                usage = TokenUsage(input = promptTokens, output = completionTokens),
            ),
        )
    }

    private fun mapFinish(raw: String?): FinishReason = when (raw) {
        "stop" -> FinishReason.STOP
        "length" -> FinishReason.MAX_TOKENS
        "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
        "content_filter" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.STOP
    }

    private fun buildRequestBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.model.modelId)
        put("max_tokens", request.maxTokens)
        request.temperature?.let { put("temperature", it) }
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
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
        put("messages", buildMessages(request))
    }

    private fun buildMessages(request: LlmRequest): JsonArray = buildJsonArray {
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
                    val replayable = toolParts.filter { it.state is ToolState.Running || it.state is ToolState.Completed }
                    // OpenAI assistant messages: `content` is required as a string when
                    // there are no `tool_calls`. Aborted prior turns leave assistant
                    // messages with neither text nor tool_calls — emit "" so the
                    // request validates instead of getting "expected string, got null".
                    if (text.isNotEmpty()) {
                        put("content", text)
                    } else if (replayable.isEmpty()) {
                        put("content", "")
                    }
                    if (replayable.isNotEmpty()) putJsonArray("tool_calls") {
                        for (p in replayable) addJsonObject {
                            put("id", p.callId.value)
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", p.toolId)
                                val input = when (val s = p.state) {
                                    is ToolState.Running -> s.input
                                    is ToolState.Completed -> s.input
                                    else -> JsonObject(emptyMap())
                                }
                                put("arguments", json.encodeToString(JsonElement.serializer(), input))
                            }
                        }
                    }
                }
                for (p in toolParts) {
                    val s = p.state
                    if (s is ToolState.Completed || s is ToolState.Failed) addJsonObject {
                        put("role", "tool")
                        put("tool_call_id", p.callId.value)
                        put("content", when (s) {
                            is ToolState.Completed -> s.outputForLlm
                            is ToolState.Failed -> s.message
                            else -> ""
                        })
                    }
                }
            }
        }
    }
}
