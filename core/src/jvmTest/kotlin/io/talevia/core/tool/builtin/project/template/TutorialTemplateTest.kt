package io.talevia.core.tool.builtin.project.template

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.domain.source.genre.tutorial.TutorialNodeKinds
import io.talevia.core.domain.source.genre.tutorial.asTutorialBrandSpec
import io.talevia.core.domain.source.genre.tutorial.asTutorialBrollLibrary
import io.talevia.core.domain.source.genre.tutorial.asTutorialScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [seedTutorialTemplate] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/template/TutorialTemplate.kt`.
 * Cycle 253 audit: 0 direct test refs (only the indirect tool-
 * dispatch test `tutorialSeedsFourNodes` exercises it).
 *
 * Same audit-pattern fallback as cycles 207-252. Fourth in the
 * template-pin family after Narrative (250), Ad (251), MusicMv
 * (252).
 *
 * Pins three correctness contracts:
 *
 *  1. **`seededNodeIds` ordering**: per source,
 *     `[styleId, brandId, scriptId, brollId]`. Drift would
 *     silently shuffle Output.seededNodeIds order downstream.
 *
 *  2. **Parent-edge DAG topology**:
 *     - style (StyleBible) → no parents (root)
 *     - brand-spec → no parents (root)
 *     - script → [style, brand-spec] **MARQUEE DUAL-PARENT
 *       PIN** — drift to either single-parent silently breaks
 *       half the propagation chain (style → script for visual
 *       look; brand-spec → script for product name lookup).
 *     - broll → no parents (root) — drift to "broll →
 *       script" or "broll → brand" would silently couple the
 *       broll library to upstream changes that shouldn't
 *       cascade (b-roll is generic visual filler, NOT
 *       script-specific).
 *
 *  3. **Body-content defaults + concrete-list defaults**:
 *     - style: name="style", description="TODO: describe the
 *       visual style".
 *     - brand-spec: productName="TODO: product name",
 *       brandColors=[] (empty by design — drift to default
 *       black sentinel like Ad's palette would silently
 *       provide a color the user didn't ask for),
 *       lowerThirdStyle="TODO: lower-third treatment".
 *     - script: title / spokenText TODO placeholders +
 *       **`segments=["intro", "demo", "wrap"]`** marquee
 *       concrete-default pin — these 3 segments capture
 *       Tutorial template intent (drift would silently change
 *       the canonical tutorial structure).
 *     - broll: assetIds=[] (empty — placeholder for user
 *       imports), notes="TODO: import screen-capture / demo
 *       clips and bind assetIds here" (the kdoc-canonical
 *       hint to the user).
 */
class TutorialTemplateTest {

    private val styleId = "style"
    private val brandId = "brand-spec"
    private val scriptId = "script"
    private val brollId = "broll"

    // ── 1. seededNodeIds ordering + count ───────────────────

    @Test fun seededNodeIdsOrderingMatchesAddOrder() {
        val (_, ids) = seedTutorialTemplate()
        assertEquals(
            listOf(styleId, brandId, scriptId, brollId),
            ids,
            "seededNodeIds MUST match add-order: style → brand → script → broll",
        )
    }

    @Test fun seededNodeIdsHasExactlyFourEntries() {
        val (_, ids) = seedTutorialTemplate()
        assertEquals(4, ids.size, "tutorial template seeds exactly 4 nodes")
    }

    // ── 2. Source has all 4 nodes by ID + correct kinds ─────

    @Test fun sourceContainsAllFourNodeIds() {
        val (source, _) = seedTutorialTemplate()
        val ids = source.nodes.map { it.id.value }.toSet()
        assertEquals(setOf(styleId, brandId, scriptId, brollId), ids)
    }

    @Test fun sourceNodeKindsMatchKdocClassification() {
        val (source, _) = seedTutorialTemplate()
        val kindByOd = source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.STYLE_BIBLE, kindByOd[styleId])
        assertEquals(TutorialNodeKinds.BRAND_SPEC, kindByOd[brandId])
        assertEquals(TutorialNodeKinds.SCRIPT, kindByOd[scriptId])
        assertEquals(TutorialNodeKinds.BROLL_LIBRARY, kindByOd[brollId])
    }

    // ── 3. Parent-edge DAG topology ─────────────────────────

    @Test fun styleIsRootNode() {
        assertTrue(parentsOf(styleId).isEmpty(), "$styleId MUST be a root")
    }

    @Test fun brandSpecIsRootNode() {
        assertTrue(parentsOf(brandId).isEmpty(), "$brandId MUST be a root")
    }

    @Test fun brollLibraryIsRootNode() {
        // Pin: per the kdoc-implied design, b-roll is generic
        // visual filler — drift to "broll → script" or
        // "broll → brand" would silently couple b-roll to
        // upstream changes that shouldn't cascade (re-edits to
        // the script shouldn't invalidate b-roll thumbnails).
        assertTrue(
            parentsOf(brollId).isEmpty(),
            "$brollId MUST be a root (b-roll is generic visual filler, NOT script-specific)",
        )
    }

    @Test fun scriptHasStyleAndBrandSpecAsParents() {
        // Marquee dual-parent pin: script depends on BOTH the
        // style bible (visual look references / mood) AND the
        // brand-spec (product name / lower-third treatment).
        // Drift to either single-parent silently breaks half
        // the DAG propagation chain.
        val parents = parentsOf(scriptId)
        assertEquals(
            listOf(SourceRef(SourceNodeId(styleId)), SourceRef(SourceNodeId(brandId))),
            parents,
            "$scriptId MUST have BOTH $styleId AND $brandId as parents (in that order); got: $parents",
        )
    }

    // ── 4. Body-content defaults ────────────────────────────

    @Test fun styleBibleBodyHasStylePlaceholder() {
        val body = nodeOf(styleId).asStyleBible()
        assertNotNull(body)
        assertEquals("style", body.name)
        assertEquals("TODO: describe the visual style", body.description)
    }

    @Test fun brandSpecBodyHasEmptyBrandColorsAndTodoFields() {
        // Marquee empty-list pin: brandColors is `[]` (empty)
        // by design — Tutorial template intentionally doesn't
        // pre-populate a color sentinel because the user's
        // brand colors are NOT yet known. Drift to default
        // black sentinel (like Ad's palette) would silently
        // provide a color the user didn't ask for, which then
        // ships through to the rendered tutorial.
        val body = nodeOf(brandId).asTutorialBrandSpec()
        assertNotNull(body)
        assertEquals("TODO: product name", body.productName)
        assertEquals(
            emptyList(),
            body.brandColors,
            "brandColors MUST default to empty (NOT a sentinel color); got: ${body.brandColors}",
        )
        assertEquals(
            "TODO: lower-third treatment",
            body.lowerThirdStyle,
        )
    }

    @Test fun scriptBodyHasCanonicalThreeSegmentsIntroDemoWrap() {
        // Marquee concrete-default pin: segments=["intro",
        // "demo", "wrap"] is the canonical Tutorial template
        // structure. Drift to a different segment list (e.g.
        // ["intro", "body", "outro"] or ["start", "middle",
        // "end"]) would silently change what fresh-tutorial
        // users see as the recommended structure.
        val body = nodeOf(scriptId).asTutorialScript()
        assertNotNull(body)
        assertEquals("TODO: tutorial title", body.title)
        assertEquals("TODO: voiceover script", body.spokenText)
        assertEquals(
            listOf("intro", "demo", "wrap"),
            body.segments,
            "Tutorial segments MUST be exactly ['intro', 'demo', 'wrap'] (canonical template intent)",
        )
    }

    @Test fun brollLibraryBodyHasEmptyAssetsAndImportHint() {
        // Marquee empty-list + canonical-hint pin: assetIds
        // is empty by design (user's imported clips fill it
        // in later); notes string is the canonical hint
        // pointing at `import_media`. Drift in either silently
        // changes onboarding UX.
        val body = nodeOf(brollId).asTutorialBrollLibrary()
        assertNotNull(body)
        assertEquals(
            emptyList(),
            body.assetIds,
            "broll-library.assetIds MUST default to empty",
        )
        assertEquals(
            "TODO: import screen-capture / demo clips and bind assetIds here",
            body.notes,
            "broll-library.notes MUST carry the canonical import-media hint",
        )
    }

    // ── helpers ─────────────────────────────────────────────

    private fun parentsOf(id: String): List<SourceRef> {
        val (source, _) = seedTutorialTemplate()
        return source.nodes.first { it.id.value == id }.parents
    }

    private fun nodeOf(id: String): io.talevia.core.domain.source.SourceNode {
        val (source, _) = seedTutorialTemplate()
        return source.nodes.first { it.id.value == id }
    }
}
