package io.talevia.core.provider.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmRequest
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the OpenAI provider's SSE → LlmEvent translation. OpenAI's stream is
 * shaped differently from Anthropic's (one payload per choice delta, terminated by
 * `data: [DONE]`), so we pin each notable case independently:
 *  - happy-path text deltas + finish_reason parsing
 *  - tool_calls streamed with a per-index accumulator for arguments
 *  - `[DONE]` sentinel must not crash the JSON parser
 *  - malformed mid-stream chunk must NOT abort the stream
 */
class OpenAiProviderStreamTest {

    @Test
    fun happyPathEmitsTextAndFinish() = runTest {
        val sse = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"hi \"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"there\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n",
            "data: [DONE]\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()

        val deltas = events.filterIsInstance<LlmEvent.TextDelta>().map { it.text }
        assertEquals(listOf("hi ", "there"), deltas)
        val finish = events.filterIsInstance<LlmEvent.StepFinish>().single()
        assertEquals(FinishReason.STOP, finish.finish)
        assertEquals(4L, finish.usage.input)
        assertEquals(2L, finish.usage.output)
    }

    @Test
    fun toolCallArgumentsAccumulateAcrossChunks() = runTest {
        val sse = listOf(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_xyz\",\"function\":{\"name\":\"add_clip\",\"arguments\":\"{\\\"pro\"}}]}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"jectId\\\":\\\"p1\\\"}\"}}]}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
            "data: [DONE]\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()

        val ready = events.filterIsInstance<LlmEvent.ToolCallReady>().single()
        assertEquals("add_clip", ready.toolId)
        assertEquals("call_xyz", ready.callId.value)
        assertTrue(ready.input.toString().contains("\"p1\""))
        val finish = events.filterIsInstance<LlmEvent.StepFinish>().single()
        assertEquals(FinishReason.TOOL_CALLS, finish.finish)
    }

    @Test
    fun doneSentinelDoesNotCrashParser() = runTest {
        val sse = "data: [DONE]\n\n"
        val events = provider(sse).stream(simpleRequest()).toList()
        // With no deltas, the provider should still close the stream with a default
        // StepFinish(STOP) rather than throwing.
        assertTrue(events.any { it is LlmEvent.StepFinish })
    }

    @Test
    fun jsonNullFieldsDoNotCrashParser() = runTest {
        // OpenAI emits `"usage": null` on every non-final chunk when stream_options
        // include_usage=true, and may inline `"delta": null` / `"tool_calls": null`.
        // Treat JsonNull as absent rather than letting `?.jsonObject` throw.
        val sse = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}],\"usage\":null}\n\n",
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":1}}\n\n",
            "data: {\"choices\":[{\"delta\":null,\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()
        val deltas = events.filterIsInstance<LlmEvent.TextDelta>().map { it.text }
        assertEquals(listOf("hi"), deltas)
        val finish = events.filterIsInstance<LlmEvent.StepFinish>().single()
        assertEquals(FinishReason.STOP, finish.finish)
        assertEquals(7L, finish.usage.input)
        assertEquals(1L, finish.usage.output)
    }

    @Test
    fun malformedEventMidStreamDoesNotAbort() = runTest {
        val sse = listOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"ok \"}}]}\n\n",
            "data: NOT JSON AT ALL\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"after\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()
        val deltas = events.filterIsInstance<LlmEvent.TextDelta>().map { it.text }
        assertEquals(listOf("ok ", "after"), deltas)
        assertFalse(events.any { it is LlmEvent.Error })
    }

    @Test
    fun emptyAssistantTurnEmitsExplicitEmptyContent() = runTest {
        // Aborted prior turns (errored providers, cancellations) leave assistant
        // messages with neither text nor tool_calls. Without an explicit content,
        // OpenAI rejects the replay with "expected a string, got null". Pin: we
        // emit content="" for empty assistant turns so the request validates.
        val sid = SessionId("s1")
        val model = ModelRef("openai", "gpt-test")
        val ts = Instant.fromEpochMilliseconds(0)
        val emptyAssistant = MessageWithParts(
            message = Message.Assistant(
                id = MessageId("a1"),
                sessionId = sid,
                createdAt = ts,
                parentId = MessageId("u1"),
                model = model,
            ),
            parts = emptyList(),
        )
        val req = LlmRequest(model = model, messages = listOf(emptyAssistant))

        var capturedBody: String? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedBody = (request.body as TextContent).text
                    respond(
                        content = ByteReadChannel("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                }
            }
        }
        OpenAiProvider(client, apiKey = "test-key").stream(req).toList()

        val msgs = Json.parseToJsonElement(capturedBody!!).jsonObject["messages"]!!.jsonArray
        val assistant = msgs.single { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }.jsonObject
        assertEquals("", assistant["content"]!!.jsonPrimitive.content)
        assertFalse(assistant.containsKey("tool_calls"))
    }

    @Test
    fun httpErrorSurfacesAsErrorEvent() = runTest {
        // OpenAI returns a JSON `{"error":{...}}` body on 4xx — NOT SSE. Without a
        // status check, sseEvents() yields zero and the agent sees a phantom STOP /
        // 0-tokens finish. Pin: the provider must surface an Error + ERROR finish.
        val errBody = """{"error":{"message":"Invalid value for 'content': expected a string, got null.","type":"invalid_request_error","code":null}}"""
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(errBody),
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
        val events = OpenAiProvider(client, apiKey = "test-key").stream(simpleRequest()).toList()
        val err = events.filterIsInstance<LlmEvent.Error>().single()
        assertTrue(err.message.contains("HTTP 400"))
        assertTrue(err.message.contains("invalid_request_error"))
        assertTrue(err.message.contains("expected a string"))
        val finish = events.filterIsInstance<LlmEvent.StepFinish>().single()
        assertEquals(FinishReason.ERROR, finish.finish)
    }

    private fun simpleRequest() = LlmRequest(
        model = ModelRef("openai", "gpt-test"),
        messages = emptyList(),
    )

    private fun provider(sseBody: String): OpenAiProvider = OpenAiProvider(
        httpClient = mockClient(sseBody),
        apiKey = "test-key",
    )

    private fun mockClient(body: String): HttpClient = HttpClient(MockEngine) {
        engine {
            addHandler {
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                )
            }
        }
    }
}
