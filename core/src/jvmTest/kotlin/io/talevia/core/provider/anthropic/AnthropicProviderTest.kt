package io.talevia.core.provider.anthropic

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.domain.Timeline
import io.talevia.core.provider.LlmRequest
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolSpec
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicProviderTest {

    private fun provider(): AnthropicProvider =
        AnthropicProvider(HttpClient(CIO), apiKey = "test")

    @Test
    fun systemPromptIsEncodedAsArrayWithEphemeralCacheControl() {
        val body = buildAnthropicRequestBody(
            LlmRequest(
                model = ModelRef("anthropic", "claude-opus-4-7"),
                messages = emptyList(),
                systemPrompt = "you are a helpful video editor",
            ),
        )

        val system = body["system"] as? JsonArray
            ?: error("system should be encoded as a block array, got ${body["system"]}")
        assertEquals(1, system.size)
        val block = system[0].jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("you are a helpful video editor", block["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", block["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptySystemPromptIsOmitted() {
        val body = buildAnthropicRequestBody(
            LlmRequest(model = ModelRef("anthropic", "claude-opus-4-7"), messages = emptyList()),
        )
        assertNull(body["system"])
    }

    @Test
    fun onlyFinalToolCarriesCacheControl() {
        val spec = { id: String ->
            ToolSpec(id = id, helpText = "help for $id", inputSchema = buildJsonObject { put("type", "object") })
        }
        val body = buildAnthropicRequestBody(
            LlmRequest(
                model = ModelRef("anthropic", "claude-opus-4-7"),
                messages = emptyList(),
                tools = listOf(spec("a"), spec("b"), spec("c")),
            ),
        )

        val tools = body["tools"]!!.jsonArray
        assertEquals(3, tools.size)
        assertNull(tools[0].jsonObject["cache_control"])
        assertNull(tools[1].jsonObject["cache_control"])
        val last = tools[2].jsonObject["cache_control"]!!.jsonObject
        assertEquals("ephemeral", last["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun noToolsBlockEmittedWhenToolListEmpty() {
        val body = buildAnthropicRequestBody(
            LlmRequest(model = ModelRef("anthropic", "claude-opus-4-7"), messages = emptyList()),
        )
        assertNull(body["tools"])
    }

    @Test
    fun assistantReasoningAndSnapshotBlocksAreReplayedAsText() {
        val epoch = Instant.fromEpochMilliseconds(0)
        val msg = Message.Assistant(
            id = MessageId("a1"),
            sessionId = SessionId("s1"),
            createdAt = epoch,
            parentId = MessageId("u0"),
            model = ModelRef("anthropic", "claude-opus-4-7"),
        )
        val parts = listOf(
            Part.Reasoning(PartId("p1"), MessageId("a1"), SessionId("s1"), epoch, text = "think think"),
            Part.TimelineSnapshot(PartId("p2"), MessageId("a1"), SessionId("s1"), epoch, timeline = Timeline()),
        )
        val body = buildAnthropicRequestBody(
            LlmRequest(
                model = ModelRef("anthropic", "claude-opus-4-7"),
                messages = listOf(MessageWithParts(msg, parts)),
            ),
        )

        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val content = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size, "reasoning + snapshot both replayed as text blocks")
        content.forEach { block ->
            assertEquals("text", block.jsonObject["type"]!!.jsonPrimitive.content)
        }
        assertTrue(content[0].jsonObject["text"]!!.jsonPrimitive.content.contains("<prior_reasoning>"))
        assertTrue(content[1].jsonObject["text"]!!.jsonPrimitive.content.contains("<timeline_snapshot"))
    }

    @Test
    fun coreRequestFieldsArePresent() {
        val body = buildAnthropicRequestBody(
            LlmRequest(
                model = ModelRef("anthropic", "claude-opus-4-7"),
                messages = emptyList(),
                maxTokens = 1234,
                temperature = 0.5,
            ),
        )
        assertEquals("claude-opus-4-7", body["model"]!!.jsonPrimitive.content)
        assertEquals(1234, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(0.5, body["temperature"]!!.jsonPrimitive.content.toDouble())
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
    }
}
