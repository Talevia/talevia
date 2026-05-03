package io.talevia.core.tool.builtin.project.template

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.consistency.asBrandPalette
import io.talevia.core.domain.source.genre.ad.AdNodeKinds
import io.talevia.core.domain.source.genre.ad.asAdBrandBrief
import io.talevia.core.domain.source.genre.ad.asAdProductSpec
import io.talevia.core.domain.source.genre.ad.asAdVariantRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [seedAdTemplate] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/template/AdTemplate.kt`.
 * Cycle 251 audit: 0 direct test refs (only the indirect tool-
 * dispatch test `adSeedsFourNodesAndWiresParents` exercises it).
 *
 * Same audit-pattern fallback as cycles 207-250. Sister to cycle
 * 250's [seedNarrativeTemplate] pin — same shape (`seededNodeIds`
 * order + parent edges + body placeholders) for a different
 * genre.
 *
 * The kdoc explicitly says "Variant is parented on both brief
 * and product so edits to either flow down to every downstream
 * cut." That dual-parent wiring is the marquee load-bearing
 * value of the ad template — pinning it is what enforces the
 * promise.
 *
 * Pins three correctness contracts:
 *
 *  1. **`seededNodeIds` ordering**: per source,
 *     `[paletteId, briefId, productId, variantId]`. Drift would
 *     silently shuffle Output.seededNodeIds order downstream.
 *
 *  2. **Parent-edge DAG topology**:
 *     - brand-palette → no parents (root)
 *     - brand-brief → [brand-palette]
 *     - product → no parents (root) — products don't depend on
 *       branding by construction
 *     - variant-1 → [brand-brief, product]
 *       **Marquee dual-parent pin** matching the kdoc promise.
 *
 *  3. **Body-content defaults**:
 *     - brand-palette: hexColors=["#000000"] (single black
 *       sentinel — drift to `[]` would silently break
 *       downstream renderers expecting at least one color).
 *     - brand-brief: 4 specific TODO placeholders
 *       (brandName / tagline / audience / callToAction).
 *     - product: 2 TODO placeholders (productName / description).
 *     - variant: variantName="variant-1", targetDurationSeconds=15,
 *       aspectRatio="16:9" (concrete defaults — drift in any
 *       silently changes first-impression UX), notes="TODO: ..."
 *       placeholder.
 */
class AdTemplateTest {

    private val paletteId = "brand-palette"
    private val briefId = "brand-brief"
    private val productId = "product"
    private val variantId = "variant-1"

    // ── 1. seededNodeIds ordering ───────────────────────────

    @Test fun seededNodeIdsOrderingMatchesAddOrder() {
        val (_, ids) = seedAdTemplate()
        assertEquals(
            listOf(paletteId, briefId, productId, variantId),
            ids,
            "seededNodeIds MUST match add-order: palette → brief → product → variant",
        )
    }

    @Test fun seededNodeIdsHasExactlyFourEntries() {
        val (_, ids) = seedAdTemplate()
        assertEquals(4, ids.size, "ad template seeds exactly 4 nodes")
    }

    // ── 2. Source has all 4 nodes by ID + correct kinds ─────

    @Test fun sourceContainsAllFourNodeIds() {
        val (source, _) = seedAdTemplate()
        val ids = source.nodes.map { it.id.value }.toSet()
        assertEquals(
            setOf(paletteId, briefId, productId, variantId),
            ids,
        )
    }

    @Test fun sourceNodeKindsMatchKdocClassification() {
        val (source, _) = seedAdTemplate()
        val kindByOd = source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.BRAND_PALETTE, kindByOd[paletteId])
        assertEquals(AdNodeKinds.BRAND_BRIEF, kindByOd[briefId])
        assertEquals(AdNodeKinds.PRODUCT_SPEC, kindByOd[productId])
        assertEquals(AdNodeKinds.VARIANT_REQUEST, kindByOd[variantId])
    }

    // ── 3. Parent-edge DAG topology ─────────────────────────

    @Test fun brandPaletteIsRootNode() {
        assertTrue(parentsOf(paletteId).isEmpty(), "$paletteId MUST be a root")
    }

    @Test fun productIsRootNode() {
        // Pin: per the kdoc, products don't depend on branding by
        // construction — a product spec is independent of the
        // brand brief. Drift to "product → palette" would silently
        // create a phantom dependency.
        assertTrue(parentsOf(productId).isEmpty(), "$productId MUST be a root")
    }

    @Test fun brandBriefHasBrandPaletteAsParent() {
        // Pin: edits to the palette propagate through the brief
        // to every downstream variant (since variant has brief as
        // a parent). Drift here would silently break that chain.
        assertEquals(
            listOf(SourceRef(SourceNodeId(paletteId))),
            parentsOf(briefId),
            "$briefId MUST have $paletteId as its only parent",
        )
    }

    @Test fun variantHasBriefAndProductAsParents() {
        // Marquee dual-parent pin matching the kdoc: "Variant is
        // parented on both brief and product so edits to either
        // flow down to every downstream cut." Drift to either
        // single-parent silently breaks half the propagation
        // chain.
        val parents = parentsOf(variantId)
        assertEquals(
            listOf(SourceRef(SourceNodeId(briefId)), SourceRef(SourceNodeId(productId))),
            parents,
            "$variantId MUST have BOTH $briefId AND $productId as parents (in that order); got: $parents",
        )
    }

    // ── 4. Body-content defaults ────────────────────────────

    @Test fun brandPaletteBodyHasSingleBlackHexSentinel() {
        // Pin: per source, hexColors is ["#000000"] — a single
        // black sentinel. Drift to empty list `[]` would silently
        // break downstream renderers expecting at least one
        // color (some compose UIs ASSUME hexColors[0] exists for
        // primary swatches).
        val body = nodeOf(paletteId).asBrandPalette()
        assertNotNull(body, "$paletteId MUST decode as BrandPaletteBody")
        assertEquals("brand-palette", body.name)
        assertEquals(
            listOf("#000000"),
            body.hexColors,
            "default palette MUST contain exactly one black hex sentinel",
        )
        assertTrue(
            body.typographyHints.isEmpty(),
            "default palette has no typography hints",
        )
    }

    @Test fun brandBriefBodyHasFourTodoPlaceholders() {
        // Marquee 4-field placeholder pin: drift to drop the
        // "TODO:" prefix in any field would change the convention
        // users grep for; drift to drop a field silently changes
        // the brief's UX.
        val body = nodeOf(briefId).asAdBrandBrief()
        assertNotNull(body, "$briefId MUST decode as AdBrandBriefBody")
        assertEquals("TODO: brand name", body.brandName)
        assertEquals("TODO: campaign tagline", body.tagline)
        assertEquals("TODO: target audience", body.audience)
        assertEquals("TODO: call to action", body.callToAction)
    }

    @Test fun productBodyHasTwoTodoPlaceholders() {
        val body = nodeOf(productId).asAdProductSpec()
        assertNotNull(body, "$productId MUST decode as AdProductSpecBody")
        assertEquals("TODO: product name", body.productName)
        assertEquals("TODO: product description", body.description)
    }

    @Test fun variantBodyCarriesConcreteDefaultsAndPlaceholderNotes() {
        // Marquee concrete-default pin: variant.targetDurationSeconds
        // = 15 (the canonical 15-second ad cut), aspectRatio =
        // "16:9" (default landscape). Drift to 30s / 9:16 silently
        // changes first-impression UX for fresh ad projects.
        // Variant.variantName matches the id — drift would
        // confuse users looking for the variant by name.
        val body = nodeOf(variantId).asAdVariantRequest()
        assertNotNull(body, "$variantId MUST decode as AdVariantRequestBody")
        assertEquals(
            "variant-1",
            body.variantName,
            "default variantName MUST match the id 'variant-1'",
        )
        assertEquals(
            15,
            body.targetDurationSeconds,
            "default duration MUST be 15s (canonical short ad)",
        )
        assertEquals(
            "16:9",
            body.aspectRatio,
            "default aspect MUST be 16:9 (drift to 9:16 / 1:1 silently changes UX)",
        )
        assertEquals(
            "TODO: variant-specific creative notes",
            body.notes,
            "notes MUST carry the canonical TODO placeholder",
        )
    }

    // ── helpers ─────────────────────────────────────────────

    private fun parentsOf(id: String): List<SourceRef> {
        val (source, _) = seedAdTemplate()
        return source.nodes.first { it.id.value == id }.parents
    }

    private fun nodeOf(id: String): io.talevia.core.domain.source.SourceNode {
        val (source, _) = seedAdTemplate()
        return source.nodes.first { it.id.value == id }
    }
}
