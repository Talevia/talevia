package io.talevia.core.provider.anthropic

import io.talevia.core.CallId
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
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for the message-replay shape produced by
 * [buildAnthropicMessages] (called from
 * [buildAnthropicRequestBody]) — see
 * `core/src/commonMain/kotlin/io/talevia/core/provider/anthropic/
 * AnthropicRequestBody.kt:80`.
 *
 * Cycle 290 audit: existing [AnthropicProviderTest] (6 tests)
 * covers `body[system]` / `body[tools]` / core fields /
 * reasoning+snapshot replay — but ZERO tests for
 * `tool_use` / `tool_result` / `is_error` / `Cancelled` /
 * `Failed` / `Pending` shapes that the Anthropic API
 * **strictly enforces**: a malformed `tool_result` referencing
 * an absent `tool_use` is rejected at the request boundary.
 *
 * Same audit-pattern fallback as cycles 207-289.
 *
 * Drift surface protected:
 *   - **Drop the Failed/Cancelled tool_use replay** → paired
 *     tool_result has no matching tool_use_id, Anthropic
 *     returns 400 mid-stream. Drift surfaced as opaque
 *     provider error.
 *   - **Skip the Cancelled "cancelled: <msg>" prefix** → agent
 *     post-mortem reasoning loses the cancel-vs-error
 *     distinction.
 *   - **Drop the input ?: {} fallback for Failed.input=null**
 *     → schema-parse-error tool calls produce malformed
 *     tool_use blocks (input field missing).
 *   - **Drift to replay Pending tool calls** → tool_use blocks
 *     emitted before the agent decided arguments, breaking
 *     the streaming contract.
 *   - **Drift in the assistant-vs-user empty-text asymmetry**
 *     → empty user text gets dropped (or empty assistant text
 *     stops being skipped) — both sides break differently.
 *   - **Drift in anthropicThinkingBudget thinking block** →
 *     extended thinking budget silently disabled.
 *
 * Pins via runs of [buildAnthropicRequestBody] + JSON
 * inspection on the `messages` array.
 */
class AnthropicMessagesReplayTest {

    private val sid = SessionId("s1")
    private val mid = MessageId("m1")
    private val mid2 = MessageId("m2")
    private val now = Instant.fromEpochMilliseconds(0)
    private val anyModel = ModelRef("anthropic", "claude-opus-4-7")

    private fun userMessage(parts: List<Part>): MessageWithParts =
        MessageWithParts(
            message = Message.User(
                id = mid,
                sessionId = sid,
                createdAt = now,
                agent = "primary",
                model = anyModel,
            ),
            parts = parts,
        )

    private fun assistantMessage(parts: List<Part>): MessageWithParts =
        MessageWithParts(
            message = Message.Assistant(
                id = mid,
                sessionId = sid,
                createdAt = now,
                parentId = mid2,
                model = anyModel,
            ),
            parts = parts,
        )

    private fun textPart(text: String, partId: String = "pt"): Part.Text = Part.Text(
        id = PartId(partId), messageId = mid, sessionId = sid, createdAt = now,
        text = text,
    )

    private fun toolPart(callId: String, state: ToolState, toolId: String = "any_tool"): Part.Tool =
        Part.Tool(
            id = PartId("p-$callId"), messageId = mid, sessionId = sid, createdAt = now,
            callId = CallId(callId), toolId = toolId, state = state,
        )

    private fun runWithMessages(history: List<MessageWithParts>): JsonObject =
        buildAnthropicRequestBody(
            LlmRequest(model = anyModel, messages = history),
        )

    // ── User message: text content array ───────────────────

    @Test fun userMessageRendersAsRoleUserWithTextContent() {
        // Marquee shape pin: User Message → role=user with
        // content array of {type, text} blocks.
        val body = runWithMessages(
            listOf(userMessage(listOf(textPart("hello")))),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size, "single User message renders as one Anthropic message")
        val first = messages[0].jsonObject
        assertEquals("user", first["role"]!!.jsonPrimitive.content)
        val content = first["content"]!!.jsonArray
        assertEquals(1, content.size)
        val block = content[0].jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("hello", block["text"]!!.jsonPrimitive.content)
    }

    @Test fun userMessageWithMultipleTextPartsPreservesAll() {
        // Pin: multiple Part.Text on a User Message all
        // surface as separate type=text blocks.
        val body = runWithMessages(
            listOf(
                userMessage(
                    listOf(textPart("a", "p1"), textPart("b", "p2"), textPart("c", "p3")),
                ),
            ),
        )
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals(listOf("a", "b", "c"), content.map { it.jsonObject["text"]!!.jsonPrimitive.content })
    }

    // ── Assistant text empty-skip asymmetry ─────────────────

    @Test fun assistantEmptyTextBlocksAreSkipped() {
        // Marquee asymmetry pin: assistant skips empty text;
        // user does NOT (per source). Drift to symmetrise
        // either side breaks the wire format Anthropic
        // expects.
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(
                        textPart("", "empty"),
                        textPart("real content", "real"),
                        textPart("", "empty2"),
                    ),
                ),
            ),
        )
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(
            1,
            content.size,
            "assistant Empty Text parts MUST be skipped; only the non-empty one survives",
        )
        assertEquals("real content", content[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    // ── Tool replay: Pending vs Running/Completed/Failed/Cancelled

    @Test fun runningToolReplaysAsToolUseWithEchoedInput() {
        // Marquee tool_use shape pin: Running tool emits
        // assistant content block with type=tool_use, id,
        // name, input.
        val input = buildJsonObject { put("query", "lookup") }
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(toolPart("call-1", ToolState.Running(input = input), toolId = "lookup_thing")),
                ),
            ),
        )
        val messages = body["messages"]!!.jsonArray
        // Running has no tool_result emitted — only the
        // assistant tool_use message.
        assertEquals(1, messages.size, "Running tool: only assistant tool_use, no tool_result yet")
        val content = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        val block = content[0].jsonObject
        assertEquals("tool_use", block["type"]!!.jsonPrimitive.content)
        assertEquals("call-1", block["id"]!!.jsonPrimitive.content)
        assertEquals("lookup_thing", block["name"]!!.jsonPrimitive.content)
        assertEquals(input, block["input"], "Running input MUST echo verbatim")
    }

    @Test fun completedToolEmitsToolUseAndPairedToolResult() {
        // Marquee pair pin: Completed tool emits BOTH the
        // assistant tool_use AND a separate user message
        // with tool_result. Drift to drop either half breaks
        // the API contract.
        val input = buildJsonObject { put("k", "v") }
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(
                        toolPart(
                            "call-1",
                            ToolState.Completed(
                                input = input,
                                outputForLlm = "result text",
                                data = JsonObject(emptyMap()),
                            ),
                            toolId = "do_thing",
                        ),
                    ),
                ),
            ),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size, "Completed tool: assistant tool_use + user tool_result")

        // Block 0: assistant tool_use.
        val asst = messages[0].jsonObject
        assertEquals("assistant", asst["role"]!!.jsonPrimitive.content)
        val toolUse = asst["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("call-1", toolUse["id"]!!.jsonPrimitive.content)
        assertEquals("do_thing", toolUse["name"]!!.jsonPrimitive.content)
        assertEquals(input, toolUse["input"])

        // Block 1: user tool_result.
        val resultMsg = messages[1].jsonObject
        assertEquals("user", resultMsg["role"]!!.jsonPrimitive.content)
        val toolResult = resultMsg["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", toolResult["type"]!!.jsonPrimitive.content)
        assertEquals(
            "call-1",
            toolResult["tool_use_id"]!!.jsonPrimitive.content,
            "tool_use_id MUST equal the callId.value linking back to the tool_use",
        )
        assertEquals("result text", toolResult["content"]!!.jsonPrimitive.content)
        assertNull(toolResult["is_error"], "successful tool_result MUST NOT carry is_error")
    }

    @Test fun failedToolHasIsErrorTrueAndMessageContent() {
        // Marquee error pin: Failed tool_result carries
        // is_error=true AND the error message in content.
        val input = buildJsonObject { put("bad", true) }
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(
                        toolPart(
                            "call-fail",
                            ToolState.Failed(input = input, message = "boom — provider 500"),
                            toolId = "do_thing",
                        ),
                    ),
                ),
            ),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)

        // tool_use replays even on failure (paired-with-result invariant).
        val toolUse = messages[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("call-fail", toolUse["id"]!!.jsonPrimitive.content)
        assertEquals(input, toolUse["input"])

        // tool_result is_error=true.
        val toolResult = messages[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", toolResult["type"]!!.jsonPrimitive.content)
        assertEquals(
            true,
            toolResult["is_error"]!!.jsonPrimitive.content.toBoolean(),
            "Failed tool_result MUST set is_error=true",
        )
        assertEquals("boom — provider 500", toolResult["content"]!!.jsonPrimitive.content)
    }

    @Test fun failedToolWithNullInputFallsBackToEmptyObject() {
        // Marquee fallback pin: per source line 127, Failed
        // with input=null replays as input={}. Drift to
        // replay missing-input tool_use would emit malformed
        // wire shape.
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(
                        toolPart(
                            "call-pre-fail",
                            ToolState.Failed(input = null, message = "schema parse error"),
                        ),
                    ),
                ),
            ),
        )
        val toolUse = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(
            JsonObject(emptyMap()),
            toolUse["input"],
            "Failed.input=null MUST replay as input={} (well-formed tool_use)",
        )
    }

    @Test fun cancelledToolHasCancelledPrefixAndIsErrorTrue() {
        // Marquee cancel-vs-error pin: per source line 167,
        // Cancelled replays content as "cancelled: <msg>" +
        // is_error=true. Drift to share the Failed message
        // shape would lose the cancel-vs-error distinction.
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(
                        toolPart(
                            "call-cancel",
                            ToolState.Cancelled(
                                input = buildJsonObject { put("k", "v") },
                                message = "user pressed Ctrl-C",
                            ),
                        ),
                    ),
                ),
            ),
        )
        val toolResult = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(
            "cancelled: user pressed Ctrl-C",
            toolResult["content"]!!.jsonPrimitive.content,
            "Cancelled tool_result content MUST start with 'cancelled: ' prefix (drift to share Failed shape surfaces here)",
        )
        assertEquals(
            true,
            toolResult["is_error"]!!.jsonPrimitive.content.toBoolean(),
            "Cancelled tool_result MUST also set is_error=true (Anthropic accepts only the Failed-shape via this prefix)",
        )
    }

    @Test fun cancelledToolWithNullInputAlsoFallsBackToEmptyObject() {
        // Sister fallback pin to Failed: Cancelled.input=null
        // replays as input={} on the tool_use side.
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(
                        toolPart(
                            "call-cancel-pending",
                            ToolState.Cancelled(input = null, message = "x"),
                        ),
                    ),
                ),
            ),
        )
        val toolUse = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals(JsonObject(emptyMap()), toolUse["input"])
    }

    @Test fun pendingToolIsNotReplayedAsToolUse() {
        // Marquee skip-pending pin: per source line 132,
        // Pending tool calls aren't replayed (no callId
        // arguments yet). Drift to replay would emit
        // malformed tool_use blocks.
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(toolPart("call-pending", ToolState.Pending)),
                ),
            ),
        )
        val messages = body["messages"]!!.jsonArray
        // Single assistant message, but its content array
        // is empty (no text, no tool_use replayed).
        assertEquals(1, messages.size, "Pending: assistant emitted (no paired tool_result)")
        val content = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals(
            0,
            content.size,
            "Pending tool MUST NOT replay as a tool_use block",
        )
    }

    // ── Mixed assistant content (text + tool_use) ──────────

    @Test fun assistantWithTextAndToolBlocksKeepsBothInOrder() {
        // Pin: a single assistant turn with both Text
        // and Tool parts surfaces both kinds in the
        // assistant content array (order matters).
        val input = buildJsonObject { put("q", "z") }
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(
                        textPart("first thought", "p1"),
                        toolPart("c1", ToolState.Running(input = input)),
                    ),
                ),
            ),
        )
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("first thought", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("tool_use", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("c1", content[1].jsonObject["id"]!!.jsonPrimitive.content)
    }

    // ── thinking budget option ──────────────────────────────

    @Test fun anthropicThinkingBudgetEmitsThinkingBlock() {
        // Marquee extended-thinking pin: per source lines
        // 65-70, anthropicThinkingBudget renders a thinking
        // block with type=enabled + budget_tokens. Drift to
        // omit silently disables extended thinking.
        val body = buildAnthropicRequestBody(
            LlmRequest(
                model = anyModel,
                messages = emptyList(),
                options = ProviderOptions(anthropicThinkingBudget = 8000),
            ),
        )
        val thinking = body["thinking"]?.jsonObject
        assertNotNull(thinking, "anthropicThinkingBudget MUST emit a thinking block")
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals(
            8000,
            thinking["budget_tokens"]!!.jsonPrimitive.content.toInt(),
            "thinking.budget_tokens MUST echo the requested budget",
        )
    }

    @Test fun nullAnthropicThinkingBudgetOmitsThinkingBlock() {
        // Sister gate pin: when the budget is null (default),
        // the thinking block MUST NOT be emitted. Drift to
        // emit an empty thinking block would silently send
        // unintended thinking signals.
        val body = buildAnthropicRequestBody(
            LlmRequest(
                model = anyModel,
                messages = emptyList(),
                // options defaults to ProviderOptions()
                // which has anthropicThinkingBudget=null.
            ),
        )
        assertNull(
            body["thinking"],
            "null anthropicThinkingBudget MUST NOT emit a thinking block",
        )
    }

    // ── Multi-tool turn: all results in single user message

    @Test fun multipleToolsInOneTurnBatchInOneToolResultMessage() {
        // Pin: per source line 151, all tool_result blocks
        // for one assistant turn are batched into a SINGLE
        // user message (not one user message per tool).
        // Anthropic requires this batching shape.
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(
                        toolPart(
                            "c1",
                            ToolState.Completed(
                                input = JsonObject(emptyMap()),
                                outputForLlm = "result1",
                                data = JsonObject(emptyMap()),
                            ),
                        ),
                        toolPart(
                            "c2",
                            ToolState.Completed(
                                input = JsonObject(emptyMap()),
                                outputForLlm = "result2",
                                data = JsonObject(emptyMap()),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size, "2 tools → 1 assistant + 1 batched user (NOT 1+2)")
        // The single user message has TWO tool_result blocks.
        val userContent = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals(2, userContent.size, "both tool_result blocks batched in same user message")
        assertEquals("c1", userContent[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("c2", userContent[1].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test fun runningOnlyToolDoesNotEmitToolResultMessage() {
        // Pin: per source lines 146-150, only Completed /
        // Failed / Cancelled emit a tool_result block.
        // Running stays on the assistant side without a
        // paired user message. Drift to also emit a "pending
        // result" would break the API contract.
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(toolPart("c1", ToolState.Running(input = JsonObject(emptyMap())))),
                ),
            ),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(
            1,
            messages.size,
            "Running-only assistant turn MUST NOT emit a tool_result user message",
        )
    }

    // ── Cross-section invariants ────────────────────────────

    @Test fun successfulToolResultDoesNotCarryIsErrorField() {
        // Sister negative-pin: drift to ALWAYS set is_error
        // (even on success) would silently flag every
        // successful tool as an error.
        val body = runWithMessages(
            listOf(
                assistantMessage(
                    listOf(
                        toolPart(
                            "ok",
                            ToolState.Completed(
                                input = JsonObject(emptyMap()),
                                outputForLlm = "fine",
                                data = JsonObject(emptyMap()),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val toolResult = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertNull(
            toolResult["is_error"],
            "Completed tool_result MUST NOT carry is_error (drift to always-true would flag success as error)",
        )
        assertTrue(
            "is_error" !in toolResult,
            "is_error key MUST be entirely absent (not just null) on successful results",
        )
    }
}
