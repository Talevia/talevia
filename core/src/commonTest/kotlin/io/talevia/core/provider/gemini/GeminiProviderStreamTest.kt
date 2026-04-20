package io.talevia.core.provider.gemini

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmRequest
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
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
