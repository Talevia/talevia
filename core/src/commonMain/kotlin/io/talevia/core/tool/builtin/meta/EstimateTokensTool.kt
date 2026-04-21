package io.talevia.core.tool.builtin.meta

import io.talevia.core.compaction.TokenEstimator
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Size a candidate text against the heuristic token budget — agent
 * self-introspection.
 *
 * Useful pre-flight: before composing a large prompt or pasting a long
 * artefact into the conversation, the agent can ask "will this blow
 * past the model's context window?". Pairs with
 * [ListToolsTool] / [ListProvidersTool] as capability-discovery
 * utilities the agent can reach for *inside* a turn.
 *
 * Wraps [io.talevia.core.compaction.TokenEstimator.forText], which is
 * the same approximation driving "should we compact?" decisions
 * (`~4 chars/token`, rounded up). This is a *heuristic* — the real
 * provider-specific tokenizer will disagree, usually modestly. Once a
 * turn completes, [io.talevia.core.session.TokenUsage] on the assistant
 * message carries the actual BPE/SentencePiece numbers reported by
 * Anthropic / OpenAI; prefer that whenever it's available.
 *
 * Read-only, `tool.read` (default ALLOW). Pure local computation — no
 * I/O, no allocations proportional to user-visible state.
 */
class EstimateTokensTool : Tool<EstimateTokensTool.Input, EstimateTokensTool.Output> {

    @Serializable data class Input(
        /** Text to size. Must be non-blank; enforced in [execute]. */
        val text: String,
    )

    @Serializable data class Output(
        /** Estimated token count via `TokenEstimator.forText`. */
        val tokens: Int,
        /** Raw character length of the input. */
        val characters: Int,
        /** `characters / tokens` — a sanity check on the ~4 ratio. */
        val approxCharsPerToken: Double,
    )

    override val id: String = "estimate_tokens"
    override val helpText: String =
        "Estimate how many tokens a piece of text will cost in the LLM context window. " +
            "Heuristic only (~4 chars/token, rounded up) — the provider's real tokenizer " +
            "(BPE for OpenAI, SentencePiece for Anthropic) will disagree modestly. For " +
            "the *actual* token counts reported after a turn, read `TokenUsage` on the " +
            "assistant message. Useful pre-flight: before pasting a long artefact or " +
            "composing a big prompt, ask whether it'll fit."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("tool.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") {
                put("type", "string")
                put("description", "Text to size. Must be non-empty.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("text"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.text.isNotBlank()) {
            "estimate_tokens: `text` must be a non-blank string."
        }
        val tokens = TokenEstimator.forText(input.text)
        val characters = input.text.length
        // `tokens` is guaranteed > 0 here: text is non-blank, so length >= 1,
        // and `(length + 3) / 4` is >= 1 for length >= 1. Still guard defensively.
        val ratio = if (tokens <= 0) 0.0 else characters.toDouble() / tokens.toDouble()
        val rounded = (ratio * 100).toLong() / 100.0
        val summary = "~$tokens token(s) for $characters char(s) (~$rounded chars/token — heuristic, not the provider tokenizer)."
        return ToolResult(
            title = "estimate tokens (~$tokens)",
            outputForLlm = summary,
            data = Output(
                tokens = tokens,
                characters = characters,
                approxCharsPerToken = ratio,
            ),
        )
    }
}
