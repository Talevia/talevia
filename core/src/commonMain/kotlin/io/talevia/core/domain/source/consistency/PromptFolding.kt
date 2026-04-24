package io.talevia.core.domain.source.consistency

import io.talevia.core.domain.source.SourceNode

/**
 * Result of folding consistency nodes into an AIGC prompt. The effective prompt is
 * what the tool sends to the provider; [appliedNodeIds] is what it records as the
 * clip's [io.talevia.core.domain.Clip.sourceBinding] so the DAG can mark downstream
 * renders stale when any bound node changes.
 */
data class FoldedPrompt(
    val effectivePrompt: String,
    val negativePrompt: String?,
    val appliedNodeIds: List<String>,
    val loraPins: List<LoraPin>,
    val referenceAssetIds: List<String>,
)

/**
 * Fold consistency nodes into an AIGC prompt. Ordering is deliberate:
 *
 *   [style bibles] [brand palette] [locations] [characters] + base prompt
 *
 * Style/brand first so the model reads "global look" before "this shot's subject";
 * location next so spatial context frames the subject; character last-but-one so the
 * identity signal sits close to the shot-specific text; base prompt at the tail because
 * models pay more attention there.
 *
 * Nodes of unknown kinds are ignored — the caller may pass in the full source node
 * list without pre-filtering. Unsupported node kinds never block a generation.
 *
 * [negativePrompt] is the *merged* negative from all style bibles (comma-joined), so
 * callers don't need to track it separately.
 *
 * [loraPins] and [referenceAssetIds] surface the provider-specific hooks separately
 * from the text prompt — image-gen providers vary on whether they accept them, and
 * the text prompt is already populated with the character's textual description for
 * providers that support nothing but text.
 */
fun foldConsistencyIntoPrompt(basePrompt: String, nodes: List<SourceNode>): FoldedPrompt {
    if (nodes.isEmpty()) {
        return FoldedPrompt(
            effectivePrompt = basePrompt,
            negativePrompt = null,
            appliedNodeIds = emptyList(),
            loraPins = emptyList(),
            referenceAssetIds = emptyList(),
        )
    }
    val styleFragments = mutableListOf<String>()
    val negatives = mutableListOf<String>()
    val brandFragments = mutableListOf<String>()
    val locationFragments = mutableListOf<String>()
    val characterFragments = mutableListOf<String>()
    val applied = mutableListOf<String>()
    val loras = mutableListOf<LoraPin>()
    val refs = mutableListOf<String>()

    for (node in nodes) {
        when (node.kind) {
            ConsistencyKinds.STYLE_BIBLE -> node.asStyleBible()?.let { style ->
                applied += node.id.value
                styleFragments += buildString {
                    append("Style: ").append(style.name)
                    if (style.description.isNotBlank()) append(" — ").append(style.description)
                    if (style.moodKeywords.isNotEmpty()) {
                        append(" [mood: ").append(style.moodKeywords.joinToString(", ")).append("]")
                    }
                }
                style.negativePrompt?.takeIf { it.isNotBlank() }?.let { negatives += it }
            }
            ConsistencyKinds.BRAND_PALETTE -> node.asBrandPalette()?.let { brand ->
                applied += node.id.value
                brandFragments += buildString {
                    append("Brand: ").append(brand.name)
                    if (brand.hexColors.isNotEmpty()) {
                        append(" palette ").append(brand.hexColors.joinToString(" / "))
                    }
                    if (brand.typographyHints.isNotEmpty()) {
                        append(" typography ").append(brand.typographyHints.joinToString(", "))
                    }
                }
            }
            ConsistencyKinds.LOCATION_REF -> node.asLocationRef()?.let { loc ->
                applied += node.id.value
                locationFragments += buildString {
                    append("Setting \"").append(loc.name).append("\": ")
                    append(loc.description)
                }
                refs.addAll(loc.referenceAssetIds.map { it.value })
            }
            ConsistencyKinds.CHARACTER_REF -> node.asCharacterRef()?.let { char ->
                applied += node.id.value
                characterFragments += buildString {
                    append("Character \"").append(char.name).append("\": ")
                    append(char.visualDescription)
                }
                char.loraPin?.let { loras += it }
                refs.addAll(char.referenceAssetIds.map { it.value })
            }
            else -> Unit
        }
    }

    val effective = buildString {
        styleFragments.forEach { appendLine(it) }
        brandFragments.forEach { appendLine(it) }
        locationFragments.forEach { appendLine(it) }
        characterFragments.forEach { appendLine(it) }
        if (isNotEmpty() && basePrompt.isNotBlank()) appendLine()
        append(basePrompt)
    }.trim()

    val negative = if (negatives.isEmpty()) null else negatives.joinToString(", ")

    return FoldedPrompt(
        effectivePrompt = effective,
        negativePrompt = negative,
        appliedNodeIds = applied,
        loraPins = loras,
        referenceAssetIds = refs,
    )
}
