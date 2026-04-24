package io.talevia.core.provider.gemini

import io.ktor.client.HttpClient
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
import io.talevia.core.provider.logMalformedSse
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Google Gemini streaming provider against the public Generative Language API
 * (`models/{model}:streamGenerateContent?alt=sse`). Translates Gemini's
 * `GenerateContentResponse` chunks into the normalised [LlmEvent] stream used
 * by [io.talevia.core.agent.Agent].
 *
 * Gemini quirks to keep in mind:
 *  - SSE frames have no `event:` header; each `data:` line contains a full
 *    `GenerateContentResponse` JSON.
 *  - Function calls are emitted as a single complete part (no argument streaming),
 *    and Gemini does not supply a `call_id` — we mint one ourselves and use the
 *    function name to correlate the response on replay.
 *  - Tool results are replayed as `role: "user"` with `functionResponse` parts,
 *    which is how Gemini expects the loop to continue.
 *
 * Reference: https://ai.google.dev/api/generate-content#method:-models.streamgeneratecontent
 */
@OptIn(ExperimentalUuidApi::class)
class GeminiProvider(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val apiVersion: String = "v1beta",
    private val json: Json = JsonConfig.default,
) : LlmProvider {

    override val id: String = "gemini"

    override suspend fun listModels(): List<ModelInfo> = listOf(
        ModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", contextWindow = 2_000_000, supportsTools = true, supportsThinking = true, supportsImages = true),
        ModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", contextWindow = 1_000_000, supportsTools = true, supportsThinking = true, supportsImages = true),
        ModelInfo("gemini-2.0-flash", "Gemini 2.0 Flash", contextWindow = 1_000_000, supportsTools = true, supportsImages = true),
    )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        send(LlmEvent.StepStart)

        val body = buildRequestBody(request)
        val url = "$baseUrl/$apiVersion/models/${request.model.modelId}:streamGenerateContent?alt=sse&key=$apiKey"
        val response = httpClient.preparePost(url) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonElement.serializer(), body))
        }

        var textPartId: PartId? = null
        var reasoningPartId: PartId? = null
        var promptTokens = 0L
        var candidateTokens = 0L
        var cachedTokens = 0L
        var thoughtsTokens = 0L
        var finishRaw: String? = null
        var sawToolCall = false
        var aborted = false

        response.execute { http ->
            // Gemini returns a JSON `{"error":{...}}` envelope on 4xx/5xx, not SSE.
            // Surface as a real Error event so callers don't see a phantom finish.
            if (!http.status.isSuccess()) {
                val raw = runCatching { http.bodyAsText() }.getOrElse { "<no body>" }
                val parsed = runCatching {
                    val obj = json.parseToJsonElement(raw).jsonObject
                    val err = obj["error"]?.jsonObject
                    val status = err?.get("status")?.jsonPrimitive?.contentOrNull
                    val msg = err?.get("message")?.jsonPrimitive?.contentOrNull
                    listOfNotNull(status, msg).joinToString(": ").ifBlank { raw }
                }.getOrElse { raw }
                val statusCode = http.status.value
                val retriable = statusCode >= 500 || statusCode == 429 || statusCode == 408
                val retryAfterMs = io.talevia.core.provider.parseRetryAfterMs(
                    ms = http.headers["retry-after-ms"],
                    seconds = http.headers["retry-after"],
                )
                send(
                    LlmEvent.Error(
                        message = "gemini HTTP $statusCode: $parsed",
                        retriable = retriable,
                        retryAfterMs = retryAfterMs,
                    ),
                )
                send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
                aborted = true
                return@execute
            }
            // Mid-stream drops (server hangs up after 200 OK, socket reset,
            // CDN flake) need to retry just like HTTP 5xx — otherwise one
            // flaky connection kills the whole agent turn.
            try {
                http.sseEvents().collect { sse ->
                    if (sse.data == "[DONE]") return@collect
                    val payload = runCatching { json.parseToJsonElement(sse.data).jsonObject }.getOrElse { cause ->
                        logMalformedSse("gemini", sse.event, sse.data, cause)
                        return@collect
                    }

                    payload["usageMetadata"]?.jsonObject?.let { usage ->
                        promptTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull?.toLong() ?: promptTokens
                        candidateTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull?.toLong() ?: candidateTokens
                        cachedTokens = usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull?.toLong() ?: cachedTokens
                        thoughtsTokens = usage["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull?.toLong() ?: thoughtsTokens
                    }

                    val candidate = payload["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@collect
                    candidate["finishReason"]?.jsonPrimitive?.contentOrNull?.let { finishRaw = it }

                    val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: return@collect
                    for (partEl in parts) {
                        val part = partEl.jsonObject
                        // Gemini marks "thinking" parts with a boolean `"thought": true`; the
                        // primitive `content` renders as the string "true".
                        val isThought = part["thought"]?.jsonPrimitive?.contentOrNull == "true"

                        part["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                            if (text.isEmpty()) return@let
                            if (isThought) {
                                val pid = reasoningPartId ?: PartId(Uuid.random().toString()).also {
                                    reasoningPartId = it
                                    send(LlmEvent.ReasoningStart(it))
                                }
                                send(LlmEvent.ReasoningDelta(pid, text))
                            } else {
                                val pid = textPartId ?: PartId(Uuid.random().toString()).also {
                                    textPartId = it
                                    send(LlmEvent.TextStart(it))
                                }
                                send(LlmEvent.TextDelta(pid, text))
                            }
                        }

                        part["functionCall"]?.jsonObject?.let { fc ->
                            val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@let
                            val args = fc["args"]?.jsonObject ?: JsonObject(emptyMap())
                            val partId = PartId(Uuid.random().toString())
                            // Gemini doesn't supply a call id; mint a stable one so the
                            // agent + tool_result replay round-trip works.
                            val callId = CallId("gemini-call-${Uuid.random()}")
                            sawToolCall = true
                            send(LlmEvent.ToolCallStart(partId, callId, name))
                            send(LlmEvent.ToolCallReady(partId, callId, name, args))
                        }
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                io.talevia.core.logging.Loggers.get("provider.gemini").log(
                    io.talevia.core.logging.LogLevel.WARN,
                    "stream.aborted",
                    mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())),
                )
                send(
                    LlmEvent.Error(
                        message = "gemini stream aborted: ${e.message ?: e::class.simpleName ?: "I/O error"}",
                        retriable = true,
                    ),
                )
                send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
                aborted = true
                return@execute
            }
        }

        if (aborted) return@channelFlow

        reasoningPartId?.let { send(LlmEvent.ReasoningEnd(it, "")) }
        textPartId?.let { send(LlmEvent.TextEnd(it, "")) }

        send(
            LlmEvent.StepFinish(
                finish = mapFinish(finishRaw, sawToolCall),
                usage = TokenUsage(
                    input = promptTokens,
                    output = candidateTokens,
                    reasoning = thoughtsTokens,
                    cacheRead = cachedTokens,
                ),
            ),
        )
    }

    private fun mapFinish(raw: String?, sawToolCall: Boolean): FinishReason = when (raw) {
        "STOP" -> if (sawToolCall) FinishReason.TOOL_CALLS else FinishReason.END_TURN
        "MAX_TOKENS" -> FinishReason.MAX_TOKENS
        "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> FinishReason.CONTENT_FILTER
        null -> if (sawToolCall) FinishReason.TOOL_CALLS else FinishReason.STOP
        else -> FinishReason.STOP
    }

    internal fun buildRequestBody(request: LlmRequest): JsonObject = buildJsonObject {
        put("contents", buildContents(request.messages))
        request.systemPrompt?.takeIf { it.isNotEmpty() }?.let { prompt ->
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", prompt) }
                }
            }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        request.tools.forEach { spec ->
                            addJsonObject {
                                put("name", spec.id)
                                put("description", spec.helpText)
                                put("parameters", spec.inputSchema)
                            }
                        }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("maxOutputTokens", request.maxTokens)
            request.temperature?.let { put("temperature", it) }
        }
    }

    private fun buildContents(history: List<MessageWithParts>): JsonArray = buildJsonArray {
        for (mwp in history) when (mwp.message) {
            is Message.User -> addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    for (p in mwp.parts) if (p is Part.Text && p.text.isNotEmpty()) addJsonObject {
                        put("text", p.text)
                    }
                }
            }
            is Message.Assistant -> {
                val toolParts = mwp.parts.filterIsInstance<Part.Tool>()
                addJsonObject {
                    put("role", "model")
                    putJsonArray("parts") {
                        for (p in mwp.parts) when (p) {
                            is Part.Text -> if (p.text.isNotEmpty()) addJsonObject { put("text", p.text) }
                            is Part.Reasoning -> if (p.text.isNotEmpty()) addJsonObject {
                                put("text", ReplayFormatting.formatReasoning(p))
                            }
                            is Part.TimelineSnapshot -> addJsonObject {
                                put("text", ReplayFormatting.formatTimelineSnapshot(p))
                            }
                            is Part.Tool -> when (val s = p.state) {
                                // Failed / Cancelled must be replayed as
                                // functionCall so the paired functionResponse
                                // below has a matching call to resolve against
                                // — same invariant as OpenAI's tool_calls/tool
                                // and Anthropic's tool_use/tool_result.
                                // Pending is still skipped (no input/callId
                                // on the wire).
                                is ToolState.Running,
                                is ToolState.Completed,
                                is ToolState.Failed,
                                is ToolState.Cancelled,
                                -> addJsonObject {
                                    putJsonObject("functionCall") {
                                        put("name", p.toolId)
                                        put("args", when (s) {
                                            is ToolState.Running -> s.input
                                            is ToolState.Completed -> s.input
                                            is ToolState.Failed -> s.input ?: JsonObject(emptyMap())
                                            is ToolState.Cancelled -> s.input ?: JsonObject(emptyMap())
                                            else -> JsonObject(emptyMap())
                                        })
                                    }
                                }
                                else -> { /* pending — not replayed */ }
                            }
                            else -> { /* step-start/finish/media/compaction/render-progress not replayed */ }
                        }
                    }
                }
                val results = toolParts.filter {
                    it.state is ToolState.Completed ||
                        it.state is ToolState.Failed ||
                        it.state is ToolState.Cancelled
                }
                if (results.isNotEmpty()) addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        for (p in results) addJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", p.toolId)
                                putJsonObject("response") {
                                    when (val s = p.state) {
                                        is ToolState.Completed -> put("result", s.outputForLlm)
                                        is ToolState.Failed -> {
                                            put("error", s.message)
                                        }
                                        is ToolState.Cancelled -> {
                                            put("error", "cancelled: ${s.message}")
                                        }
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
