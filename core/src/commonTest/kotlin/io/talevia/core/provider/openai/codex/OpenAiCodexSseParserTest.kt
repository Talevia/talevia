package io.talevia.core.provider.openai.codex

import io.talevia.core.JsonConfig
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAiCodexSseParserTest {

    @Test
    fun textOnlyTurnEmitsStartDeltasEnd() = runTest {
        val parser = OpenAiCodexSseParser()
        val events = mutableListOf<LlmEvent>()
        parser.feed(
            """{"type":"response.created","response":{"id":"resp_1"}}""",
            """{"type":"response.output_item.added","item":{"id":"msg_1","type":"message","role":"assistant","content":[]}}""",
            """{"type":"response.output_text.delta","item_id":"msg_1","delta":"hello "}""",
            """{"type":"response.output_text.delta","item_id":"msg_1","delta":"world"}""",
            """{"type":"response.output_item.done","item":{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"hello world"}]}}""",
            """{"type":"response.completed","response":{"id":"resp_1","usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}}}""",
            sink = events,
        )

        // Expected: TextStart, TextDelta(hello ), TextDelta(world), TextEnd("hello world")
        assertEquals(4, events.size)
        assertTrue(events[0] is LlmEvent.TextStart)
        val partId = (events[0] as LlmEvent.TextStart).partId
        assertEquals(LlmEvent.TextDelta(partId, "hello "), events[1])
        assertEquals(LlmEvent.TextDelta(partId, "world"), events[2])
        assertEquals(LlmEvent.TextEnd(partId, "hello world"), events[3])

        assertEquals(10, parser.terminalUsage.input)
        assertEquals(2, parser.terminalUsage.output)
        assertNull(parser.terminalError)
        assertEquals(FinishReason.STOP, parser.resolveFinish())
    }

    @Test
    fun toolCallEmitsStartDeltasReadyAndFinishesWithToolCalls() = runTest {
        val parser = OpenAiCodexSseParser()
        val events = mutableListOf<LlmEvent>()
        parser.feed(
            """{"type":"response.created","response":{"id":"resp_2"}}""",
            """{"type":"response.output_item.added","item":{"id":"fc_1","type":"function_call","name":"create_clip","call_id":"call_abc","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","call_id":"call_abc","delta":"{\"path\""}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","call_id":"call_abc","delta":":\"clip.mp4\"}"}""",
            """{"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","name":"create_clip","call_id":"call_abc","arguments":"{\"path\":\"clip.mp4\"}"}}""",
            """{"type":"response.completed","response":{"id":"resp_2","usage":{"input_tokens":50,"output_tokens":12,"total_tokens":62}}}""",
            sink = events,
        )

        assertEquals(4, events.size)
        val start = events[0] as LlmEvent.ToolCallStart
        assertEquals("create_clip", start.toolId)
        assertEquals("call_abc", start.callId.value)
        assertEquals(LlmEvent.ToolCallInputDelta(start.partId, start.callId, """{"path""""), events[1])
        assertEquals(LlmEvent.ToolCallInputDelta(start.partId, start.callId, """:"clip.mp4"}"""), events[2])
        val ready = events[3] as LlmEvent.ToolCallReady
        assertEquals(start.callId, ready.callId)
        assertEquals("create_clip", ready.toolId)
        // The parsed input should round-trip the streamed args
        val parsedJson = ready.input.jsonObject
        assertEquals("clip.mp4", JsonConfig.default.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), parsedJson["path"]!!).trim('"'))

        assertEquals(FinishReason.TOOL_CALLS, parser.resolveFinish())
    }

    @Test
    fun reasoningDeltasMapToReasoningEvents() = runTest {
        val parser = OpenAiCodexSseParser()
        val events = mutableListOf<LlmEvent>()
        parser.feed(
            """{"type":"response.output_item.added","item":{"id":"reason_1","type":"reasoning","summary":[]}}""",
            """{"type":"response.reasoning_text.delta","item_id":"reason_1","content_index":0,"delta":"Thinking about it"}""",
            """{"type":"response.output_item.done","item":{"id":"reason_1","type":"reasoning","summary":[],"content":[{"type":"reasoning_text","text":"Thinking about it"}]}}""",
            """{"type":"response.completed","response":{"id":"r","usage":{"input_tokens":1,"output_tokens":1,"total_tokens":2}}}""",
            sink = events,
        )
        assertEquals(3, events.size)
        assertTrue(events[0] is LlmEvent.ReasoningStart)
        assertTrue(events[1] is LlmEvent.ReasoningDelta)
        assertEquals(LlmEvent.ReasoningEnd((events[0] as LlmEvent.ReasoningStart).partId, "Thinking about it"), events[2])
    }

    @Test
    fun responseFailedSetsRetriableErrorForUnknownCodes() = runTest {
        val parser = OpenAiCodexSseParser()
        val events = mutableListOf<LlmEvent>()
        parser.feed(
            """{"type":"response.failed","response":{"id":"r","error":{"code":"server_error","message":"upstream timeout"}}}""",
            sink = events,
        )
        assertTrue(events.isEmpty(), "response.failed updates terminalError but doesn't emit immediately")
        val err = parser.terminalError
        assertNotNull(err)
        assertTrue(err.retriable)
        assertTrue(err.message.contains("server_error"))
        assertEquals(FinishReason.ERROR, parser.resolveFinish())
    }

    @Test
    fun responseFailedQuotaExceededIsNotRetriable() = runTest {
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.failed","response":{"id":"r","error":{"code":"insufficient_quota","message":"quota"}}}""",
            sink = mutableListOf(),
        )
        val err = parser.terminalError
        assertNotNull(err)
        assertEquals(false, err.retriable)
    }

    @Test
    fun responseIncompleteIsRetriable() = runTest {
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.incomplete","response":{"id":"r","incomplete_details":{"reason":"max_output_tokens"}}}""",
            sink = mutableListOf(),
        )
        val err = parser.terminalError
        assertNotNull(err)
        assertTrue(err.message.contains("max_output_tokens"))
        assertTrue(err.retriable)
    }

    @Test
    fun unknownEventsAreIgnored() = runTest {
        val parser = OpenAiCodexSseParser()
        val events = mutableListOf<LlmEvent>()
        parser.feed(
            """{"type":"response.metadata","headers":{}}""",
            """{"type":"some.event.we.dont.know","foo":"bar"}""",
            sink = events,
        )
        assertTrue(events.isEmpty())
    }

    private suspend fun OpenAiCodexSseParser.feed(vararg lines: String, sink: MutableList<LlmEvent>) {
        val json = JsonConfig.default
        for (line in lines) {
            val obj = json.parseToJsonElement(line).jsonObject
            process(obj) { ev -> sink.add(ev) }
        }
    }
}
