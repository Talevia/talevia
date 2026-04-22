package io.talevia.core.tool.builtin.project.template

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.BrandPaletteBody
import io.talevia.core.domain.source.consistency.addBrandPalette
import io.talevia.core.domain.source.genre.ad.AdBrandBriefBody
import io.talevia.core.domain.source.genre.ad.AdProductSpecBody
import io.talevia.core.domain.source.genre.ad.AdVariantRequestBody
import io.talevia.core.domain.source.genre.ad.addAdBrandBrief
import io.talevia.core.domain.source.genre.ad.addAdProductSpec
import io.talevia.core.domain.source.genre.ad.addAdVariantRequest

/**
 * `ad` genre skeleton — brand_brief + product_spec + variant_request +
 * brand_palette. Variant is parented on both brief and product so edits to
 * either flow down to every downstream cut.
 */
internal fun seedAdTemplate(): Pair<Source, List<String>> {
    val paletteId = SourceNodeId("brand-palette")
    val briefId = SourceNodeId("brand-brief")
    val productId = SourceNodeId("product")
    val variantId = SourceNodeId("variant-1")

    val s: Source = Source.EMPTY
        .addBrandPalette(
            paletteId,
            BrandPaletteBody(name = "brand-palette", hexColors = listOf("#000000")),
        )
        .addAdBrandBrief(
            briefId,
            AdBrandBriefBody(
                brandName = "TODO: brand name",
                tagline = "TODO: campaign tagline",
                audience = "TODO: target audience",
                callToAction = "TODO: call to action",
            ),
            parents = listOf(SourceRef(paletteId)),
        )
        .addAdProductSpec(
            productId,
            AdProductSpecBody(
                productName = "TODO: product name",
                description = "TODO: product description",
            ),
        )
        .addAdVariantRequest(
            variantId,
            AdVariantRequestBody(
                variantName = "variant-1",
                targetDurationSeconds = 15,
                aspectRatio = "16:9",
                notes = "TODO: variant-specific creative notes",
            ),
            parents = listOf(SourceRef(briefId), SourceRef(productId)),
        )
    return s to listOf(paletteId.value, briefId.value, productId.value, variantId.value)
}
