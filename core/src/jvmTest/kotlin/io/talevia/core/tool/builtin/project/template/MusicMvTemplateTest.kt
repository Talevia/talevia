package io.talevia.core.tool.builtin.project.template

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.consistency.asBrandPalette
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.genre.musicmv.MusicMvNodeKinds
import io.talevia.core.domain.source.genre.musicmv.asMusicMvPerformanceShot
import io.talevia.core.domain.source.genre.musicmv.asMusicMvVisualConcept
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [seedMusicMvTemplate] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/template/MusicMvTemplate.kt`.
 * Cycle 252 audit: 0 direct test refs (only the indirect tool-
 * dispatch test `musicMvSeedsFourNodesAndSkipsTrack` exercises it).
 *
 * Same audit-pattern fallback as cycles 207-251. Third in the
 * template-pin trilogy after [seedNarrativeTemplate] (cycle 250)
 * and [seedAdTemplate] (cycle 251).
 *
 * The kdoc carries a load-bearing **negative** invariant the
 * existing indirect test doesn't pin in detail: "The
 * `musicmv.track` node is deliberately **not** seeded because it
 * requires an imported music asset id — the caller nudges the
 * user to `import_media` then define a track node."
 *
 * Drift to "auto-seed an empty track node" would silently let
 * users dispatch generate_music against a placeholder track
 * without realising they hadn't bound the actual song. Pinning
 * the absence is the only line of defense.
 *
 * Pins three correctness contracts:
 *
 *  1. **`seededNodeIds` ordering**: per source,
 *     `[paletteId, performerId, conceptId, shotId]`. Drift would
 *     silently shuffle Output.seededNodeIds order downstream.
 *
 *  2. **`musicmv.track` is NOT seeded** (the marquee kdoc
 *     negative invariant). Drift to seed an empty track node
 *     would silently let users skip the import step.
 *
 *  3. **Parent-edge DAG topology**:
 *     - brand-palette → no parents (root)
 *     - performer (CharacterRef) → no parents (root)
 *     - visual-concept → [brand-palette]
 *     - performance-1 → [visual-concept, performer] **MARQUEE
 *       DUAL-PARENT PIN** (sister to ad's variant→[brief, product]
 *       and narrative's scene→[story, character]).
 *
 * Plus body-content + cross-binding pins:
 *   - brand-palette.hexColors = ["#000000"] (same single-black
 *     sentinel as Ad's brand-palette).
 *   - performer.name = "performer", visualDescription carries
 *     a "TODO:" placeholder.
 *   - visual-concept: logline / mood TODO placeholders;
 *     paletteRef = paletteId.value (cross-binding integrity —
 *     drift would silently break the visual-concept→palette
 *     reference link).
 *   - performance-1: performer = "performer" (cross-binding by
 *     NAME, NOT id — drift would silently mismatch the shot's
 *     performer reference); action carries "TODO:" placeholder.
 */
class MusicMvTemplateTest {

    private val paletteId = "brand-palette"
    private val performerId = "performer"
    private val conceptId = "visual-concept"
    private val shotId = "performance-1"

    // ── 1. seededNodeIds ordering + count ───────────────────

    @Test fun seededNodeIdsOrderingMatchesAddOrder() {
        val (_, ids) = seedMusicMvTemplate()
        assertEquals(
            listOf(paletteId, performerId, conceptId, shotId),
            ids,
            "seededNodeIds MUST match add-order: palette → performer → concept → shot",
        )
    }

    @Test fun seededNodeIdsHasExactlyFourEntries() {
        val (_, ids) = seedMusicMvTemplate()
        assertEquals(4, ids.size, "musicmv template seeds exactly 4 nodes (NOT 5 — track is excluded)")
    }

    // ── 2. musicmv.track is deliberately NOT seeded ─────────

    @Test fun trackNodeIsDeliberatelyNotSeeded() {
        // Marquee kdoc negative-invariant pin: per the file
        // kdoc, "The `musicmv.track` node is deliberately **not**
        // seeded because it requires an imported music asset id —
        // the caller nudges the user to `import_media` then
        // define a track node."
        //
        // Drift to "auto-seed an empty track" would silently let
        // users dispatch generate_music against a placeholder
        // track without realising they hadn't bound the actual
        // song. The absence pin is the ONLY line of defense.
        val (source, _) = seedMusicMvTemplate()
        val trackNodes = source.nodes.filter { it.kind == MusicMvNodeKinds.TRACK }
        assertTrue(
            trackNodes.isEmpty(),
            "musicmv.track node MUST NOT be seeded — kdoc explicitly excludes it; got: ${trackNodes.map { it.id.value }}",
        )
    }

    // ── 3. Source has all 4 nodes by ID + correct kinds ─────

    @Test fun sourceContainsAllFourNodeIds() {
        val (source, _) = seedMusicMvTemplate()
        val ids = source.nodes.map { it.id.value }.toSet()
        assertEquals(setOf(paletteId, performerId, conceptId, shotId), ids)
    }

    @Test fun sourceNodeKindsMatchKdocClassification() {
        val (source, _) = seedMusicMvTemplate()
        val kindByOd = source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.BRAND_PALETTE, kindByOd[paletteId])
        assertEquals(ConsistencyKinds.CHARACTER_REF, kindByOd[performerId])
        assertEquals(MusicMvNodeKinds.VISUAL_CONCEPT, kindByOd[conceptId])
        assertEquals(MusicMvNodeKinds.PERFORMANCE_SHOT, kindByOd[shotId])
    }

    // ── 4. Parent-edge DAG topology ─────────────────────────

    @Test fun brandPaletteIsRootNode() {
        assertTrue(parentsOf(paletteId).isEmpty(), "$paletteId MUST be a root")
    }

    @Test fun performerIsRootNode() {
        // Pin: performer (CharacterRef) is a root — drift to
        // "performer → palette" would silently couple the
        // performer to the palette (breaking the consistency-
        // binding model where character is independent of
        // visual style).
        assertTrue(parentsOf(performerId).isEmpty(), "$performerId MUST be a root")
    }

    @Test fun visualConceptHasBrandPaletteAsParent() {
        // Pin: edits to the palette propagate to the concept,
        // which in turn propagates to every performance shot.
        assertEquals(
            listOf(SourceRef(SourceNodeId(paletteId))),
            parentsOf(conceptId),
            "$conceptId MUST have $paletteId as its only parent",
        )
    }

    @Test fun performanceShotHasConceptAndPerformerAsParents() {
        // Marquee dual-parent pin: shot depends on BOTH the
        // visual concept (mood / look) AND the performer
        // (identity). Sister to AdTemplate's variant→[brief,
        // product] and Narrative's scene→[story, character].
        // Drift to either single-parent silently breaks half
        // the DAG propagation.
        val parents = parentsOf(shotId)
        assertEquals(
            listOf(SourceRef(SourceNodeId(conceptId)), SourceRef(SourceNodeId(performerId))),
            parents,
            "$shotId MUST have BOTH $conceptId AND $performerId as parents (in that order); got: $parents",
        )
    }

    // ── 5. Body-content defaults + cross-bindings ───────────

    @Test fun brandPaletteBodyHasSingleBlackHexSentinel() {
        // Pin: same default as Ad's brand-palette — single
        // black hex sentinel. Drift to empty list would silently
        // break renderers expecting hexColors[0].
        val body = nodeOf(paletteId).asBrandPalette()
        assertNotNull(body)
        assertEquals("brand-palette", body.name)
        assertEquals(listOf("#000000"), body.hexColors)
    }

    @Test fun performerBodyHasPerformerNamePlaceholder() {
        val body = nodeOf(performerId).asCharacterRef()
        assertNotNull(body)
        assertEquals("performer", body.name)
        assertEquals(
            "TODO: describe the performer's look",
            body.visualDescription,
        )
        // No reference assets / loraPin / voiceId by default —
        // the user fills in via the LLM agent later.
        assertTrue(body.referenceAssetIds.isEmpty())
        assertTrue(body.loraPin == null)
        assertTrue(body.voiceId == null)
    }

    @Test fun visualConceptBodyCarriesTodoLoglineAndPaletteCrossBinding() {
        // Marquee cross-binding pin: visual-concept.paletteRef
        // MUST equal paletteId.value. Drift would silently
        // break the concept→palette reference link (the concept
        // wouldn't pick up palette colors at fold time).
        val body = nodeOf(conceptId).asMusicMvVisualConcept()
        assertNotNull(body)
        assertEquals("TODO: one-sentence MV concept", body.logline)
        assertEquals("TODO: mood", body.mood)
        assertEquals(
            paletteId,
            body.paletteRef,
            "visual-concept.paletteRef MUST equal '$paletteId' (cross-binding pin)",
        )
    }

    @Test fun performanceShotBodyHasPerformerNameCrossBinding() {
        // Marquee cross-binding pin: shot.performer is a NAME
        // (string) reference, NOT an id. The seed sets it to
        // "performer" matching the CharacterRefBody.name. Drift
        // to "performer-1" / id form would silently break the
        // shot→performer link.
        val body = nodeOf(shotId).asMusicMvPerformanceShot()
        assertNotNull(body)
        assertEquals(
            "performer",
            body.performer,
            "shot.performer MUST equal performer-CharacterRef.name (cross-binding pin)",
        )
        assertEquals(
            "TODO: describe the performance beat this shot covers",
            body.action,
        )
    }

    // ── helpers ─────────────────────────────────────────────

    private fun parentsOf(id: String): List<SourceRef> {
        val (source, _) = seedMusicMvTemplate()
        return source.nodes.first { it.id.value == id }.parents
    }

    private fun nodeOf(id: String): io.talevia.core.domain.source.SourceNode {
        val (source, _) = seedMusicMvTemplate()
        return source.nodes.first { it.id.value == id }
    }
}
