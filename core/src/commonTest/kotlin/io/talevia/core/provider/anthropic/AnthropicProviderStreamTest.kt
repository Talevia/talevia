package io.talevia.core.provider.anthropic

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
 * Exercises the Anthropic provider's SSE → LlmEvent translation against canned
 * payloads. Covers the happy path plus the behaviours that used to fail silently:
 *  - malformed single event mid-stream must NOT abort the stream
 *  - tool_use with no input_json_delta should still emit a ToolCallReady with
 *    JsonObject(emptyMap()) (matches the documented "no args" tool contract)
 *  - `ping` heartbeats (valid JSON but no case we handle) must be skipped silently
 */
class AnthropicProviderStreamTest {

    @Test
    fun happyPathEmitsTextAndStepFinish() = runTest {
        val sse = listOf(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}\n\n",
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\"}}\n\n",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"hello \"}}\n\n",
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"world\"}}\n\n",
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
            "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}\n\n",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()

        // StepStart, TextStart, 2x TextDelta, TextEnd, StepFinish
        assertEquals(LlmEvent.StepStart, events.first())
        assertTrue(events.any { it is LlmEvent.TextStart })
        val deltas = events.filterIsInstance<LlmEvent.TextDelta>().map { it.text }
        assertEquals(listOf("hello ", "world"), deltas)
        val finish = events.filterIsInstance<LlmEvent.StepFinish>().single()
        assertEquals(10L, finish.usage.input)
        assertEquals(5L, finish.usage.output)
    }

    @Test
    fun malformedEventMidStreamDoesNotAbort() = runTest {
        val sse = listOf(
            "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n",
            // Garbage payload — previously silently dropped. We assert the following
            // message_stop is still reached, i.e. we didn't bail on the whole stream.
            "event: content_block_delta\ndata: THIS IS NOT JSON\n\n",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()

        assertTrue(events.any { it is LlmEvent.StepFinish }, "stream must still finish after malformed chunk")
        assertFalse(events.any { it is LlmEvent.Error }, "malformed chunk should warn, not surface as Error")
    }

    @Test
    fun pingHeartbeatSkippedSilently() = runTest {
        val sse = listOf(
            "event: ping\ndata: {}\n\n",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()

        assertFalse(events.any { it is LlmEvent.TextDelta })
        assertTrue(events.any { it is LlmEvent.StepFinish })
    }

    @Test
    fun toolCallWithEmptyInputEmitsEmptyJsonObject() = runTest {
        val sse = listOf(
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_abc\",\"name\":\"screenshot\"}}\n\n",
            // NO input_json_delta — tool with no arguments.
            "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n",
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
        ).joinToString("")

        val events = provider(sse).stream(simpleRequest()).toList()
        val ready = events.filterIsInstance<LlmEvent.ToolCallReady>().single()
        assertEquals("screenshot", ready.toolId)
        assertEquals("toolu_abc", ready.callId.value)
        // Serialised form of JsonObject(emptyMap()) is "{}"
        assertEquals("{}", ready.input.toString())
    }

    @Test
    fun httpErrorSurfacesAsErrorEvent() = runTest {
        val errBody = """{"type":"error","error":{"type":"invalid_request_error","message":"messages: at least one message is required"}}"""
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
        val events = AnthropicProvider(client, apiKey = "test-key").stream(simpleRequest()).toList()
        val err = events.filterIsInstance<LlmEvent.Error>().single()
        assertTrue(err.message.contains("HTTP 400"))
        assertTrue(err.message.contains("invalid_request_error"))
        val finish = events.filterIsInstance<LlmEvent.StepFinish>().single()
        assertEquals(FinishReason.ERROR, finish.finish)
    }

    private fun simpleRequest() = LlmRequest(
        model = ModelRef("anthropic", "claude-test"),
        messages = emptyList(),
    )

    private fun provider(sseBody: String): AnthropicProvider = AnthropicProvider(
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
