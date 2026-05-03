package io.talevia.core.provider.openai

import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
import io.talevia.core.domain.Timeline
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ProviderOptions
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolSpec
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [buildChatCompletionsBody] —
 * `core/provider/openai/OpenAiRequestBody.kt`. Builds the OpenAI
 * Chat Completions request body. Cycle 218 audit: 167 LOC, 0 direct
 * test refs verified across both `commonTest` and `jvmTest`.
 *
 * Sister to cycle 217's `buildResponsesApiBody` (Codex Responses API).
 * Different wire shape, different concerns:
 *  - Tool spec is wrapped in `{type=function, function: {...}}` (NOT
 *    flat like Codex).
 *  - Uses `max_completion_tokens` (NOT `max_tokens`, which legacy
 *    GPT-5 reasoning models reject).
 *  - Tool replay uses `role: tool` messages anchored to
 *    `tool_calls[*].id` on the prior assistant turn.
 *
 * Same audit-pattern fallback as cycles 207-217. Drift in this
 * builder breaks every OpenAI Chat Completions dispatch in
 * production.
 *
 * Six correctness contracts pinned:
 *
 *  1. **Top-level shape required keys.** model /
 *     max_completion_tokens / stream / stream_options / messages —
 *     always present. `tools` only when non-empty;
 *     `prompt_cache_key` only when non-empty; `temperature` only
 *     when set.
 *
 *  2. **`max_completion_tokens` (NOT `max_tokens`).** OpenAI
 *     deprecated `max_tokens` and the GPT-5 reasoning family
 *     actively rejects it with a 400. Drift back to `max_tokens`
 *     would break every reasoning-model dispatch.
 *
 *  3. **Tool spec shape.** `{type=function, function: {name,
 *     description, parameters}}` — distinct from Codex's flat
 *     shape. Drift to flat would silently fail Chat Completions
 *     validation.
 *
 *  4. **Assistant content fallback.** Per kdoc: "API rejects with
 *     'expected string, got null' unless we emit `""`". Three
 *     branches: (a) has text → content = text; (b) no text but
 *     replayable tools → omit content entirely; (c) no text AND
 *     no tools (aborted prior turn) → content = "".
 *
 *  5. **`tool_calls` + `role:tool` pairing.** Replayable filter is
 *     Running ∪ Completed ∪ Failed ∪ Cancelled. Pending NEVER
 *     emits (no anchor in tool_calls AND no role:tool message).
 *     `role:tool` messages emit only for Completed (outputForLlm)
 *     / Failed (message) / Cancelled ("cancelled: <msg>"). Running
 *     emits in `tool_calls` but NOT as role:tool (no output yet).
 *
 *  6. **Tool-call arguments null-input fallback.** Failed +
 *     Cancelled may carry null input (errored pre-dispatch). The
 *     `arguments` JSON must be `"{}"` not `"null"` — drift would
 *     fail the model's JSON.parse on replay.
 *
 * Plus shape pins: `stream=true` always; `stream_options.include_usage
 * =true`; `temperature` omitted when null; user message text joined
 * by newlines; assistant text + reasoning + timeline_snapshot
 * formatted via ReplayFormatting; system prompt becomes the FIRST
 * message.
 */
class OpenAiRequestBodyTest {

    private val json = JsonConfig.default
    private val baseTime: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun userMessage(): Message.User = Message.User(
        id = MessageId("u1"),
        sessionId = SessionId("s"),
        createdAt = baseTime,
        agent = "test",
        model = ModelRef("openai", "gpt-5"),
    )

    private fun assistantMessage(): Message.Assistant = Message.Assistant(
        id = MessageId("a1"),
        sessionId = SessionId("s"),
        createdAt = baseTime,
        parentId = MessageId("u1"),
        model = ModelRef("openai", "gpt-5"),
    )

    private fun textPart(text: String, id: String = "p1"): Part.Text = Part.Text(
        id = PartId(id),
        messageId = MessageId("m"),
        sessionId = SessionId("s"),
        createdAt = baseTime,
        text = text,
    )

    private fun reasoningPart(text: String, id: String = "p1"): Part.Reasoning = Part.Reasoning(
        id = PartId(id),
        messageId = MessageId("m"),
        sessionId = SessionId("s"),
        createdAt = baseTime,
        text = text,
    )

    private fun timelineSnapshotPart(id: String = "ts1"): Part.TimelineSnapshot = Part.TimelineSnapshot(
        id = PartId(id),
        messageId = MessageId("m"),
        sessionId = SessionId("s"),
        createdAt = baseTime,
        timeline = Timeline(),
    )

    private fun toolPart(
        callId: String,
        toolId: String,
        state: ToolState,
        id: String = "tp1",
    ): Part.Tool = Part.Tool(
        id = PartId(id),
        messageId = MessageId("m"),
        sessionId = SessionId("s"),
        createdAt = baseTime,
        callId = CallId(callId),
        toolId = toolId,
        state = state,
    )

    private fun request(
        messages: List<MessageWithParts> = emptyList(),
        tools: List<ToolSpec> = emptyList(),
        systemPrompt: String? = null,
        options: ProviderOptions = ProviderOptions(),
        modelId: String = "gpt-5",
        maxTokens: Int = 4096,
        temperature: Double? = null,
    ): LlmRequest = LlmRequest(
        model = ModelRef("openai", modelId),
        messages = messages,
        tools = tools,
        systemPrompt = systemPrompt,
        options = options,
        maxTokens = maxTokens,
        temperature = temperature,
    )

    private fun build(req: LlmRequest = request()): JsonObject =
        buildChatCompletionsBody(req, json)

    // ── 1. Top-level shape ──────────────────────────────────

    @Test fun bodyHasRequiredKeysWithDefaultRequest() {
        val body = build()
        // Always present.
        assertTrue("model" in body.keys)
        assertTrue("max_completion_tokens" in body.keys)
        assertTrue("stream" in body.keys)
        assertTrue("stream_options" in body.keys)
        assertTrue("messages" in body.keys)
        // Conditional — empty by default.
        assertFalse("tools" in body.keys, "tools omitted when none registered")
        assertFalse("prompt_cache_key" in body.keys, "prompt_cache_key omitted when null")
        assertFalse("temperature" in body.keys, "temperature omitted when null")
    }

    @Test fun streamFlagsAlwaysSet() {
        val body = build()
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean)
        val so = body["stream_options"]!!.jsonObject
        assertEquals(true, so["include_usage"]!!.jsonPrimitive.boolean)
    }

    @Test fun modelEqualsRequestModelId() {
        val body = build(request(modelId = "gpt-5.4-preview"))
        assertEquals("gpt-5.4-preview", body["model"]!!.jsonPrimitive.content)
    }

    @Test fun temperatureOnlyEmittedWhenSet() {
        val withT = build(request(temperature = 0.7))
        assertEquals(0.7, withT["temperature"]!!.jsonPrimitive.content.toDouble())
        val withoutT = build(request(temperature = null))
        assertFalse("temperature" in withoutT.keys)
    }

    @Test fun promptCacheKeyEmittedWhenNonEmpty() {
        val body = build(
            request(options = ProviderOptions(openaiPromptCacheKey = "session-123")),
        )
        assertEquals("session-123", body["prompt_cache_key"]!!.jsonPrimitive.content)
    }

    @Test fun promptCacheKeyOmittedWhenEmptyOrNull() {
        // Pin: per impl `takeIf { it.isNotEmpty() }`. Empty AND null
        // both omit the key (NOT JsonNull).
        for (opt in listOf(
            ProviderOptions(openaiPromptCacheKey = null),
            ProviderOptions(openaiPromptCacheKey = ""),
        )) {
            val body = build(request(options = opt))
            assertFalse(
                "prompt_cache_key" in body.keys,
                "key absent for opt=$opt; got keys=${body.keys}",
            )
        }
    }

    // ── 2. max_completion_tokens (NOT max_tokens) ───────────

    @Test fun maxCompletionTokensFieldNameNotMaxTokens() {
        // Marquee API-deprecation pin: per kdoc, GPT-5 reasoning
        // models reject the legacy `max_tokens` with 400. Drift back
        // would break every reasoning-model dispatch.
        val body = build(request(maxTokens = 8192))
        assertTrue(
            "max_completion_tokens" in body.keys,
            "must use the new name 'max_completion_tokens'",
        )
        assertFalse(
            "max_tokens" in body.keys,
            "legacy 'max_tokens' must NOT be present",
        )
        assertEquals(8192, body["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
    }

    // ── 3. Tool spec shape ─────────────────────────────────

    @Test fun toolSpecWrappedInFunctionField() {
        // Marquee Chat-Completions-shape pin: `{type=function,
        // function: {name, description, parameters}}` — distinct
        // from Codex's flat shape. Drift to Codex shape would fail
        // Chat Completions validation.
        val schema = buildJsonObject { put("type", JsonPrimitive("object")) }
        val body = build(
            request(tools = listOf(ToolSpec(id = "echo", helpText = "Echo back input.", inputSchema = schema))),
        )
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        // Crucially: `function` NESTED block (NOT flat).
        val fn = tool["function"]!!.jsonObject
        assertEquals("echo", fn["name"]!!.jsonPrimitive.content)
        assertEquals("Echo back input.", fn["description"]!!.jsonPrimitive.content)
        assertEquals(schema, fn["parameters"])
        // Drift defense: the flat-Codex-style fields must NOT appear at top level.
        assertFalse("name" in tool.keys, "name must be inside `function`, not flat")
        assertFalse("description" in tool.keys)
        assertFalse("parameters" in tool.keys)
    }

    @Test fun emptyToolsArrayOmittedEntirely() {
        // Pin: per impl `if (request.tools.isNotEmpty())`. Empty
        // tools list → key absent (NOT empty array). Drift to
        // empty-array might be benign on OpenAI but the kdoc-implied
        // contract is "absent = not advertised".
        val body = build()
        assertFalse("tools" in body.keys)
    }

    // ── 4. Assistant content fallback ──────────────────────

    @Test fun assistantWithTextEmitsContentAsText() {
        val body = build(
            request(
                messages = listOf(
                    MessageWithParts(
                        message = assistantMessage(),
                        parts = listOf(textPart("hello world")),
                    ),
                ),
            ),
        )
        val msgs = body["messages"]!!.jsonArray
        val a = msgs.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject
        assertEquals("hello world", a["content"]!!.jsonPrimitive.content)
    }

    @Test fun assistantWithNoTextNoToolsEmitsContentEmptyString() {
        // Marquee aborted-turn pin: a defensive `""` content prevents
        // the OpenAI "expected string, got null" 400 on aborted prior
        // turns.
        val req = request(
            messages = listOf(
                MessageWithParts(message = assistantMessage(), parts = emptyList()),
            ),
        )
        val body = build(req)
        val msgs = body["messages"]!!.jsonArray
        val a = msgs.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject
        assertEquals("", a["content"]!!.jsonPrimitive.content)
    }

    @Test fun assistantWithToolsButNoTextOmitsContentEntirely() {
        // Pin: per kdoc "Turns that only contain tool calls omit
        // `content` entirely, matching the OpenAI examples." Drift
        // to "" or null would deviate from the canonical shape.
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "call-1",
                            toolId = "echo",
                            state = ToolState.Completed(
                                input = JsonObject(emptyMap()),
                                outputForLlm = "ok",
                                data = JsonNull,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val a = body["messages"]!!.jsonArray
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject
        assertFalse(
            "content" in a.keys,
            "pure-tool assistant turn must OMIT content; got keys=${a.keys}",
        )
    }

    @Test fun assistantTextWithReasoningAndSnapshotAreFormattedAndConcatenated() {
        // Pin: text + reasoning (formatted) + timeline_snapshot
        // (formatted) all concat with `\n` separator.
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        textPart("the actual reply", id = "p1"),
                        reasoningPart("internal thought", id = "p2"),
                        timelineSnapshotPart(id = "p3"),
                    ),
                ),
            ),
        )
        val body = build(req)
        val a = body["messages"]!!.jsonArray
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject
        val content = a["content"]!!.jsonPrimitive.content
        assertTrue("the actual reply" in content, "text included")
        assertTrue(
            "<prior_reasoning>internal thought</prior_reasoning>" in content,
            "reasoning wrapped via ReplayFormatting; got: $content",
        )
        assertTrue(
            "<timeline_snapshot id=\"p3\">" in content,
            "timeline_snapshot wrapped via ReplayFormatting; got: $content",
        )
    }

    @Test fun assistantBlankReasoningOrTextSkipped() {
        // Pin: per impl `text.takeIf { it.isNotEmpty() }`. An
        // empty-string Text or Reasoning part produces no content
        // contribution. (Drift to "include empty strings" would
        // produce trailing newlines and confuse the model.)
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        textPart("", id = "p1"),
                        textPart("real text", id = "p2"),
                        reasoningPart("", id = "p3"),
                    ),
                ),
            ),
        )
        val body = build(req)
        val a = body["messages"]!!.jsonArray
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject
        assertEquals("real text", a["content"]!!.jsonPrimitive.content)
    }

    // ── 5. tool_calls + role:tool pairing ──────────────────

    @Test fun toolCallsArrayShape() {
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        textPart("calling tool"),
                        toolPart(
                            callId = "call-1",
                            toolId = "echo",
                            state = ToolState.Completed(
                                input = buildJsonObject { put("text", JsonPrimitive("hi")) },
                                outputForLlm = "echoed: hi",
                                data = JsonNull,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val a = body["messages"]!!.jsonArray
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject
        val calls = a["tool_calls"]!!.jsonArray
        assertEquals(1, calls.size)
        val call = calls[0].jsonObject
        assertEquals("call-1", call["id"]!!.jsonPrimitive.content)
        assertEquals("function", call["type"]!!.jsonPrimitive.content)
        val fn = call["function"]!!.jsonObject
        assertEquals("echo", fn["name"]!!.jsonPrimitive.content)
        // arguments is a JSON string (NOT a JsonObject inline).
        val arguments = fn["arguments"]!!.jsonPrimitive.content
        assertTrue("\"text\":\"hi\"" in arguments, "arguments JSON-encoded; got: $arguments")
    }

    @Test fun pendingToolPartProducesNoToolCallAndNoToolMessage() {
        // Marquee Pending-skip pin: Pending must NOT appear in
        // tool_calls (would mislead model into thinking the tool
        // was invoked when arguments hadn't streamed yet) AND must
        // NOT emit a role:tool message (would orphan with no
        // anchor).
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "pending-1",
                            toolId = "not_yet",
                            state = ToolState.Pending,
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val msgs = body["messages"]!!.jsonArray
        val a = msgs.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject
        // No tool_calls (only pending → no replayable, so no key).
        assertFalse(
            "tool_calls" in a.keys,
            "Pending-only turn must omit tool_calls; got: $a",
        )
        // No role:tool messages at all.
        val toolMsgs = msgs.map { it.jsonObject }
            .filter { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals(0, toolMsgs.size)
    }

    @Test fun runningToolEmitsToolCallButNoRoleToolMessage() {
        // Pin: Running has input but no output yet. Replayable for
        // tool_calls anchoring; role:tool message is NOT emitted
        // (no content).
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "running-1",
                            toolId = "long_op",
                            state = ToolState.Running(
                                input = buildJsonObject { put("k", JsonPrimitive("v")) },
                            ),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val msgs = body["messages"]!!.jsonArray
        val a = msgs.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject
        assertEquals(1, a["tool_calls"]!!.jsonArray.size, "Running anchored in tool_calls")
        val toolMsgs = msgs.map { it.jsonObject }
            .filter { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals(0, toolMsgs.size, "Running emits NO role:tool message (no output yet)")
    }

    @Test fun completedToolEmitsToolCallAndOutputAsContent() {
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "done-1",
                            toolId = "echo",
                            state = ToolState.Completed(
                                input = JsonObject(emptyMap()),
                                outputForLlm = "echoed",
                                data = JsonNull,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val msgs = body["messages"]!!.jsonArray
        val toolMsg = msgs.map { it.jsonObject }
            .first { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals("done-1", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("echoed", toolMsg["content"]!!.jsonPrimitive.content)
    }

    @Test fun failedToolMessageContentIsTheErrorMessage() {
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "fail-1",
                            toolId = "broken",
                            state = ToolState.Failed(
                                input = JsonObject(emptyMap()),
                                message = "auth failed",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val toolMsg = body["messages"]!!.jsonArray.map { it.jsonObject }
            .first { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals("auth failed", toolMsg["content"]!!.jsonPrimitive.content)
    }

    @Test fun cancelledToolMessageContentPrependsCancelledLabel() {
        // Pin: per impl `"cancelled: ${s.message}"`. Distinguishes
        // from Failed without parsing message-prefix conventions.
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "cancel-1",
                            toolId = "interrupted",
                            state = ToolState.Cancelled(
                                input = JsonObject(emptyMap()),
                                message = "user pressed Ctrl-C",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val toolMsg = body["messages"]!!.jsonArray.map { it.jsonObject }
            .first { it["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals("cancelled: user pressed Ctrl-C", toolMsg["content"]!!.jsonPrimitive.content)
    }

    // ── 6. tool-call arguments null-input fallback ─────────

    @Test fun failedToolWithNullInputArgumentsBecomeEmptyObjectString() {
        // Marquee null-input fallback pin: per impl `s.input ?:
        // JsonObject(emptyMap())`. The arguments field must be
        // `"{}"` (NOT `"null"`) so the model's JSON.parse on replay
        // succeeds.
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "fail-2",
                            toolId = "early_fail",
                            state = ToolState.Failed(input = null, message = "auth"),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val a = body["messages"]!!.jsonArray
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject
        val call = a["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("{}", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
    }

    @Test fun cancelledToolWithNullInputArgumentsBecomeEmptyObjectString() {
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "cancel-2",
                            toolId = "early_cancel",
                            state = ToolState.Cancelled(input = null, message = "ctrl-c"),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val a = body["messages"]!!.jsonArray
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
            .jsonObject
        val call = a["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("{}", call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
    }

    // ── User message + system prompt ───────────────────────

    @Test fun systemPromptBecomesFirstMessage() {
        val body = build(request(systemPrompt = "You are a video editor."))
        val first = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("system", first["role"]!!.jsonPrimitive.content)
        assertEquals("You are a video editor.", first["content"]!!.jsonPrimitive.content)
    }

    @Test fun nullSystemPromptOmitsSystemMessage() {
        val body = build()
        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertNull(msgs.firstOrNull { it["role"]?.jsonPrimitive?.content == "system" })
    }

    @Test fun userMessageJoinsTextPartsByNewline() {
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = userMessage(),
                    parts = listOf(textPart("first line", "p1"), textPart("second line", "p2")),
                ),
            ),
        )
        val body = build(req)
        val u = body["messages"]!!.jsonArray
            .first { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
            .jsonObject
        assertEquals("first line\nsecond line", u["content"]!!.jsonPrimitive.content)
    }

    // ── End-to-end: multi-turn replay ──────────────────────

    @Test fun multiTurnConversationReplaysWithToolPairing() {
        // Pin: a realistic multi-turn replay produces user →
        // assistant(text+tool_call) → tool → assistant(text). The
        // role:tool message follows the assistant turn that
        // anchored its callId.
        val req = request(
            systemPrompt = "system",
            messages = listOf(
                MessageWithParts(message = userMessage(), parts = listOf(textPart("question?"))),
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        textPart("calling..."),
                        toolPart(
                            callId = "call-A",
                            toolId = "lookup",
                            state = ToolState.Completed(
                                input = buildJsonObject { put("q", JsonPrimitive("x")) },
                                outputForLlm = "found: 42",
                                data = JsonNull,
                            ),
                        ),
                    ),
                ),
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(textPart("answer is 42")),
                ),
            ),
        )
        val body = build(req)
        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
        val roles = msgs.map { it["role"]!!.jsonPrimitive.content }
        // Order: system / user / assistant / tool / assistant.
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant"), roles)
        // The tool message anchors to call-A.
        val toolMsg = msgs.first { it["role"]!!.jsonPrimitive.content == "tool" }
        assertEquals("call-A", toolMsg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("found: 42", toolMsg["content"]!!.jsonPrimitive.content)
    }
}
