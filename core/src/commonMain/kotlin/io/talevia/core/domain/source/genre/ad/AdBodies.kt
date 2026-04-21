package io.talevia.core.domain.source.genre.ad

import io.talevia.core.AssetId
import kotlinx.serialization.Serializable

/**
 * Typed payload bodies for the Ad / Marketing genre's
 * [io.talevia.core.domain.source.SourceNode]s.
 *
 * Each body is a regular [kotlinx.serialization.Serializable] data class — it
 * is encoded into a [kotlinx.serialization.json.JsonElement] via the canonical
 * [io.talevia.core.JsonConfig.default] at write time and decoded on read by
 * [AdSourceExt] accessors. Core itself never sees these types.
 *
 * Distinguishing property of the genre: **ads are variant-heavy**. The same
 * spot usually ships in multiple durations (6s / 15s / 30s), aspect ratios
 * (16:9 / 9:16 / 1:1), and languages. Rather than squashing that into one
 * node with nested structs, each variant is its own `ad.variant_request`
 * node — one project accumulates many. That matches how teams actually
 * track ad deliverables and lets the DAG treat each variant as an
 * independent downstream artifact of the shared brief + product spec.
 */

/**
 * Strategy brief — who the ad is for, what it should say. The top-level
 * creative input the agent folds into every variant's AIGC calls.
 *
 * @param brandName the brand the ad is for.
 * @param tagline the campaign tagline / headline, if there is one.
 * @param toneKeywords free-form tone descriptors ("playful", "premium",
 *   "aspirational"). Kept as a list rather than an enum — marketing
 *   briefs use whatever vocabulary the team already thinks in.
 * @param audience target audience description, free-form ("new parents
 *   shopping for a first car seat").
 * @param callToAction what the viewer should do after watching ("pre-
 *   order at talevia.io", "download the app").
 */
@Serializable
data class AdBrandBriefBody(
    val brandName: String,
    val tagline: String = "",
    val toneKeywords: List<String> = emptyList(),
    val audience: String = "",
    val callToAction: String = "",
)

/**
 * The thing being sold — product name, description, key benefits, and any
 * reference imagery (packshots, lifestyle photos) the AIGC tools should
 * use as visual references.
 *
 * @param productName the product's name.
 * @param description free-form product description.
 * @param keyBenefits short list of selling points the ad should hit.
 * @param referenceAssetIds packshots / lifestyle stills the compiler
 *   passes as visual references to `generate_image` / `generate_video`.
 */
@Serializable
data class AdProductSpecBody(
    val productName: String,
    val description: String = "",
    val keyBenefits: List<String> = emptyList(),
    val referenceAssetIds: List<AssetId> = emptyList(),
)

/**
 * One ad variant the project must ship — a specific (duration × aspect
 * ratio × language) combination. One project has many of these, one per
 * deliverable cut. Each variant ends up driving its own export (different
 * `outputProfile`, possibly different timeline slice).
 *
 * @param variantName human-readable label ("15s landscape en-US",
 *   "vertical teaser de-DE"). This is the id the team uses day-to-day.
 * @param targetDurationSeconds target runtime in seconds. Nullable so a
 *   variant can defer the decision ("do the creative first, time it
 *   later").
 * @param aspectRatio free-form aspect ratio string ("16:9", "9:16",
 *   "1:1"). Not an enum; platforms keep inventing new ones
 *   ("2:3 for TikTok-shop", "4:5 for Instagram-carousel") and we don't
 *   want to recompile Core every time.
 * @param language ISO-639-1 hint when the variant is language-specific
 *   (drives VO / subtitle language). Null when the variant is silent or
 *   language-agnostic.
 * @param notes free-form creative notes for this variant ("extra
 *   aggressive CTA; skip the hero shot").
 */
@Serializable
data class AdVariantRequestBody(
    val variantName: String,
    val targetDurationSeconds: Int? = null,
    val aspectRatio: String? = null,
    val language: String? = null,
    val notes: String = "",
)
