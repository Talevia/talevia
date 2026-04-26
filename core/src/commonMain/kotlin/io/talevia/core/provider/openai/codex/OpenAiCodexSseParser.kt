package io.talevia.core.provider.openai.codex

import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.TokenUsage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Stateful translator from ChatGPT-backend Responses API SSE events to
 * normalised [LlmEvent]s. Each event JSON line is fed into [process]; the
 * parser tracks per-item partIds + tool-call accumulators across the stream.
 *
 * Events recognised (others ignored — the codex backend emits a long tail of
 * server-internal telemetry events we don't care about):
 *  - `response.created`                       → no LlmEvent (StepStart already sent)
 *  - `response.output_item.added`             → TextStart / ReasoningStart / ToolCallStart
 *  - `response.output_text.delta`             → TextDelta
 *  - `response.reasoning_text.delta`          → ReasoningDelta
 *  - `response.function_call_arguments.delta` → ToolCallInputDelta
 *  - `response.output_item.done`              → TextEnd / ReasoningEnd / ToolCallReady
 *  - `response.completed`                     → captured into [terminal] (StepFinish at flush)
 *  - `response.failed` / `response.incomplete`→ captured into [terminalError]
 */
@OptIn(ExperimentalUuidApi::class)
internal class OpenAiCodexSseParser {

    private data class ToolBuf(
        val partId: PartId,
        var callId: CallId? = null,
        var name: String? = null,
        val args: StringBuilder = StringBuilder(),
    )

    private val partIdByItemId = mutableMapOf<String, PartId>()
    private val toolBufByItemId = mutableMapOf<String, ToolBuf>()
    private var seenAnyToolCall = false

    /** Set when `response.completed` arrives. Drives [LlmEvent.StepFinish.usage]. */
    var terminalUsage: TokenUsage = TokenUsage.ZERO
        private set

    /** Set when `response.completed.end_turn` is observed. */
    var endTurn: Boolean? = null
        private set

    /** When non-null, the stream errored (response.failed / response.incomplete). */
    var terminalError: LlmEvent.Error? = null
        private set

    /** Translates [event] into zero or more [LlmEvent]s and emits them via [send]. */
    suspend fun process(event: JsonObject, send: suspend (LlmEvent) -> Unit) {
        when (val kind = (event["type"] as? JsonPrimitive)?.contentOrNull) {
            null -> Unit
            "response.created" -> Unit
            "response.output_item.added" -> handleItemAdded(event, send)
            "response.output_text.delta" -> handleTextDelta(event, send)
            "response.reasoning_text.delta" -> handleReasoningDelta(event, send)
            "response.function_call_arguments.delta" -> handleArgsDelta(event, send)
            "response.output_item.done" -> handleItemDone(event, send)
            "response.completed" -> handleCompleted(event)
            "response.failed" -> handleFailed(event)
            "response.incomplete" -> handleIncomplete(event)
            else -> Unit // ignore unrecognised — codex emits many server-internal telemetry events
        }
    }

    /** Final mapping after the SSE stream closes. Returns the resolved finish reason. */
    fun resolveFinish(): FinishReason {
        if (terminalError != null) return FinishReason.ERROR
        if (seenAnyToolCall) return FinishReason.TOOL_CALLS
        if (endTurn == true) return FinishReason.END_TURN
        return FinishReason.STOP
    }

    private suspend fun handleItemAdded(event: JsonObject, send: suspend (LlmEvent) -> Unit) {
        val item = event["item"] as? JsonObject ?: return
        val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return
        val partId = partIdByItemId.getOrPut(itemId) { PartId(Uuid.random().toString()) }
        when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
            "message" -> {
                val role = (item["role"] as? JsonPrimitive)?.contentOrNull
                if (role == "assistant") send(LlmEvent.TextStart(partId))
            }
            "reasoning" -> send(LlmEvent.ReasoningStart(partId))
            "function_call" -> {
                seenAnyToolCall = true
                val name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: "unknown"
                val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull
                    ?: (item["id"] as? JsonPrimitive)?.contentOrNull
                    ?: return
                val buf = toolBufByItemId.getOrPut(itemId) { ToolBuf(partId, CallId(callId), name) }
                buf.callId = CallId(callId)
                buf.name = name
                send(LlmEvent.ToolCallStart(partId, CallId(callId), name))
            }
            // custom_tool_call / tool_search_call / web_search_call etc — server-side tools we
            // don't surface; ignore silently.
        }
    }

    private suspend fun handleTextDelta(event: JsonObject, send: suspend (LlmEvent) -> Unit) {
        val itemId = (event["item_id"] as? JsonPrimitive)?.contentOrNull ?: return
        val delta = (event["delta"] as? JsonPrimitive)?.contentOrNull ?: return
        if (delta.isEmpty()) return
        val partId = partIdByItemId[itemId] ?: return
        send(LlmEvent.TextDelta(partId, delta))
    }

    private suspend fun handleReasoningDelta(event: JsonObject, send: suspend (LlmEvent) -> Unit) {
        val itemId = (event["item_id"] as? JsonPrimitive)?.contentOrNull ?: return
        val delta = (event["delta"] as? JsonPrimitive)?.contentOrNull ?: return
        if (delta.isEmpty()) return
        val partId = partIdByItemId[itemId] ?: return
        send(LlmEvent.ReasoningDelta(partId, delta))
    }

    private suspend fun handleArgsDelta(event: JsonObject, send: suspend (LlmEvent) -> Unit) {
        val itemId = (event["item_id"] as? JsonPrimitive)?.contentOrNull ?: return
        val delta = (event["delta"] as? JsonPrimitive)?.contentOrNull ?: return
        if (delta.isEmpty()) return
        val buf = toolBufByItemId[itemId] ?: return
        val callId = buf.callId ?: return
        buf.args.append(delta)
        send(LlmEvent.ToolCallInputDelta(buf.partId, callId, delta))
    }

    private suspend fun handleItemDone(event: JsonObject, send: suspend (LlmEvent) -> Unit) {
        val item = event["item"] as? JsonObject ?: return
        val itemId = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return
        when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
            "message" -> {
                val partId = partIdByItemId[itemId] ?: return
                val role = (item["role"] as? JsonPrimitive)?.contentOrNull
                if (role != "assistant") return
                val content = item["content"] as? kotlinx.serialization.json.JsonArray
                val text = content
                    ?.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
                    ?.joinToString("") { it.contentOrNull.orEmpty() }
                    ?: ""
                send(LlmEvent.TextEnd(partId, text))
            }
            "reasoning" -> {
                val partId = partIdByItemId[itemId] ?: return
                // Final reasoning text is in item.summary[*].text or item.content[*].text
                val text = (item["content"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
                    ?.joinToString("") { it.contentOrNull.orEmpty() }
                    ?: ""
                send(LlmEvent.ReasoningEnd(partId, text))
            }
            "function_call" -> {
                val buf = toolBufByItemId[itemId] ?: return
                val callId = buf.callId ?: return
                val finalArgs = (item["arguments"] as? JsonPrimitive)?.contentOrNull ?: buf.args.toString()
                val parsed: JsonElement = if (finalArgs.isBlank()) {
                    JsonObject(emptyMap())
                } else {
                    runCatching { io.talevia.core.JsonConfig.default.parseToJsonElement(finalArgs) }
                        .getOrElse { JsonObject(emptyMap()) }
                }
                send(LlmEvent.ToolCallReady(buf.partId, callId, buf.name ?: "unknown", parsed))
            }
        }
    }

    private fun handleCompleted(event: JsonObject) {
        val response = event["response"] as? JsonObject ?: return
        endTurn = (response["end_turn"] as? JsonPrimitive)?.let { runCatching { it.contentOrNull?.toBoolean() }.getOrNull() }
        val usage = response["usage"] as? JsonObject ?: return
        val input = usage["input_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val output = usage["output_tokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val cached = (usage["input_tokens_details"] as? JsonObject)
            ?.get("cached_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        val reasoning = (usage["output_tokens_details"] as? JsonObject)
            ?.get("reasoning_tokens")?.jsonPrimitive?.longOrNull ?: 0L
        terminalUsage = TokenUsage(input = input, output = output, reasoning = reasoning, cacheRead = cached)
    }

    private fun handleFailed(event: JsonObject) {
        val response = event["response"] as? JsonObject
        val error = response?.get("error") as? JsonObject
        val code = (error?.get("code") as? JsonPrimitive)?.contentOrNull
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: "openai-codex response.failed without details"
        // rate_limit_exceeded / context_length_exceeded / quota_exceeded are NOT retriable; everything
        // else with a 5xx-ish flavour is.
        val retriable = code == null || code !in NON_RETRIABLE_CODES
        terminalError = LlmEvent.Error(
            message = "openai-codex stream failed: ${listOfNotNull(code, message).joinToString(": ")}",
            retriable = retriable,
        )
    }

    private fun handleIncomplete(event: JsonObject) {
        val response = event["response"] as? JsonObject
        val reason = (response?.get("incomplete_details") as? JsonObject)
            ?.get("reason")?.jsonPrimitive?.contentOrNull
            ?: "unknown"
        terminalError = LlmEvent.Error(
            message = "openai-codex incomplete response: $reason",
            retriable = true,
        )
    }

    @Suppress("unused")
    private fun JsonObject.intAt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private companion object {
        // Codex-side classification of permanent failures — see codex-rs/codex-api/src/sse/responses.rs:330-360
        private val NON_RETRIABLE_CODES = setOf(
            "context_length_exceeded",
            "insufficient_quota",
            "invalid_prompt",
            "cyber_policy",
        )
    }
}
