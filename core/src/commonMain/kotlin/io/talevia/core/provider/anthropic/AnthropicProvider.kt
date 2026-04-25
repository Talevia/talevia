package io.talevia.core.provider.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
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
import io.talevia.core.provider.logMalformedSse
import io.talevia.core.provider.parseRetryAfterMs
import io.talevia.core.provider.sseEvents
import io.talevia.core.session.FinishReason
import io.talevia.core.session.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

        val body = buildAnthropicRequestBody(request)
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
            // Anthropic returns a JSON `{"type":"error","error":{...}}` envelope on
            // 4xx/5xx, not an SSE stream. Bail out with a real Error event instead
            // of letting sseEvents() yield zero and the agent finish at usage 0/0.
            if (!http.status.isSuccess()) {
                val raw = runCatching { http.bodyAsText() }.getOrElse { "<no body>" }
                val parsed = runCatching {
                    val obj = json.parseToJsonElement(raw).jsonObject
                    val err = obj["error"]?.jsonObject
                    val type = err?.get("type")?.jsonPrimitive?.contentOrNull
                    val msg = err?.get("message")?.jsonPrimitive?.contentOrNull
                    listOfNotNull(type, msg).joinToString(": ").ifBlank { raw }
                }.getOrElse { raw }
                val status = http.status.value
                val retriable = status >= 500 || status == 429 || status == 408
                val retryAfterMs = parseRetryAfterMs(
                    ms = http.headers["retry-after-ms"],
                    seconds = http.headers["retry-after"],
                )
                send(
                    LlmEvent.Error(
                        message = "anthropic HTTP $status: $parsed",
                        retriable = retriable,
                        retryAfterMs = retryAfterMs,
                    ),
                )
                send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
                return@execute
            }
            // Mid-stream drops (server hangs up after 200 OK, socket reset,
            // CDN flake) need to retry just like HTTP 5xx — otherwise one
            // flaky connection kills the whole agent turn.
            try {
                http.sseEvents().collect { sse ->
                    val payload = runCatching { json.parseToJsonElement(sse.data).jsonObject }.getOrElse { cause ->
                        // Don't abort the whole stream on a single malformed event — the server may
                        // still send `message_stop`. But make it visible: silent drops used to leave
                        // operators with no explanation for partially-rendered turns.
                        logMalformedSse("anthropic", sse.event, sse.data, cause)
                        return@collect
                    }
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
                                finish = mapAnthropicStopReason(stopReason),
                                usage = TokenUsage(
                                    // Normalise to match OpenAI's semantics: `input` is
                                    // the total input, with `cacheRead` / `cacheWrite`
                                    // as subsets of it. Anthropic's raw `input_tokens`
                                    // reports only the uncached portion, so we sum in
                                    // the cache buckets here. Hit rate is then
                                    // `cacheRead / input` on any provider.
                                    input = inputTokens + cacheReadTokens + cacheCreationTokens,
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
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                io.talevia.core.logging.Loggers.get("provider.anthropic").log(
                    io.talevia.core.logging.LogLevel.WARN,
                    "stream.aborted",
                    mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())),
                )
                send(
                    LlmEvent.Error(
                        message = "anthropic stream aborted: ${e.message ?: e::class.simpleName ?: "I/O error"}",
                        retriable = true,
                    ),
                )
                send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
            }
        }
    }
}
