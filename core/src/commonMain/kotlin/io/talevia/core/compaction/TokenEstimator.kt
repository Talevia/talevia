package io.talevia.core.compaction

import io.talevia.core.AssetId
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.Resolution
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.Part
import io.talevia.core.session.ToolState
import kotlinx.serialization.json.JsonElement

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

    /**
     * Tokens for an entire conversation snapshot. [resolveAsset] is consulted
     * for every [Part.Media] so the estimator can size image/video attachments
     * from their actual resolution; omit it to fall back to a coarse default.
     */
    fun forHistory(
        messages: List<MessageWithParts>,
        resolveAsset: (AssetId) -> MediaAsset? = { null },
    ): Int = messages.sumOf { mwp -> mwp.parts.sumOf { forPart(it, resolveAsset) } }

    fun forPart(part: Part, resolveAsset: (AssetId) -> MediaAsset? = { null }): Int = when (part) {
        is Part.Text -> forText(part.text)
        is Part.Reasoning -> forText(part.text)
        is Part.Tool -> when (val s = part.state) {
            is ToolState.Pending -> 16
            is ToolState.Running -> 24 + forJson(s.input)
            is ToolState.Completed -> 24 + forJson(s.input) + forText(s.outputForLlm) + forJson(s.data)
            is ToolState.Failed -> 24 + (s.input?.let(::forJson) ?: 0) + forText(s.message)
        }
        is Part.Media -> forMedia(resolveAsset(part.assetId)?.metadata?.resolution)
        is Part.TimelineSnapshot -> forText(part.timeline.toString())
        is Part.RenderProgress -> 8
        is Part.StepStart -> 4
        is Part.StepFinish -> 8
        is Part.Compaction -> forText(part.summary)
        is Part.Todos -> 16 + part.todos.sumOf { 8 + forText(it.content) }
    }

    /**
     * Image/video token cost approximation. Matches the Anthropic formula
     * `tokens = (width * height) / 750` (Claude's vision pricing), capped at
     * ~1.6k per image so a single 4K frame doesn't dominate the estimate —
     * both Anthropic and OpenAI downscale very large inputs server-side. When
     * no resolution is known we pick 1568 (Anthropic's "standard 1092x1092"
     * budget) since 32 was far too low for inline image attachments.
     */
    internal fun forMedia(resolution: Resolution?): Int {
        if (resolution == null) return DEFAULT_IMAGE_TOKENS
        val raw = (resolution.width.toLong() * resolution.height.toLong()) / 750L
        return raw.coerceIn(DEFAULT_IMAGE_TOKENS.toLong(), MAX_IMAGE_TOKENS.toLong()).toInt()
    }

    private const val DEFAULT_IMAGE_TOKENS = 1568
    private const val MAX_IMAGE_TOKENS = 6144
}
