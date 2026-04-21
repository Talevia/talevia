package io.talevia.core.domain.source.genre.tutorial

import io.talevia.core.AssetId
import kotlinx.serialization.Serializable

/**
 * Typed payload bodies for the Tutorial genre's
 * [io.talevia.core.domain.source.SourceNode]s.
 *
 * Each body is a regular [kotlinx.serialization.Serializable] data class — it
 * is encoded into a [kotlinx.serialization.json.JsonElement] via the canonical
 * [io.talevia.core.JsonConfig.default] at write time and decoded on read by
 * [TutorialSourceExt] accessors. Core itself never sees these types.
 *
 * Field philosophy: tutorials are raw-footage-heavy (screen captures, demo
 * recordings) plus a scripted voiceover. The schema reflects that — a
 * script node carrying the spoken text, a b-roll library carrying the
 * footage, and a brand spec for the lower-third / product-name styling.
 * `segments` on the script is a free-form list of section headers
 * ("intro", "setup", "demo", "wrap") the agent may use for chaptering
 * without us inventing a typed struct for it.
 */

/**
 * Script for the tutorial — the voiceover text plus coarse section markers.
 * `spokenText` is the literal payload fed to `synthesize_speech`. `segments`
 * is optional chaptering metadata; keep it string-list-typed so the agent
 * can name sections however makes sense for the video ("install", "first
 * run", "troubleshoot") without being forced into a taxonomy.
 *
 * @param title human-readable title for the tutorial.
 * @param spokenText the voiceover script, word-for-word.
 * @param segments free-form list of section headers for chaptering.
 * @param targetDurationSeconds optional total runtime hint — the agent
 *   paces cuts against this when set.
 */
@Serializable
data class TutorialScriptBody(
    val title: String,
    val spokenText: String,
    val segments: List<String> = emptyList(),
    val targetDurationSeconds: Int? = null,
)

/**
 * B-roll library — imported screen-capture or demo recordings the tutorial
 * draws from. Intentionally mirrors `VlogRawFootageBody` because the
 * shape is the same: a bag of asset ids plus creator notes. Keeping
 * the shapes parallel means cross-genre tooling (list / cull / describe
 * raw footage) can treat them uniformly if we ever want to.
 *
 * @param assetIds imported screen-capture / demo clips.
 * @param notes free-form creator notes ("re-record the install step").
 */
@Serializable
data class TutorialBrollLibraryBody(
    val assetIds: List<AssetId>,
    val notes: String = "",
)

/**
 * Brand / look specification for the tutorial — product name shown on
 * screen, brand colors, lower-third styling, optional logo asset. This
 * is the tutorial-local surface; global brand palette consistency is
 * still expressed via `core.consistency.brand_palette` when the same
 * brand spans multiple projects. Keeping a genre-local brand spec lets
 * one-off tutorials ship without scaffolding a full palette node.
 *
 * @param productName the name the tutorial is about ("Talevia CLI").
 * @param brandColors hex strings / named colors the lower third uses.
 * @param lowerThirdStyle free-form description ("minimal white text,
 *   black drop shadow, bottom-left").
 * @param logoAssetId optional logo image asset for the bug / watermark.
 */
@Serializable
data class TutorialBrandSpecBody(
    val productName: String,
    val brandColors: List<String> = emptyList(),
    val lowerThirdStyle: String = "",
    val logoAssetId: AssetId? = null,
)
