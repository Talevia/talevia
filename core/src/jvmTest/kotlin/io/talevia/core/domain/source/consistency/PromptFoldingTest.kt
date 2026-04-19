package io.talevia.core.domain.source.consistency

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Folding contract: consistency nodes become prompt fragments + separately-surfaced
 * provider hooks (LoRA pins, reference asset ids, negative prompt).
 */
class PromptFoldingTest {

    @Test fun emptyBindingsReturnsBasePromptUntouched() {
        val out = foldConsistencyIntoPrompt("a cat on a mat", emptyList())
        assertEquals("a cat on a mat", out.effectivePrompt)
        assertTrue(out.appliedNodeIds.isEmpty())
        assertNull(out.negativePrompt)
    }

    @Test fun styleStyleBrandCharacterOrderingInFoldedPrompt() {
        val src = Source.EMPTY
            .addStyleBible(
                SourceNodeId("style"),
                StyleBibleBody(
                    name = "cinematic-warm",
                    description = "warm teal/orange, 35mm feel",
                    moodKeywords = listOf("warm", "nostalgic"),
                ),
            )
            .addBrandPalette(
                SourceNodeId("brand"),
                BrandPaletteBody(name = "talevia", hexColors = listOf("#0A84FF")),
            )
            .addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair, round glasses"),
            )

        val bound = src.resolveConsistencyBindings(
            listOf(SourceNodeId("style"), SourceNodeId("brand"), SourceNodeId("mei")),
        )
        val out = foldConsistencyIntoPrompt("walking in the rain", bound)

        val text = out.effectivePrompt
        // Style first
        val styleIdx = text.indexOf("Style:")
        val brandIdx = text.indexOf("Brand:")
        val charIdx = text.indexOf("Character \"Mei\"")
        val baseIdx = text.indexOf("walking in the rain")
        assertTrue(styleIdx >= 0 && brandIdx > styleIdx, "style must precede brand")
        assertTrue(charIdx > brandIdx, "character must follow brand")
        assertTrue(baseIdx > charIdx, "base prompt must sit at the tail (attention bias)")

        assertEquals(
            listOf("style", "brand", "mei"),
            out.appliedNodeIds,
        )
    }

    @Test fun negativePromptsMergeAcrossStyleBibles() {
        val src = Source.EMPTY
            .addStyleBible(
                SourceNodeId("s1"),
                StyleBibleBody("a", "aa", negativePrompt = "blurry"),
            )
            .addStyleBible(
                SourceNodeId("s2"),
                StyleBibleBody("b", "bb", negativePrompt = "oversaturated"),
            )
        val bound = src.resolveConsistencyBindings(listOf(SourceNodeId("s1"), SourceNodeId("s2")))
        val out = foldConsistencyIntoPrompt("base", bound)
        assertEquals("blurry, oversaturated", out.negativePrompt)
    }

    @Test fun characterLorasAndRefsSurfaceSeparatelyFromPromptText() {
        val src = Source.EMPTY.addCharacterRef(
            SourceNodeId("mei"),
            CharacterRefBody(
                name = "Mei",
                visualDescription = "teal hair",
                referenceAssetIds = listOf(AssetId("ref-1"), AssetId("ref-2")),
                loraPin = LoraPin(adapterId = "civitai:mei", weight = 0.7f),
            ),
        )
        val bound = src.resolveConsistencyBindings(listOf(SourceNodeId("mei")))
        val out = foldConsistencyIntoPrompt("rainy day", bound)

        assertEquals(listOf("ref-1", "ref-2"), out.referenceAssetIds)
        assertEquals(1, out.loraPins.size)
        assertEquals("civitai:mei", out.loraPins.first().adapterId)
        assertEquals(0.7f, out.loraPins.first().weight)
    }

    @Test fun unknownKindsInBindingsAreIgnored() {
        // resolveConsistencyBindings already filters to consistency kinds — even if a
        // caller passes in a raw node list that includes e.g. vlog nodes, they're
        // simply skipped in the fold.
        val vlogishNode = io.talevia.core.domain.source.SourceNode.create(
            id = SourceNodeId("not-consistency"),
            kind = "vlog.raw_footage",
        )
        val out = foldConsistencyIntoPrompt("base", listOf(vlogishNode))
        assertEquals("base", out.effectivePrompt)
        assertTrue(out.appliedNodeIds.isEmpty())
    }
}
