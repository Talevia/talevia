package io.talevia.core.tool.builtin.project.template

import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.domain.source.genre.vlog.VlogNodeKinds
import io.talevia.core.domain.source.genre.vlog.asVlogEditIntent
import io.talevia.core.domain.source.genre.vlog.asVlogRawFootage
import io.talevia.core.domain.source.genre.vlog.asVlogStylePreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [seedVlogTemplate] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/template/VlogTemplate.kt`.
 * Cycle 254 audit: 0 direct test refs (only the indirect tool-
 * dispatch test `vlogSeedsFourNodes` exercises it).
 *
 * Same audit-pattern fallback as cycles 207-253. Fifth and final
 * in the template-pin family after Narrative (250), Ad (251),
 * MusicMv (252), Tutorial (253).
 *
 * Distinguishing feature: **VlogTemplate is the only seeded
 * template where every node is a root** (no parent edges). The
 * other 4 templates wire DAG topology so edits propagate; vlog
 * intentionally keeps each node independent because the
 * style + footage + intent + preset interact only at compose
 * time (the LLM picks how to merge them per-export), NOT
 * structurally. Drift to wire any parent edge would silently
 * change vlog semantics from "independent ingredients" to
 * "DAG-coupled stages".
 *
 * Pins three correctness contracts:
 *
 *  1. **`seededNodeIds` ordering**: per source,
 *     `[styleId, footageId, intentId, presetId]`. Drift would
 *     silently shuffle Output.seededNodeIds order downstream.
 *
 *  2. **All 4 nodes are roots (no parent edges)**: marquee
 *     vlog-distinguishing pin. Drift to wire any parent edge
 *     silently changes vlog template semantics.
 *
 *  3. **Body-content defaults**:
 *     - style: name="style", description="TODO: describe the
 *       visual style".
 *     - footage: assetIds=[] (empty), notes="TODO: import
 *       footage and bind assetIds here" — canonical
 *       `import_media` hint.
 *     - intent: description="TODO: describe the editing intent
 *       / mood".
 *     - style-preset: name="style-preset" (matches the id; no
 *       description / TODO — the preset is just a slot).
 */
class VlogTemplateTest {

    private val styleId = "style"
    private val footageId = "footage"
    private val intentId = "intent"
    private val presetId = "style-preset"

    // ── 1. seededNodeIds ordering + count ───────────────────

    @Test fun seededNodeIdsOrderingMatchesAddOrder() {
        val (_, ids) = seedVlogTemplate()
        assertEquals(
            listOf(styleId, footageId, intentId, presetId),
            ids,
            "seededNodeIds MUST match add-order: style → footage → intent → preset",
        )
    }

    @Test fun seededNodeIdsHasExactlyFourEntries() {
        val (_, ids) = seedVlogTemplate()
        assertEquals(4, ids.size, "vlog template seeds exactly 4 nodes")
    }

    // ── 2. Source has all 4 nodes by ID + correct kinds ─────

    @Test fun sourceContainsAllFourNodeIds() {
        val (source, _) = seedVlogTemplate()
        val ids = source.nodes.map { it.id.value }.toSet()
        assertEquals(setOf(styleId, footageId, intentId, presetId), ids)
    }

    @Test fun sourceNodeKindsMatchKdocClassification() {
        val (source, _) = seedVlogTemplate()
        val kindByOd = source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.STYLE_BIBLE, kindByOd[styleId])
        assertEquals(VlogNodeKinds.RAW_FOOTAGE, kindByOd[footageId])
        assertEquals(VlogNodeKinds.EDIT_INTENT, kindByOd[intentId])
        assertEquals(VlogNodeKinds.STYLE_PRESET, kindByOd[presetId])
    }

    // ── 3. All 4 nodes are roots (vlog-distinguishing) ──────

    @Test fun everyNodeIsARootNode() {
        // Marquee vlog-distinguishing pin: per the kdoc-implied
        // design, vlog templates do NOT wire DAG edges because
        // style + footage + intent + preset interact only at
        // compose time (the LLM picks how to merge them per-
        // export), NOT structurally. Drift to wire any parent
        // edge silently changes vlog template semantics from
        // "independent ingredients" to "DAG-coupled stages".
        val (source, _) = seedVlogTemplate()
        for (node in source.nodes) {
            assertTrue(
                node.parents.isEmpty(),
                "${node.id.value} MUST be a root in vlog template (no parent edges); got: ${node.parents}",
            )
        }
    }

    @Test fun sourceContainsZeroEdges() {
        // Sister pin: aggregate count — total edge count across
        // all nodes is 0. Drift to "wire any single edge" would
        // surface here even if a refactor distributes edges
        // across nodes in a way the per-node pin misses.
        val (source, _) = seedVlogTemplate()
        val totalEdges = source.nodes.sumOf { it.parents.size }
        assertEquals(
            0,
            totalEdges,
            "vlog template MUST have ZERO total parent edges across all nodes",
        )
    }

    // ── 4. Body-content defaults ────────────────────────────

    @Test fun styleBibleBodyHasStylePlaceholder() {
        val body = nodeOf(styleId).asStyleBible()
        assertNotNull(body)
        assertEquals("style", body.name)
        assertEquals("TODO: describe the visual style", body.description)
    }

    @Test fun rawFootageBodyHasEmptyAssetsAndCanonicalImportHint() {
        // Marquee canonical-hint pin: the notes string is the
        // user's onboarding pointer to `import_media`. Drift
        // would silently change the first-run UX guidance.
        val body = nodeOf(footageId).asVlogRawFootage()
        assertNotNull(body)
        assertEquals(
            emptyList(),
            body.assetIds,
            "raw-footage.assetIds MUST default to empty (user fills via import_media)",
        )
        assertEquals(
            "TODO: import footage and bind assetIds here",
            body.notes,
            "raw-footage.notes MUST carry the canonical import-media hint",
        )
    }

    @Test fun editIntentBodyHasIntentMoodPlaceholder() {
        // Pin: per source, description = "TODO: describe the
        // editing intent / mood". Drift to drop "/ mood" or
        // change wording silently changes guidance.
        val body = nodeOf(intentId).asVlogEditIntent()
        assertNotNull(body)
        assertEquals(
            "TODO: describe the editing intent / mood",
            body.description,
        )
    }

    @Test fun stylePresetBodyHasNameMatchingId() {
        // Pin: style-preset.name = "style-preset" (matches the
        // node id). Drift to a different name silently confuses
        // users searching for the preset by name vs id.
        val body = nodeOf(presetId).asVlogStylePreset()
        assertNotNull(body)
        assertEquals(
            "style-preset",
            body.name,
            "style-preset.name MUST equal the node id (drift would mismatch)",
        )
    }

    // ── helpers ─────────────────────────────────────────────

    private fun nodeOf(id: String): io.talevia.core.domain.source.SourceNode {
        val (source, _) = seedVlogTemplate()
        return source.nodes.first { it.id.value == id }
    }
}
