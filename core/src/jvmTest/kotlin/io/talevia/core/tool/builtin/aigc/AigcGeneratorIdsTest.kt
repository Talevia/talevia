package io.talevia.core.tool.builtin.aigc

import io.talevia.core.cost.AigcPricing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the 4 AIGC generator `ID` companion constants and their
 * cross-coupling with [AigcPricing] tool-id constants. Cycle 301
 * audit: 0 prior `assertEquals(AigcXGenerator.ID, ...)` test refs;
 * the literal values appear in 8+ source files but the coupling
 * between `AigcXGenerator.ID` and `AigcPricing.TOOL_*` is
 * untested.
 *
 * Same audit-pattern fallback as cycles 207-300. Applied banked
 * duplicate-check idiom (cycle 289).
 *
 * Why this matters: each generator class declares an `ID` companion
 * constant ("generate_image" etc.) used as the tool's wire id.
 * `AigcPricing.estimateCents(toolId, ...)` routes pricing per
 * modality based on the SAME literal value (declared separately in
 * `AigcPricing.TOOL_*` constants — covered cycle 280). The two
 * MUST stay in lockstep — drift in either silently breaks
 * `Lockfile` cost lookups for that modality.
 *
 * Drift signals:
 *   - **Drift in any AigcXGenerator.ID** silently breaks tool
 *     dispatch (the dispatcher routes on `Tool.id` literal).
 *   - **Drift in cross-constant coupling** (AigcImageGenerator.ID ≠
 *     AigcPricing.TOOL_GENERATE_IMAGE) silently breaks per-modality
 *     pricing lookups — `estimateCents` returns null instead of
 *     the actual price.
 *   - **Cross-uniqueness drift** (two generators share an ID)
 *     silently merges modalities at the dispatch layer.
 */
class AigcGeneratorIdsTest {

    // ── Per-generator exact-value pins ──────────────────────

    @Test fun aigcImageGeneratorIdIsGenerateImage() {
        assertEquals(
            "generate_image",
            AigcImageGenerator.ID,
            "AigcImageGenerator.ID MUST be 'generate_image' (drift breaks dispatch)",
        )
    }

    @Test fun aigcVideoGeneratorIdIsGenerateVideo() {
        assertEquals("generate_video", AigcVideoGenerator.ID)
    }

    @Test fun aigcMusicGeneratorIdIsGenerateMusic() {
        assertEquals("generate_music", AigcMusicGenerator.ID)
    }

    @Test fun aigcSpeechGeneratorIdIsSynthesizeSpeech() {
        // Marquee odd-one-out pin: speech is "synthesize_speech",
        // NOT "generate_speech". Drift to align with the others
        // would silently break dispatch.
        assertEquals(
            "synthesize_speech",
            AigcSpeechGenerator.ID,
            "AigcSpeechGenerator.ID MUST be 'synthesize_speech' (NOT 'generate_speech')",
        )
    }

    // ── Cross-coupling with AigcPricing.TOOL_* ─────────────

    @Test fun aigcImageGeneratorIdMatchesAigcPricingToolGenerateImage() {
        // Marquee cross-coupling pin: drift in either constant
        // silently breaks per-modality pricing lookup.
        assertEquals(
            AigcPricing.TOOL_GENERATE_IMAGE,
            AigcImageGenerator.ID,
            "AigcImageGenerator.ID MUST equal AigcPricing.TOOL_GENERATE_IMAGE — pricing routes on this literal",
        )
    }

    @Test fun aigcVideoGeneratorIdMatchesAigcPricingToolGenerateVideo() {
        assertEquals(AigcPricing.TOOL_GENERATE_VIDEO, AigcVideoGenerator.ID)
    }

    @Test fun aigcMusicGeneratorIdMatchesAigcPricingToolGenerateMusic() {
        assertEquals(AigcPricing.TOOL_GENERATE_MUSIC, AigcMusicGenerator.ID)
    }

    @Test fun aigcSpeechGeneratorIdMatchesAigcPricingToolSynthesizeSpeech() {
        assertEquals(AigcPricing.TOOL_SYNTHESIZE_SPEECH, AigcSpeechGenerator.ID)
    }

    // ── Cross-uniqueness ───────────────────────────────────

    @Test fun fourAigcGeneratorIdsAreDistinct() {
        // Marquee uniqueness pin: drift to share an ID would
        // silently merge two modalities at the dispatch layer.
        val ids = setOf(
            AigcImageGenerator.ID,
            AigcVideoGenerator.ID,
            AigcMusicGenerator.ID,
            AigcSpeechGenerator.ID,
        )
        assertEquals(
            4,
            ids.size,
            "the 4 AigcXGenerator.ID values MUST be distinct; got: $ids",
        )
    }

    // ── Wire-format conventions ────────────────────────────

    @Test fun allIdsAreLowercaseSnakeCase() {
        // Pin: every ID matches the lowercase-with-underscores
        // wire-format convention shared by every Tool.id in the
        // builtin set. Drift to camelCase (e.g.
        // 'generateImage') silently breaks the convention.
        for ((name, id) in mapOf(
            "AigcImageGenerator.ID" to AigcImageGenerator.ID,
            "AigcVideoGenerator.ID" to AigcVideoGenerator.ID,
            "AigcMusicGenerator.ID" to AigcMusicGenerator.ID,
            "AigcSpeechGenerator.ID" to AigcSpeechGenerator.ID,
        )) {
            assertEquals(
                id.lowercase(),
                id,
                "$name MUST be lowercase; got: '$id'",
            )
            assertTrue(
                "-" !in id,
                "$name MUST NOT contain hyphens (use underscores)",
            )
            assertTrue(
                id.all { it.isLetterOrDigit() || it == '_' },
                "$name MUST be [a-z0-9_] only; got: '$id'",
            )
        }
    }

    @Test fun threeOfFourIdsStartWithGenerate() {
        // Pin: 3 generators use "generate_*" prefix; speech
        // uses "synthesize_speech" because TTS isn't really
        // generation in the same sense as image/video/music
        // (it converts text → audio rather than minting from
        // a prompt). Pin documents the actual convention so a
        // future refactor that "harmonises" to all-generate-*
        // surfaces the trade-off explicitly.
        val generateCount = listOf(
            AigcImageGenerator.ID,
            AigcVideoGenerator.ID,
            AigcMusicGenerator.ID,
            AigcSpeechGenerator.ID,
        ).count { it.startsWith("generate_") }
        assertEquals(
            3,
            generateCount,
            "exactly 3 generators use 'generate_*' prefix (image/video/music); speech uses 'synthesize_speech'",
        )
    }

    // ── Sanity: can be used as Tool.id ─────────────────────

    @Test fun idsAreNonBlankAndNonEmpty() {
        for ((name, id) in mapOf(
            "AigcImageGenerator.ID" to AigcImageGenerator.ID,
            "AigcVideoGenerator.ID" to AigcVideoGenerator.ID,
            "AigcMusicGenerator.ID" to AigcMusicGenerator.ID,
            "AigcSpeechGenerator.ID" to AigcSpeechGenerator.ID,
        )) {
            assertTrue(id.isNotBlank(), "$name MUST be non-blank; got: '$id'")
        }
    }

    // ── Cross-coupling: every TOOL_* has a matching generator ID

    @Test fun everyAigcPricingToolConstantMapsToAGeneratorId() {
        // Sister coverage pin: AigcPricing has 5 TOOL_*
        // constants (image / TTS / video / music + upscale).
        // Of those, 4 correspond to the AigcXGenerator family;
        // upscale_asset is not in this family (separate
        // UpscaleAssetTool with its own id) so it's expected
        // to NOT have a matching generator ID. Pin documents
        // the 4-to-4 correspondence + the upscale exclusion.
        val generatorIds = setOf(
            AigcImageGenerator.ID,
            AigcVideoGenerator.ID,
            AigcMusicGenerator.ID,
            AigcSpeechGenerator.ID,
        )
        assertTrue(AigcPricing.TOOL_GENERATE_IMAGE in generatorIds)
        assertTrue(AigcPricing.TOOL_GENERATE_VIDEO in generatorIds)
        assertTrue(AigcPricing.TOOL_GENERATE_MUSIC in generatorIds)
        assertTrue(AigcPricing.TOOL_SYNTHESIZE_SPEECH in generatorIds)
        // Upscale is in AigcPricing but NOT in the
        // AigcXGenerator family.
        assertTrue(
            AigcPricing.TOOL_UPSCALE_ASSET !in generatorIds,
            "TOOL_UPSCALE_ASSET ('${AigcPricing.TOOL_UPSCALE_ASSET}') MUST NOT be one of the AigcXGenerator IDs (separate tool family)",
        )
    }
}
