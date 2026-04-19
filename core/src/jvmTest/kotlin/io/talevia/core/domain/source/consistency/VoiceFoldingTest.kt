package io.talevia.core.domain.source.consistency

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Voice fold = "pick the one bound character's voice, or nothing." Visual-fold
 * concerns (style bibles, brand palettes, character appearance) never influence
 * the pick — TTS has no style axis to bind them to.
 */
class VoiceFoldingTest {

    @Test fun emptyBindingsReturnsNullVoice() {
        val out = foldVoice(emptyList())
        assertNull(out.voiceId)
        assertTrue(out.appliedNodeIds.isEmpty())
    }

    @Test fun characterWithoutVoiceIdIsSkipped() {
        val src = Source.EMPTY.addCharacterRef(
            SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
        )
        val bound = src.resolveConsistencyBindings(listOf(SourceNodeId("mei")))
        val out = foldVoice(bound)
        assertNull(out.voiceId)
        assertTrue(out.appliedNodeIds.isEmpty())
    }

    @Test fun singleVoicedCharacterPicksItsVoice() {
        val src = Source.EMPTY.addCharacterRef(
            SourceNodeId("mei"),
            CharacterRefBody(
                name = "Mei",
                visualDescription = "teal hair",
                voiceId = "nova",
            ),
        )
        val bound = src.resolveConsistencyBindings(listOf(SourceNodeId("mei")))
        val out = foldVoice(bound)
        assertEquals("nova", out.voiceId)
        assertEquals(listOf("mei"), out.appliedNodeIds)
    }

    @Test fun stylesAndBrandsAreIgnored() {
        val src = Source.EMPTY
            .addStyleBible(
                SourceNodeId("style"),
                StyleBibleBody(name = "warm", description = "warm feel"),
            )
            .addBrandPalette(
                SourceNodeId("brand"),
                BrandPaletteBody(name = "talevia", hexColors = listOf("#000")),
            )
            .addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "x", voiceId = "nova"),
            )
        val bound = src.resolveConsistencyBindings(
            listOf(SourceNodeId("style"), SourceNodeId("brand"), SourceNodeId("mei")),
        )
        val out = foldVoice(bound)
        assertEquals("nova", out.voiceId)
        assertEquals(listOf("mei"), out.appliedNodeIds)
    }

    @Test fun blankVoiceIdCountsAsUnset() {
        val src = Source.EMPTY.addCharacterRef(
            SourceNodeId("mei"),
            CharacterRefBody(name = "Mei", visualDescription = "x", voiceId = "   "),
        )
        val bound = src.resolveConsistencyBindings(listOf(SourceNodeId("mei")))
        assertNull(foldVoice(bound).voiceId)
    }

    @Test fun multipleVoicedCharactersFailLoudly() {
        val src = Source.EMPTY
            .addCharacterRef(
                SourceNodeId("mei"),
                CharacterRefBody(name = "Mei", visualDescription = "x", voiceId = "nova"),
            )
            .addCharacterRef(
                SourceNodeId("jun"),
                CharacterRefBody(name = "Jun", visualDescription = "y", voiceId = "onyx"),
            )
        val bound = src.resolveConsistencyBindings(
            listOf(SourceNodeId("mei"), SourceNodeId("jun")),
        )
        val err = assertFailsWith<IllegalStateException> { foldVoice(bound) }
        assertTrue("Ambiguous voice bindings" in err.message!!)
        assertTrue("mei" in err.message!! && "jun" in err.message!!)
    }
}
