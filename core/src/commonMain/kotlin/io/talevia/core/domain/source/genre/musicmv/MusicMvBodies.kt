package io.talevia.core.domain.source.genre.musicmv

import io.talevia.core.AssetId
import kotlinx.serialization.Serializable

/**
 * Typed payload bodies for the Music-MV genre's
 * [io.talevia.core.domain.source.SourceNode]s.
 *
 * Each body is a regular [kotlinx.serialization.Serializable] data class — it is
 * encoded into a [kotlinx.serialization.json.JsonElement] via the canonical
 * [io.talevia.core.JsonConfig.default] at write time and decoded on read by
 * [MusicMvSourceExt] accessors. Core itself never sees these types; that is what
 * keeps the genre schema out of Core (VISION §6 / §2 genre extensibility claim).
 *
 * Field philosophy: mirror the vlog / narrative genres — lean, free-form strings
 * for the bulk, a few nullable numeric hints where a downstream compiler or UI
 * benefits (`bpm`, `targetDurationSeconds`). No enums for mood / motif / style;
 * the agent will violate them on day two and we pay for the rigidity forever.
 */

/**
 * The source audio track the MV is cut to. `assetId` points at the imported or
 * AIGC-generated music asset; the optional `bpm` / `keySignature` hints let the
 * agent line cuts to musical structure without having to re-analyse the audio
 * every turn. `lyricsRef` is free-form — it can be the lyrics verbatim, a
 * `SourceNodeId` of a separately-imported lyrics node, or a file path; callers
 * decide the convention.
 *
 * @param assetId imported / generated music asset.
 * @param title song title for UI / prompt folding.
 * @param artist performer credit. Left `""` when unknown rather than making the
 *   field nullable — an empty string round-trips cleanly through the agent's
 *   typical "I don't know yet" state without a nullable branch.
 * @param bpm beats per minute hint. Optional; cut tools may align cuts to
 *   downbeats when set.
 * @param keySignature free-form musical key ("C minor", "F# dorian"). The
 *   agent passes this through to AIGC tools that care about musical coherence.
 * @param lyricsRef free-form pointer to lyrics content.
 */
@Serializable
data class MusicMvTrackBody(
    val assetId: AssetId,
    val title: String,
    val artist: String = "",
    val bpm: Int? = null,
    val keySignature: String? = null,
    val lyricsRef: String = "",
)

/**
 * The creative brief for the MV's look — what the video should *feel* like.
 * Motifs are free-form list entries ("neon rain", "one-take choreography")
 * rather than an enum so the agent and user can push whatever vocabulary
 * they're already thinking in. `paletteRef` points at a `brand_palette`
 * [io.talevia.core.SourceNodeId] when the MV must be consistent with a
 * palette — the AIGC tools fold palette bindings the same way they do for
 * character_ref / style_bible.
 *
 * @param logline one-sentence pitch for the MV's visual concept.
 * @param mood free-form mood descriptor ("melancholic", "euphoric club").
 * @param motifs recurring visual ideas the agent should seed across shots.
 * @param paletteRef optional `core.consistency.brand_palette` node id the
 *   compiler should fold into AIGC calls.
 */
@Serializable
data class MusicMvVisualConceptBody(
    val logline: String,
    val mood: String = "",
    val motifs: List<String> = emptyList(),
    val paletteRef: String? = null,
)

/**
 * A performance shot — the performer doing the action. Typically imported
 * footage (multiple takes = multiple asset ids), though an AIGC performance
 * shot is valid too. `targetDurationSeconds` is nullable because performance
 * shots are often trimmed to musical phrasing rather than a fixed duration.
 *
 * @param performer name / identifier of the performer.
 * @param action what they are doing in the shot ("lip-sync to chorus",
 *   "dance break").
 * @param assetIds imported footage clips of this performance; several takes
 *   live in one list so the agent can pick among them during assembly.
 * @param targetDurationSeconds optional runtime hint for this shot's
 *   contribution to the final cut.
 */
@Serializable
data class MusicMvPerformanceShotBody(
    val performer: String,
    val action: String = "",
    val assetIds: List<AssetId> = emptyList(),
    val targetDurationSeconds: Double? = null,
)
