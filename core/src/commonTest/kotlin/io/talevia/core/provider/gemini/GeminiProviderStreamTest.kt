package io.talevia.core.provider.gemini

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.http.content.TextContent
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmRequest
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.ToolState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the Gemini provider's SSE → LlmEvent translation. Gemini's stream is
 * shaped differently again (each `data:` frame holds a full `GenerateContentResponse`,
 * function calls arrive complete — no argument streaming — and there is no call id).
 * Pin each notable case independently:
 *  - happy-path text deltas + STOP finish + usage metadata
 *  - functionCall emits ToolCallStart + ToolCallReady with a minted CallId and
 *    the finish reason is TOOL_CALLS even when Gemini reports "STOP"
 *  - `thought: true` parts surface as reasoning, not text
 *  - malformed mid-stream chunk does not abort the stream
 */
class GeminiProviderStreamTest {

    @Test
    fun happyPathEmitsTextAndFinish() = runTest {
        val sse = listOf(
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hi \"}]}}]}\n\n",
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"there\"}]}," +
                "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":4,\"candidatesTokenCount\":2}}\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()

        val deltas = events.filterIsInstance<LlmEvent.TextDelta>().map { it.text }
        assertEquals(listOf("hi ", "there"), deltas)
        val finish = events.filterIsInstance<LlmEvent.StepFinish>().single()
        assertEquals(FinishReason.END_TURN, finish.finish)
        assertEquals(4L, finish.usage.input)
        assertEquals(2L, finish.usage.output)
    }

    @Test
    fun functionCallEmitsReadyAndFinishesAsToolCalls() = runTest {
        val sse = listOf(
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"add_clip\",\"args\":{\"projectId\":\"p1\"}}}]}," +
                "\"finishReason\":\"STOP\"}]}\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()

        val ready = events.filterIsInstance<LlmEvent.ToolCallReady>().single()
        assertEquals("add_clip", ready.toolId)
        assertTrue(ready.callId.value.startsWith("gemini-call-"))
        assertTrue(ready.input.toString().contains("\"p1\""))
        val start = events.filterIsInstance<LlmEvent.ToolCallStart>().single()
        assertEquals(ready.callId, start.callId)
        val finish = events.filterIsInstance<LlmEvent.StepFinish>().single()
        assertEquals(FinishReason.TOOL_CALLS, finish.finish)
    }

    @Test
    fun thoughtPartSurfacesAsReasoning() = runTest {
        val sse = listOf(
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[" +
                "{\"thought\":true,\"text\":\"planning\"},{\"text\":\"answer\"}]}," +
                "\"finishReason\":\"STOP\"}]}\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()

        val reasoning = events.filterIsInstance<LlmEvent.ReasoningDelta>().map { it.text }
        val text = events.filterIsInstance<LlmEvent.TextDelta>().map { it.text }
        assertEquals(listOf("planning"), reasoning)
        assertEquals(listOf("answer"), text)
    }

    @Test
    fun malformedEventMidStreamDoesNotAbort() = runTest {
        val sse = listOf(
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok \"}]}}]}\n\n",
            "data: NOT JSON AT ALL\n\n",
            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"after\"}]}," +
                "\"finishReason\":\"STOP\"}]}\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()
        val deltas = events.filterIsInstance<LlmEvent.TextDelta>().map { it.text }
        assertEquals(listOf("ok ", "after"), deltas)
        assertFalse(events.any { it is LlmEvent.Error })
        assertTrue(events.any { it is LlmEvent.StepFinish })
    }

    @Test
    fun httpErrorSurfacesAsErrorEvent() = runTest {
        val errBody = """{"error":{"code":400,"message":"Invalid value at 'contents[0].parts[0].text' (TYPE_STRING), null","status":"INVALID_ARGUMENT"}}"""
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
        val events = GeminiProvider(client, apiKey = "test-key").stream(simpleRequest()).toList()
        val err = events.filterIsInstance<LlmEvent.Error>().single()
        assertTrue(err.message.contains("HTTP 400"))
        assertTrue(err.message.contains("INVALID_ARGUMENT"))
        val finish = events.filterIsInstance<LlmEvent.StepFinish>().single()
        assertEquals(FinishReason.ERROR, finish.finish)
    }

    @Test
    fun failedToolPartReplaysAsFunctionCallSoResponseHasAnchor() = runTest {
        // Regression: Gemini requires every `functionResponse` part to pair
        // with a `functionCall` in a preceding model turn. A Failed tool part
        // must be replayed as functionCall. Same invariant as the OpenAI /
        // Anthropic providers — see OpenAiProviderStreamTest for the anchor
        // that first surfaced this in the CLI.
        val sid = SessionId("s1")
        val model = ModelRef("gemini", "gemini-test")
        val ts = Instant.fromEpochMilliseconds(0)
        val failedArgs: JsonObject = buildJsonObject { /* empty */ }
        val assistantWithFailedTool = MessageWithParts(
            message = Message.Assistant(
                id = MessageId("a1"),
                sessionId = sid,
                createdAt = ts,
                parentId = MessageId("u1"),
                model = model,
            ),
            parts = listOf(
                Part.Tool(
                    id = PartId("p1"),
                    messageId = MessageId("a1"),
                    sessionId = sid,
                    createdAt = ts,
                    callId = CallId("call_failed_xyz"),
                    toolId = "switch_project",
                    state = ToolState.Failed(input = failedArgs, message = "project 'session' not found"),
                ),
            ),
        )
        val req = LlmRequest(model = model, messages = listOf(assistantWithFailedTool))

        var capturedBody: String? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedBody = (request.body as TextContent).text
                    respond(
                        content = ByteReadChannel(
                            "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"ok\"}]}," +
                                "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1}}\n\n",
                        ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                }
            }
        }
        GeminiProvider(client, apiKey = "test-key").stream(req).toList()

        val body = Json.parseToJsonElement(capturedBody!!).jsonObject
        val contents = body["contents"]!!.jsonArray
        val modelTurn = contents.single { it.jsonObject["role"]!!.jsonPrimitive.content == "model" }.jsonObject
        val calls = modelTurn["parts"]!!.jsonArray
            .filter { it.jsonObject.containsKey("functionCall") }
        assertEquals(1, calls.size)
        assertEquals(
            "switch_project",
            calls[0].jsonObject["functionCall"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
        val userAfter = contents.last { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }.jsonObject
        val responses = userAfter["parts"]!!.jsonArray
            .filter { it.jsonObject.containsKey("functionResponse") }
        assertEquals(1, responses.size)
        assertEquals(
            "switch_project",
            responses[0].jsonObject["functionResponse"]!!.jsonObject["name"]!!.jsonPrimitive.content,
        )
    }

    private fun simpleRequest() = LlmRequest(
        model = ModelRef("gemini", "gemini-test"),
        messages = emptyList(),
    )

    private fun provider(sseBody: String): GeminiProvider = GeminiProvider(
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
