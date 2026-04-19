package io.talevia.core.provider

import io.talevia.core.JsonConfig
import io.talevia.core.domain.Timeline
import io.talevia.core.session.Part
import kotlinx.serialization.json.Json

/**
 * Helpers shared by provider implementations when replaying session [Part]s as
 * LLM-visible context. Each provider handles its native content types
 * (Anthropic text/thinking blocks, OpenAI message content), but the text
 * encoding of reasoning and timeline snapshots is common across providers.
 *
 * Why render reasoning as plain text instead of a provider-native `thinking`
 * block: our [Part.Reasoning] doesn't capture the provider-signed continuation
 * signature. Anthropic in particular rejects `thinking` replay without it, so
 * we surface reasoning as context-only text.
 */
internal object ReplayFormatting {
    private val json: Json = JsonConfig.default

    /** Wrap the [reasoning] text in a recognisable tag so the model can tell */
    /** it's its own prior reasoning rather than a fresh user message. */
    fun formatReasoning(reasoning: Part.Reasoning): String =
        "<prior_reasoning>${reasoning.text}</prior_reasoning>"

    /**
     * Serialize a [Part.TimelineSnapshot] as a self-describing text block. The
     * `producedByCallId` lets the model correlate a snapshot with the tool
     * that produced it — important for reasoning about revert targets.
     */
    fun formatTimelineSnapshot(snapshot: Part.TimelineSnapshot): String {
        val header = buildString {
            append("<timeline_snapshot id=\"")
            append(snapshot.id.value)
            append('"')
            snapshot.producedByCallId?.let { append(" produced_by=\"").append(it.value).append('"') }
            append('>')
        }
        val body = json.encodeToString(Timeline.serializer(), snapshot.timeline)
        return "$header\n$body\n</timeline_snapshot>"
    }
}
