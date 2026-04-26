package io.talevia.core.provider.openai.codex

import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.talevia.core.JsonConfig
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Streams from the ChatGPT-backend Responses API
 * (`https://chatgpt.com/backend-api/codex/responses`) using OAuth credentials
 * minted by [OpenAiCodexAuthenticator]. Co-exists with the API-key-based
 * [io.talevia.core.provider.openai.OpenAiProvider] under a different provider id
 * (`openai-codex` vs `openai`); registry callers register both when both are
 * configured and the user picks via `/model`.
 *
 * Wire-format details — see
 * [openai/codex codex-rs/codex-api/src/common.rs `ResponsesApiRequest`](https://github.com/openai/codex/blob/main/codex-rs/codex-api/src/common.rs)
 * and [codex-rs/codex-api/src/sse/responses.rs] for the SSE event shape.
 */
@OptIn(ExperimentalUuidApi::class)
class OpenAiCodexProvider(
    private val httpClient: HttpClient,
    private val credentials: OpenAiCodexCredentialProvider,
    private val baseUrl: String = OpenAiCodexConstants.RESPONSES_BASE,
    private val originator: String = OpenAiCodexConstants.ORIGINATOR,
    private val json: Json = JsonConfig.default,
) : LlmProvider {

    override val id: String = "openai-codex"

    override suspend fun listModels(): List<ModelInfo> = listOf(
        // Models advertised by the ChatGPT backend Codex stack. The list will
        // drift as OpenAI ships new ones — `/model` only filters by id, so an
        // unknown id still works as long as the backend accepts it.
        ModelInfo("gpt-5-codex", "GPT-5 Codex", contextWindow = 256_000, supportsTools = true, supportsThinking = true, supportsImages = true),
        ModelInfo("gpt-5", "GPT-5", contextWindow = 256_000, supportsTools = true, supportsThinking = true, supportsImages = true),
        ModelInfo("gpt-5-mini", "GPT-5 Mini", contextWindow = 200_000, supportsTools = true, supportsThinking = true),
        ModelInfo("o3", "o3", contextWindow = 200_000, supportsTools = true, supportsThinking = true),
    )

    override fun stream(request: LlmRequest): Flow<LlmEvent> = channelFlow {
        send(LlmEvent.StepStart)

        val sessionIdHeader = request.options.openaiPromptCacheKey ?: Uuid.random().toString()
        val body = buildResponsesApiBody(request, sessionId = sessionIdHeader, json = json)
        val encoded = json.encodeToString(JsonElement.serializer(), body)

        // Stream once, transparently refreshing + retrying once if the first attempt 401s.
        var aborted = false
        var attempt = 0
        var refreshedAlready = false
        outer@ while (!aborted && attempt < 2) {
            attempt++
            val creds = try {
                credentials.current()
            } catch (e: OpenAiCodexNotSignedIn) {
                send(LlmEvent.Error(message = "openai-codex: ${e.message}", retriable = false))
                send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
                aborted = true
                break@outer
            } catch (e: OpenAiCodexAuthExpired) {
                send(LlmEvent.Error(message = "openai-codex auth expired — run /login again: ${e.message}", retriable = false))
                send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
                aborted = true
                break@outer
            }

            val parser = OpenAiCodexSseParser()
            val httpResponse = httpClient.preparePost("$baseUrl/responses") {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Authorization, "Bearer ${creds.accessToken}")
                    append("ChatGPT-Account-ID", creds.accountId)
                    append("originator", originator)
                    append("session_id", sessionIdHeader)
                    append(HttpHeaders.Accept, "text/event-stream")
                }
                setBody(encoded)
            }

            var unauthorizedRetry = false
            httpResponse.execute { http ->
                if (!http.status.isSuccess()) {
                    val raw = runCatching { http.bodyAsText() }.getOrElse { "<no body>" }
                    val status = http.status.value
                    if (status == 401 && !refreshedAlready) {
                        // First-shot 401 — refresh tokens once and retry the whole call.
                        refreshedAlready = true
                        unauthorizedRetry = runCatching { credentials.refresh() }.isSuccess
                        return@execute
                    }
                    val parsed = runCatching {
                        val obj = json.parseToJsonElement(raw).jsonObject
                        val err = obj["error"] as? JsonObject
                        val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull
                        val type = (err?.get("type") as? JsonPrimitive)?.contentOrNull
                        val code = (err?.get("code") as? JsonPrimitive)?.contentOrNull
                        listOfNotNull(type, code, msg).joinToString(": ").ifBlank { raw }
                    }.getOrElse { raw }
                    val retriable = status >= 500 || status == 429 || status == 408
                    val retryAfterMs = parseRetryAfterMs(
                        ms = http.headers["retry-after-ms"],
                        seconds = http.headers["retry-after"],
                    )
                    send(
                        LlmEvent.Error(
                            message = "openai-codex HTTP $status: $parsed",
                            retriable = retriable,
                            retryAfterMs = retryAfterMs,
                        ),
                    )
                    send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
                    aborted = true
                    return@execute
                }
                try {
                    http.sseEvents().collect { sse ->
                        if (sse.data == "[DONE]") return@collect
                        val payload = runCatching { json.parseToJsonElement(sse.data).jsonObject }
                            .getOrElse { cause ->
                                io.talevia.core.provider.logMalformedSse("openai-codex", sse.event, sse.data, cause)
                                return@collect
                            }
                        parser.process(payload) { ev -> send(ev) }
                    }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    send(
                        LlmEvent.Error(
                            message = "openai-codex stream aborted: ${e.message ?: e::class.simpleName ?: "I/O error"}",
                            retriable = true,
                        ),
                    )
                    send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = TokenUsage.ZERO))
                    aborted = true
                }
            }

            if (aborted) break@outer
            if (unauthorizedRetry) continue@outer

            // Stream completed normally for this attempt.
            parser.terminalError?.let { err ->
                send(err)
                send(LlmEvent.StepFinish(finish = FinishReason.ERROR, usage = parser.terminalUsage))
            } ?: run {
                send(
                    LlmEvent.StepFinish(
                        finish = parser.resolveFinish(),
                        usage = parser.terminalUsage,
                    ),
                )
            }
            break@outer
        }
    }
}
