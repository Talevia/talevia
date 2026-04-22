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
import io.talevia.core.provider.TpmThrottle
import io.talevia.core.provider.estimateRequestTokens
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
    /**
     * Optional pre-flight TPM guard. When set, every [stream] call reserves the
     * estimated token cost via [TpmThrottle.acquire] before firing the request,
     * then settles with the provider-reported usage once it arrives. Leaving it
     * null preserves the old "fire-and-429-then-back-off" behaviour — composition
     * roots wire this up when they know the org's TPM limit
     * (e.g. via `OPENAI_TPM_LIMIT` env var).
     */
    private val tpmThrottle: TpmThrottle? = null,
) : LlmProvider {

    override val id: String = "openai"

    override suspend fun listModels(): List<ModelInfo> = listOf(
        ModelInfo("gpt-4o", "GPT-4o", contextWindow = 128_000, supportsTools = true, supportsImages = true),
        ModelInfo("gpt-4o-mini", "GPT-4o Mini", contextWindow = 128_000, supportsTools = true, supportsImages = true),
        ModelInfo("gpt-4.1", "GPT-4.1", contextWindow = 1_000_000, supportsTools = true, supportsImages = true),
    )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        send(LlmEvent.StepStart)

        // Pre-flight TPM reservation — wait for the sliding window to have room
        // before firing, so we don't burn a round-trip eating a 429. Settled at
        // the end with the usage the provider reports; on failure we settle 0
        // so the window doesn't double-book with the retry.
        val reservation = tpmThrottle?.acquire(estimateRequestTokens(request))

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
        // OpenAI reports cache hits in `usage.prompt_tokens_details.cached_tokens`
        // on the final usage chunk (requires `stream_options.include_usage=true`,
        // which we already set below). These are a subset of promptTokens — they
        // still count toward input billing, but at ~50% the uncached rate.
        var cachedTokens = 0L
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
                val status = http.status.value
                val retriable = status >= 500 || status == 429 || status == 408
                val headerRetryMs = io.talevia.core.provider.parseRetryAfterMs(
                    ms = http.headers["retry-after-ms"],
                    seconds = http.headers["retry-after"],
                )
                // OpenAI's 429 body usually reads "...Please try again in 3.363s." — parse it
                // as a fallback when headers are absent. First match wins; we scan the combined
                // error text rather than the raw body so it works whether or not JSON parse
                // succeeded.
                val messageRetryMs = parseRetryHintFromMessage(parsed) ?: parseRetryHintFromMessage(raw)
                val retryAfterMs = headerRetryMs ?: messageRetryMs
                // Teach the throttle that the server-side window is full so the next turn's
                // acquire() actually waits instead of immediately re-sending and eating a
                // second 429. Cold-start case: fresh CLI process with empty local records
                // but a hot provider-side TPM window from a prior run.
                if (status == 429 && retryAfterMs != null) {
                    tpmThrottle?.stallFor(retryAfterMs)
                }
                send(
                    LlmEvent.Error(
                        message = "openai HTTP $status: $parsed",
                        retriable = retriable,
                        retryAfterMs = retryAfterMs,
                    ),
                )
                send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
                aborted = true
                return@execute
            }
            // The status-code branch above handles the "OpenAI rejected the
            // request up front" case. A separate failure mode is the server
            // accepting the request, starting the SSE stream, then hanging up
            // midway — TPM-saturated orgs, socket resets, and CDN flakes all
            // land here. Ktor surfaces it as "Failed to parse HTTP response:
            // the server prematurely closed the connection" or similar. If we
            // let it propagate out of channelFlow the entire turn dies; treat
            // it as retriable so the RetryPolicy backs off and tries again.
            try {
                http.sseEvents().collect { sse ->
                    if (sse.data == "[DONE]") return@collect
                    val payload = runCatching { json.parseToJsonElement(sse.data).jsonObject }.getOrElse { cause ->
                        io.talevia.core.provider.logMalformedSse("openai", sse.event, sse.data, cause)
                        return@collect
                    }

                    (payload["usage"] as? JsonObject)?.let { usage ->
                        promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull?.toLong() ?: promptTokens
                        completionTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull?.toLong() ?: completionTokens
                        cachedTokens = (usage["prompt_tokens_details"] as? JsonObject)
                            ?.get("cached_tokens")?.jsonPrimitive?.intOrNull?.toLong() ?: cachedTokens
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
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                io.talevia.core.logging.Loggers.get("provider.openai").log(
                    io.talevia.core.logging.LogLevel.WARN,
                    "stream.aborted",
                    mapOf("error" to (e.message ?: e::class.simpleName.orEmpty())),
                )
                send(
                    LlmEvent.Error(
                        message = "openai stream aborted: ${e.message ?: e::class.simpleName ?: "I/O error"}",
                        retriable = true,
                    ),
                )
                send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
                aborted = true
                return@execute
            }
        }

        if (aborted) {
            // Aborted means the provider never reported usage; drop the estimate so the
            // window doesn't double-book when RetryPolicy fires the next attempt.
            reservation?.settle(0)
            return@channelFlow
        }

        textPartId?.let { send(LlmEvent.TextEnd(it, "")) /* delta-summed text already sent */ }
        for ((_, buf) in tools) {
            val callId = buf.callId ?: continue
            val rawArgs = buf.args.toString()
            val parsed = if (rawArgs.isBlank()) JsonObject(emptyMap())
            else runCatching { json.parseToJsonElement(rawArgs) }.getOrElse { JsonObject(emptyMap()) }
            send(LlmEvent.ToolCallReady(buf.partId, callId, buf.name ?: "unknown", parsed))
        }

        // Settle with actuals so the sliding window self-corrects when the estimate missed.
        // cacheRead tokens are already part of promptTokens (billed at a discount, but the
        // TPM quota counts them in full on OpenAI), so we use the raw total.
        reservation?.settle(promptTokens + completionTokens)

        send(
            LlmEvent.StepFinish(
                finish = mapFinish(finishRaw),
                usage = TokenUsage(
                    input = promptTokens,
                    output = completionTokens,
                    cacheRead = cachedTokens,
                ),
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

    /**
     * Extract the "try again in Xs" hint OpenAI puts in the 429 error body when
     * retry-after headers are absent. Returns ms or null when no match is found.
     */
    private fun parseRetryHintFromMessage(text: String): Long? {
        val match = retryHintRegex.find(text) ?: return null
        val seconds = match.groupValues[1].toDoubleOrNull() ?: return null
        return (seconds * 1000.0).toLong().coerceAtLeast(0)
    }

    private companion object {
        // Matches "Please try again in 3.363s" / "try again in 450ms" in OpenAI 429 bodies.
        private val retryHintRegex = Regex("""try again in (\d+(?:\.\d+)?)s""")
    }

    private fun buildRequestBody(request: LlmRequest): JsonObject = buildJsonObject {
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
                    // Every tool part that emits a `role: tool` message below
                    // MUST appear in this assistant's tool_calls — OpenAI
                    // rejects the whole request with HTTP 400 "messages with
                    // role 'tool' must be a response to a preceding message
                    // with 'tool_calls'" otherwise. That means Failed counts
                    // too: we emit a `role: tool` for its error message, so
                    // the tool_call entry has to be here to anchor it.
                    val replayable = toolParts.filter {
                        it.state is ToolState.Running ||
                            it.state is ToolState.Completed ||
                            it.state is ToolState.Failed
                    }
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
                                    // Failed may carry null input when the
                                    // tool errored pre-dispatch (schema parse,
                                    // missing required field). Replay as {}
                                    // so the entry is well-formed; the paired
                                    // `role: tool` message below still carries
                                    // the error text.
                                    is ToolState.Failed -> s.input ?: JsonObject(emptyMap())
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
