package io.talevia.core.compaction

import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.Part
import io.talevia.core.session.ToolState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Heuristic token estimator. Real tokenisation is provider-specific (BPE/tiktoken
 * for OpenAI, SentencePiece for Anthropic) and not worth shipping in commonMain.
 *
 * Approximation: ~4 characters per token for English text; we round up. Numbers and
 * structured JSON are denser, so we add a small fudge factor.
 *
 * The estimator is only used for "should we compact?" decisions — the LLM provider
 * returns the *real* token usage in [io.talevia.core.session.TokenUsage]; consumers
 * should prefer that signal whenever it is available.
 */
object TokenEstimator {
    /** Tokens for a raw text string. */
    fun forText(text: String): Int = ((text.length + 3) / 4)

    /** Tokens for a serialised JSON element (rough). */
    fun forJson(element: JsonElement): Int = forText(element.toString())

    /** Tokens for an entire conversation snapshot. */
    fun forHistory(messages: List<MessageWithParts>): Int =
        messages.sumOf { mwp -> mwp.parts.sumOf(::forPart) }

    fun forPart(part: Part): Int = when (part) {
        is Part.Text -> forText(part.text)
        is Part.Reasoning -> forText(part.text)
        is Part.Tool -> when (val s = part.state) {
            is ToolState.Pending -> 16
            is ToolState.Running -> 24 + forJson(s.input)
            is ToolState.Completed -> 24 + forJson(s.input) + forText(s.outputForLlm) + forJson(s.data)
            is ToolState.Failed -> 24 + (s.input?.let(::forJson) ?: 0) + forText(s.message)
        }
        is Part.Media -> 32  // placeholder — real cost depends on whether the media is sent inline
        is Part.TimelineSnapshot -> forText(part.timeline.toString())
        is Part.RenderProgress -> 8
        is Part.StepStart -> 4
        is Part.StepFinish -> 8
        is Part.Compaction -> forText(part.summary)
    }
}
