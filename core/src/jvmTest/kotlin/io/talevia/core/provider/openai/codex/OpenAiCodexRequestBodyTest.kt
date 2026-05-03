package io.talevia.core.provider.openai.codex

import io.talevia.core.CallId
import io.talevia.core.JsonConfig
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.SessionId
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Direct tests for [buildResponsesApiBody] —
 * `core/provider/openai/codex/OpenAiCodexRequestBody.kt`. Builds the
 * Responses API request body for the ChatGPT Codex backend. Cycle 217
 * audit: 141 LOC, 0 direct test refs verified across both `commonTest`
 * and `jvmTest`.
 *
 * Same audit-pattern fallback as cycles 207-216. This is a marquee
 * provider-neutral wire-shape contract: drift would silently alter
 * the JSON shape sent to the Codex backend, breaking real provider
 * dispatch in subtle ways (e.g. an empty assistant message with no
 * text would crash the model; a missing `prompt_cache_key` would
 * disable caching across turns; a wrong `tool_choice` would cause
 * the model to over- or under-call tools).
 *
 * Six correctness contracts pinned:
 *
 *  1. **Top-level body shape.** Required keys: `model`,
 *     `instructions`, `input`, `tools`, `tool_choice`,
 *     `parallel_tool_calls`, `store`, `stream`, `include`,
 *     `prompt_cache_key`, `reasoning`. Drift in any of these would
 *     either break the wire format or change behavior (`store=true`
 *     would persist responses server-side; `parallel_tool_calls=true`
 *     would change agent loop semantics).
 *
 *  2. **`instructions` carries systemPrompt or empty string.** Per
 *     impl `request.systemPrompt.orEmpty()`. Drift to `null` would
 *     fail Codex API validation (top-level `instructions` is
 *     required). Drift to dropping the user's actual systemPrompt
 *     would silently break agent personality.
 *
 *  3. **`reasoning.effort` whitelist + lenient parse.** Only the 6
 *     accepted values (`none`, `minimal`, `low`, `medium`, `high`,
 *     `xhigh`) emit `effort`; unknown / blank → omitted (backend
 *     applies per-model default). Trim + lowercase is applied.
 *     Drift to "any string OK" would surface as a 400 from Codex
 *     when the agent passes through user input verbatim.
 *
 *  4. **`reasoning.summary` is always "auto".** Per kdoc — keeps
 *     reasoning_text events streaming. Drift to "off" would silently
 *     break the SseParser's reasoning lane.
 *
 *  5. **Pure-tool assistant turn omits the message item.** Marquee
 *     correctness pin: assistant with only Tool parts (no Text)
 *     emits NO `{type=message, role=assistant}` block. Drift to
 *     "always emit" would surface as Codex API rejecting empty
 *     content arrays.
 *
 *  6. **Tool state mapping (5 variants).** Running → input + null
 *     output (no function_call_output emitted). Completed → input
 *     + outputForLlm (both blocks emitted). Failed → input ?: empty
 *     + message-as-output. Cancelled → input ?: empty +
 *     "cancelled: <msg>". Pending → SKIPPED entirely (no
 *     function_call, no output).
 *
 * Plus shape pins: `prompt_cache_key == sessionId`; tool spec
 * shape (`{type=function, name, description, strict=false,
 * parameters}`); user message text joined by newlines; multiple
 * Text parts collapsed in user/assistant content.
 */
class OpenAiCodexRequestBodyTest {

    private val json = JsonConfig.default
    private val baseTime: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun userMessage(id: String = "u1"): Message.User = Message.User(
        id = MessageId(id),
        sessionId = SessionId("s"),
        createdAt = baseTime,
        agent = "test",
        model = ModelRef("openai", "gpt-5"),
    )

    private fun assistantMessage(id: String = "a1"): Message.Assistant = Message.Assistant(
        id = MessageId(id),
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
    ): LlmRequest = LlmRequest(
        model = ModelRef("openai", modelId),
        messages = messages,
        tools = tools,
        systemPrompt = systemPrompt,
        options = options,
    )

    private fun build(
        request: LlmRequest = request(),
        sessionId: String = "session-99",
    ): JsonObject = buildResponsesApiBody(request, sessionId, json)

    // ── 1. Top-level body shape ─────────────────────────────

    @Test fun bodyContainsAllRequiredTopLevelKeys() {
        val body = build()
        val expectedKeys = setOf(
            "model", "instructions", "input", "tools", "tool_choice",
            "parallel_tool_calls", "store", "stream", "include",
            "prompt_cache_key", "reasoning",
        )
        assertEquals(
            expectedKeys,
            body.keys,
            "top-level keys must exactly match Codex Responses API spec",
        )
    }

    @Test fun bodyFixedFlagsAlwaysPresent() {
        val body = build()
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(false, body["parallel_tool_calls"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["store"]!!.jsonPrimitive.boolean, "store=false (no server-side persistence)")
        assertEquals(true, body["stream"]!!.jsonPrimitive.boolean, "stream=true always")
        assertEquals(0, body["include"]!!.jsonArray.size, "include[] empty (opt-in fields)")
    }

    @Test fun bodyModelEqualsRequestModelId() {
        val body = build(request(modelId = "gpt-5.4"))
        assertEquals("gpt-5.4", body["model"]!!.jsonPrimitive.content)
    }

    @Test fun bodyPromptCacheKeyEqualsSessionId() {
        // Pin: prompt_cache_key wires session-scoped caching. Drift
        // to a static key would conflate sessions; drift to a per-
        // turn key would defeat caching entirely.
        val body = build(sessionId = "session-CUSTOM-99")
        assertEquals(
            "session-CUSTOM-99",
            body["prompt_cache_key"]!!.jsonPrimitive.content,
        )
    }

    // ── 2. instructions carries systemPrompt or empty ───────

    @Test fun instructionsCarriesSystemPromptVerbatim() {
        val body = build(request(systemPrompt = "You are a video editor."))
        assertEquals(
            "You are a video editor.",
            body["instructions"]!!.jsonPrimitive.content,
        )
    }

    @Test fun instructionsIsEmptyStringWhenSystemPromptNull() {
        // Pin: per impl `request.systemPrompt.orEmpty()`. Drift to
        // omitting the field or sending JsonNull would fail Codex
        // API validation — `instructions` is a required top-level
        // string.
        val body = build(request(systemPrompt = null))
        val instr = body["instructions"]
        assertNotNull(instr, "instructions key must be present")
        assertEquals("", instr.jsonPrimitive.content)
    }

    // ── 3. reasoning.effort whitelist ───────────────────────

    @Test fun reasoningEffortAcceptedValues() {
        // Marquee whitelist pin: per `normalizeEffort`, the 6
        // accepted values pass through unchanged.
        for (effort in listOf("none", "minimal", "low", "medium", "high", "xhigh")) {
            val body = build(request(options = ProviderOptions(openaiReasoningEffort = effort)))
            val r = body["reasoning"]!!.jsonObject
            assertEquals(
                effort,
                r["effort"]!!.jsonPrimitive.content,
                "accepted value '$effort' must pass through verbatim",
            )
        }
    }

    @Test fun reasoningEffortLowercaseAndTrimmed() {
        // Pin: per impl `raw?.trim()?.lowercase()`. Drift to "case-
        // strict" would force the agent to pre-normalize.
        for (variant in listOf("HIGH", "  high  ", "\thigh\n", "High")) {
            val body = build(request(options = ProviderOptions(openaiReasoningEffort = variant)))
            val r = body["reasoning"]!!.jsonObject
            assertEquals(
                "high",
                r["effort"]!!.jsonPrimitive.content,
                "variant '$variant' must normalize to 'high'",
            )
        }
    }

    @Test fun reasoningEffortUnknownValueOmitted() {
        // Pin: unknown values map to null → effort field omitted
        // (NOT JsonNull). The backend then applies its per-model
        // default. Drift to "send unknown verbatim" would surface
        // as a Codex 400.
        for (bad in listOf("frobnicate", "ultra", "")) {
            val body = build(request(options = ProviderOptions(openaiReasoningEffort = bad)))
            val r = body["reasoning"]!!.jsonObject
            assertFalse(
                "effort" in r.keys,
                "effort must be ABSENT for unknown '$bad' (got keys=${r.keys})",
            )
        }
    }

    @Test fun reasoningEffortNullOmitted() {
        val body = build(request(options = ProviderOptions(openaiReasoningEffort = null)))
        val r = body["reasoning"]!!.jsonObject
        assertFalse("effort" in r.keys, "effort absent when null")
    }

    // ── 4. reasoning.summary always "auto" ──────────────────

    @Test fun reasoningSummaryAlwaysAuto() {
        // Pin: per kdoc — keeps reasoning_text events streaming.
        // Drift to "off" or omitting would silently break the
        // SseParser's reasoning lane.
        for (effort in listOf(null, "high", "frobnicate")) {
            val body = build(request(options = ProviderOptions(openaiReasoningEffort = effort)))
            val r = body["reasoning"]!!.jsonObject
            assertEquals("auto", r["summary"]!!.jsonPrimitive.content)
        }
    }

    // ── 5. Pure-tool assistant turn omits message item ──────

    @Test fun pureToolAssistantTurnOmitsMessageBlock() {
        // Marquee correctness pin: an assistant turn with ONLY
        // Tool parts (no Text) must NOT emit a `{type=message,
        // role=assistant, content=[]}` block. Drift would surface
        // as Codex API rejecting empty content arrays.
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = userMessage(),
                    parts = listOf(textPart("hello")),
                ),
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "call-1",
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
        val input = body["input"]!!.jsonArray
        // Find any assistant message block — must be zero.
        val assistantMessages = input
            .map { it.jsonObject }
            .filter {
                it["type"]?.jsonPrimitive?.content == "message" &&
                    it["role"]?.jsonPrimitive?.content == "assistant"
            }
        assertEquals(
            0,
            assistantMessages.size,
            "pure-tool assistant turn must NOT emit a message item; got: $assistantMessages",
        )
    }

    @Test fun assistantTurnWithTextEmitsMessageBlockBeforeToolCalls() {
        // Pin: when assistant has BOTH text AND tools, the message
        // item appears FIRST in the input array, then function_call
        // blocks. Drift to "tools first" would mis-order the
        // conversation history.
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        textPart("Calling the tool now."),
                        toolPart(
                            callId = "call-1",
                            toolId = "echo",
                            state = ToolState.Completed(
                                input = buildJsonObject { put("x", JsonPrimitive(1)) },
                                outputForLlm = "ok",
                                data = JsonNull,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val input = body["input"]!!.jsonArray
        // First item is the assistant message; second is the function_call.
        assertEquals("message", input[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("assistant", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("function_call", input[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    // ── 6. Tool state mapping ───────────────────────────────

    @Test fun toolStateRunningEmitsFunctionCallButNoOutput() {
        // Pin: Running → input present, output null → only the
        // function_call block emits, no function_call_output.
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
        val input = body["input"]!!.jsonArray
        val items = input.map { it.jsonObject }
        // function_call present.
        val callBlock = items.firstOrNull {
            it["type"]?.jsonPrimitive?.content == "function_call"
        }
        assertNotNull(callBlock, "function_call expected for Running state")
        assertEquals("long_op", callBlock["name"]!!.jsonPrimitive.content)
        // No function_call_output.
        val outputBlock = items.firstOrNull {
            it["type"]?.jsonPrimitive?.content == "function_call_output"
        }
        assertNull(outputBlock, "Running state must NOT emit function_call_output")
    }

    @Test fun toolStateCompletedEmitsBothBlocks() {
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "done-1",
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
        val items = body["input"]!!.jsonArray.map { it.jsonObject }
        val call = items.first { it["type"]?.jsonPrimitive?.content == "function_call" }
        val out = items.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("done-1", call["call_id"]!!.jsonPrimitive.content)
        assertEquals("echo", call["name"]!!.jsonPrimitive.content)
        assertEquals("done-1", out["call_id"]!!.jsonPrimitive.content)
        assertEquals("echoed: hi", out["output"]!!.jsonPrimitive.content)
    }

    @Test fun toolStateFailedEmitsBothBlocksWithMessageAsOutput() {
        // Pin: Failed → input ?: empty, output = message string.
        // Drift to "skip output" or "wrap in error envelope" would
        // change how the agent loop sees historical failures.
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "fail-1",
                            toolId = "broken_tool",
                            state = ToolState.Failed(
                                input = buildJsonObject { put("k", JsonPrimitive(1)) },
                                message = "tool dispatch raised IllegalStateException",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val items = body["input"]!!.jsonArray.map { it.jsonObject }
        val out = items.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals(
            "tool dispatch raised IllegalStateException",
            out["output"]!!.jsonPrimitive.content,
            "Failed.message becomes the output verbatim",
        )
    }

    @Test fun toolStateFailedWithNullInputDefaultsToEmptyObject() {
        // Pin: Failed.input may be null; arguments JSON should be
        // an empty object string `"{}"` (NOT null literal). Drift
        // would crash JSON.parse downstream.
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        toolPart(
                            callId = "fail-2",
                            toolId = "early_fail",
                            state = ToolState.Failed(input = null, message = "auth failed"),
                        ),
                    ),
                ),
            ),
        )
        val body = build(req)
        val items = body["input"]!!.jsonArray.map { it.jsonObject }
        val call = items.first { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals("{}", call["arguments"]!!.jsonPrimitive.content)
    }

    @Test fun toolStateCancelledPrependsCancelledLabel() {
        // Pin: per impl `"cancelled: ${s.message}"`. Drift to "raw
        // message" would lose the distinguishing label that
        // separates cancelled from failed.
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
        val items = body["input"]!!.jsonArray.map { it.jsonObject }
        val out = items.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals(
            "cancelled: user pressed Ctrl-C",
            out["output"]!!.jsonPrimitive.content,
        )
    }

    @Test fun toolStatePendingSkippedEntirely() {
        // Marquee Pending-skip pin: per impl `is Pending -> continue`
        // — no function_call AND no function_call_output emitted.
        // Drift to "emit anyway" would mislead the model into
        // thinking the tool was invoked when it wasn't (input
        // hadn't streamed yet).
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
        val items = body["input"]!!.jsonArray
        // No function_call and no function_call_output for the
        // pending tool.
        assertEquals(
            0,
            items.size,
            "Pending tool produces ZERO items in the input array; got: $items",
        )
    }

    // ── Tool spec shape ─────────────────────────────────────

    @Test fun toolsArrayShape() {
        // Pin: each tool spec maps to {type=function, name,
        // description, strict=false, parameters}. Drift to wrapping
        // in `function: { ... }` (Chat Completions style) would
        // fail Codex API.
        val schema = buildJsonObject { put("type", JsonPrimitive("object")) }
        val tools = listOf(
            ToolSpec(id = "echo", helpText = "Repeat the input.", inputSchema = schema),
        )
        val body = build(request(tools = tools))
        val toolsArr = body["tools"]!!.jsonArray
        assertEquals(1, toolsArr.size)
        val tool = toolsArr[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("echo", tool["name"]!!.jsonPrimitive.content)
        assertEquals("Repeat the input.", tool["description"]!!.jsonPrimitive.content)
        assertEquals(false, tool["strict"]!!.jsonPrimitive.boolean)
        assertEquals(schema, tool["parameters"])
    }

    @Test fun emptyToolsArrayWhenNoneRegistered() {
        val body = build()
        assertEquals(0, body["tools"]!!.jsonArray.size)
    }

    // ── User content joining ────────────────────────────────

    @Test fun userMessageMultipleTextPartsJoinedByNewline() {
        // Pin: per impl `joinToString("\n")`. Drift to space-join
        // or first-only would lose context.
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = userMessage(),
                    parts = listOf(
                        textPart("first line", id = "p1"),
                        textPart("second line", id = "p2"),
                    ),
                ),
            ),
        )
        val body = build(req)
        val items = body["input"]!!.jsonArray.map { it.jsonObject }
        val msg = items.first {
            it["type"]?.jsonPrimitive?.content == "message" &&
                it["role"]?.jsonPrimitive?.content == "user"
        }
        val content = msg["content"]!!.jsonArray
        assertEquals(1, content.size, "single content block with joined text")
        val textBlock = content[0].jsonObject
        assertEquals("input_text", textBlock["type"]!!.jsonPrimitive.content)
        assertEquals("first line\nsecond line", textBlock["text"]!!.jsonPrimitive.content)
    }

    @Test fun assistantMessageMultipleTextPartsJoinedByNewline() {
        val req = request(
            messages = listOf(
                MessageWithParts(
                    message = assistantMessage(),
                    parts = listOf(
                        textPart("para 1", id = "p1"),
                        textPart("para 2", id = "p2"),
                    ),
                ),
            ),
        )
        val body = build(req)
        val items = body["input"]!!.jsonArray.map { it.jsonObject }
        val msg = items.first {
            it["type"]?.jsonPrimitive?.content == "message" &&
                it["role"]?.jsonPrimitive?.content == "assistant"
        }
        val content = msg["content"]!!.jsonArray
        val textBlock = content[0].jsonObject
        assertEquals("output_text", textBlock["type"]!!.jsonPrimitive.content)
        assertEquals("para 1\npara 2", textBlock["text"]!!.jsonPrimitive.content)
    }

    // ── End-to-end shape: empty request ─────────────────────

    @Test fun emptyRequestProducesValidMinimalBody() {
        // Pin: zero messages + zero tools + null systemPrompt
        // produces a structurally valid body with no input items.
        val body = build()
        assertEquals(0, body["input"]!!.jsonArray.size, "empty messages → empty input array")
        assertEquals(0, body["tools"]!!.jsonArray.size, "empty tools → empty tools array")
        assertEquals("", body["instructions"]!!.jsonPrimitive.content)
    }
}
