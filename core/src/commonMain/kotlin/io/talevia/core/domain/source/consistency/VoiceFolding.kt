package io.talevia.core.domain.source.consistency

import io.talevia.core.domain.source.SourceNode

/**
 * Result of folding consistency bindings into a TTS / voice-clone call (VISION §5.5
 * audio lane). Separate from [FoldedPrompt] because the audio modality only consumes
 * character voice ids — style bibles, brand palettes, and character visual
 * descriptions are all irrelevant for `synthesize_speech`, so pulling them through
 * the visual-prompt fold would muddy what actually affected the generation.
 *
 * @property voiceId Resolved voice id (from a bound [CharacterRefBody.voiceId]), or
 *   `null` when no binding dictates a voice. The caller's explicit `voice` input is
 *   used when this is `null`.
 * @property appliedNodeIds The character_ref node ids whose voiceId was resolved —
 *   the [io.talevia.core.domain.Clip.sourceBinding] candidates if the tool writes a
 *   lockfile entry. Empty when no character binding had a voiceId; the TTS call is
 *   then semantically un-bound (like a hand-picked voice).
 */
data class FoldedVoice(
    val voiceId: String?,
    val appliedNodeIds: List<String>,
)

/**
 * Fold consistency bindings into a voice pick. Scans [nodes] for character_refs
 * with a non-null [CharacterRefBody.voiceId]; returns the single picked voice or an
 * error when multiple characters have voiceIds (ambiguous: which speaker?).
 *
 * Style bibles and brand palettes are silently ignored — TTS has no style axis
 * to bind them to. Character_refs without a voiceId are also skipped; the caller
 * may have bound the character for semantic clarity even though the voice is
 * picked by other means (e.g. a placeholder TTS before a human take).
 *
 * Throws on ambiguity rather than picking one, because the alternative ("first
 * wins") leads to silent regressions when a caller adds a second bound character
 * — the voice would change without any explicit opt-in. Failing loudly forces the
 * caller to disambiguate (bind only the speaker).
 */
fun foldVoice(nodes: List<SourceNode>): FoldedVoice {
    if (nodes.isEmpty()) return FoldedVoice(voiceId = null, appliedNodeIds = emptyList())
    val picks = nodes.mapNotNull { node ->
        val voice = node.asCharacterRef()?.voiceId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        node.id.value to voice
    }
    return when (picks.size) {
        0 -> FoldedVoice(voiceId = null, appliedNodeIds = emptyList())
        1 -> FoldedVoice(voiceId = picks[0].second, appliedNodeIds = listOf(picks[0].first))
        else -> error(
            "Ambiguous voice bindings: multiple character_refs have voiceIds — " +
                picks.joinToString { "${it.first}=${it.second}" } +
                ". Bind only the speaker's character_ref to synthesize_speech.",
        )
    }
}
