package io.talevia.core.domain.source.consistency

import io.talevia.core.AssetId
import io.talevia.core.JsonConfig
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Schema + round-trip contract for consistency source nodes (VISION §3.3).
 */
class ConsistencyNodeTest {

    private val json = JsonConfig.default

    @Test fun characterRefRoundTrip() {
        val body = CharacterRefBody(
            name = "Mei",
            visualDescription = "teal hair, round glasses, yellow raincoat",
            referenceAssetIds = listOf(AssetId("asset-mei-1")),
            loraPin = LoraPin(adapterId = "civitai:mei-v3", weight = 0.8f, triggerTokens = listOf("mei_chr")),
        )
        val src = Source.EMPTY.addCharacterRef(SourceNodeId("mei"), body)
        val decoded = json.decodeFromString(Source.serializer(), json.encodeToString(Source.serializer(), src))
        val node = assertNotNull(decoded.byId[SourceNodeId("mei")])
        assertEquals(ConsistencyKinds.CHARACTER_REF, node.kind)
        assertEquals(body, node.asCharacterRef())
        assertNull(node.asStyleBible())
    }

    @Test fun styleBibleRoundTrip() {
        val body = StyleBibleBody(
            name = "cinematic-warm",
            description = "warm teal/orange, shallow DOF, 35mm feel",
            negativePrompt = "flat lighting, oversaturated, plastic skin",
            moodKeywords = listOf("warm", "nostalgic"),
        )
        val src = Source.EMPTY.addStyleBible(SourceNodeId("style"), body)
        val decoded = json.decodeFromString(Source.serializer(), json.encodeToString(Source.serializer(), src))
        assertEquals(body, decoded.byId.getValue(SourceNodeId("style")).asStyleBible())
    }

    @Test fun brandPaletteRoundTrip() {
        val body = BrandPaletteBody(
            name = "talevia-brand",
            hexColors = listOf("#0A84FF", "#FF3B30"),
            typographyHints = listOf("Inter / geometric sans"),
        )
        val src = Source.EMPTY.addBrandPalette(SourceNodeId("brand"), body)
        val decoded = json.decodeFromString(Source.serializer(), json.encodeToString(Source.serializer(), src))
        assertEquals(body, decoded.byId.getValue(SourceNodeId("brand")).asBrandPalette())
    }

    @Test fun consistencyNodesSelectsOnlyConsistencyKinds() {
        val src = Source.EMPTY
            .addCharacterRef(SourceNodeId("mei"), CharacterRefBody("Mei", "desc"))
            .addStyleBible(SourceNodeId("style"), StyleBibleBody("cinematic", "desc"))
        val nodes = src.consistencyNodes()
        assertEquals(2, nodes.size)
        assertTrue(nodes.any { it.kind == ConsistencyKinds.CHARACTER_REF })
        assertTrue(nodes.any { it.kind == ConsistencyKinds.STYLE_BIBLE })
    }

    @Test fun resolveBindingsSilentlyDropsNonConsistencyIds() {
        val src = Source.EMPTY
            .addCharacterRef(SourceNodeId("mei"), CharacterRefBody("Mei", "desc"))
        val resolved = src.resolveConsistencyBindings(
            listOf(SourceNodeId("mei"), SourceNodeId("ghost")),
        )
        assertEquals(listOf(SourceNodeId("mei")), resolved.map { it.id })
    }
}
