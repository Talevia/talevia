package io.talevia.core.domain.source.genre.ad

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Round-trip + DAG-propagation hook contract for the Ad / Marketing genre.
 * Same three-property shape as the music-mv / tutorial genre tests.
 */
class AdBodiesTest {

    @Test fun brandBriefRoundTripsAndKindGuardReturnsNullForOtherKinds() {
        val body = AdBrandBriefBody(
            brandName = "Talevia",
            tagline = "Edit at the speed of thought.",
            toneKeywords = listOf("premium", "confident", "warm"),
            audience = "creators shipping weekly vlogs",
            callToAction = "Start free at talevia.io",
        )
        val src = Source.EMPTY.addAdBrandBrief(SourceNodeId("brief-1"), body)
        val node = src.byId.getValue(SourceNodeId("brief-1"))

        assertEquals(AdNodeKinds.BRAND_BRIEF, node.kind)
        assertEquals(body, node.asAdBrandBrief())
        assertNull(node.asAdProductSpec(), "kind mismatch must yield null")
        assertNull(node.asAdVariantRequest(), "kind mismatch must yield null")
    }

    @Test fun productSpecRoundTripsAndKindGuardReturnsNullForOtherKinds() {
        val body = AdProductSpecBody(
            productName = "Talevia Desktop",
            description = "An AI video editor that runs natively on your Mac.",
            keyBenefits = listOf("on-device agent", "native engine", "zero cloud lock-in"),
            referenceAssetIds = listOf(AssetId("packshot-1"), AssetId("lifestyle-1")),
        )
        val src = Source.EMPTY.addAdProductSpec(SourceNodeId("product-1"), body)
        val node = src.byId.getValue(SourceNodeId("product-1"))

        assertEquals(AdNodeKinds.PRODUCT_SPEC, node.kind)
        assertEquals(body, node.asAdProductSpec())
        assertNull(node.asAdBrandBrief())
        assertNull(node.asAdVariantRequest())
    }

    @Test fun variantRequestRoundTripsAndKindGuardReturnsNullForOtherKinds() {
        val body = AdVariantRequestBody(
            variantName = "15s vertical en-US",
            targetDurationSeconds = 15,
            aspectRatio = "9:16",
            language = "en",
            notes = "aggressive CTA, skip the hero shot",
        )
        val src = Source.EMPTY.addAdVariantRequest(SourceNodeId("variant-1"), body)
        val node = src.byId.getValue(SourceNodeId("variant-1"))

        assertEquals(AdNodeKinds.VARIANT_REQUEST, node.kind)
        assertEquals(body, node.asAdVariantRequest())
        assertNull(node.asAdBrandBrief())
        assertNull(node.asAdProductSpec())
    }

    @Test fun distinctBodiesHaveDistinctContentHashes() {
        val a = Source.EMPTY.addAdVariantRequest(
            SourceNodeId("v"),
            AdVariantRequestBody(variantName = "15s landscape", targetDurationSeconds = 15),
        )
        val b = Source.EMPTY.addAdVariantRequest(
            SourceNodeId("v"),
            AdVariantRequestBody(variantName = "30s landscape", targetDurationSeconds = 30),
        )
        val aHash = a.byId.getValue(SourceNodeId("v")).contentHash
        val bHash = b.byId.getValue(SourceNodeId("v")).contentHash
        assertNotNull(aHash)
        assertNotNull(bHash)
        assertNotEquals(aHash, bHash, "distinct bodies must yield distinct contentHash")
    }
}
