package io.talevia.core.tool.builtin.project.template

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.consistency.ConsistencyKinds
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.domain.source.genre.narrative.NarrativeNodeKinds
import io.talevia.core.domain.source.genre.narrative.asNarrativeScene
import io.talevia.core.domain.source.genre.narrative.asNarrativeShot
import io.talevia.core.domain.source.genre.narrative.asNarrativeStoryline
import io.talevia.core.domain.source.genre.narrative.asNarrativeWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Direct tests for [seedNarrativeTemplate] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/template/NarrativeTemplate.kt`.
 * Cycle 250 audit: only INDIRECT coverage exists
 * (`CreateProjectFromTemplateToolTest.narrativeSeedsSixNodesAndWiresDag`
 * pins kinds + size via `ProjectLifecycleActionTool.create_from_template`
 * dispatch, but NOT the seededNodeIds ordering, parent edges, or
 * body-content placeholder text).
 *
 * Same audit-pattern fallback as cycles 207-249.
 *
 * `seedNarrativeTemplate` is the function the project-create
 * dispatcher calls when a user picks `template = "narrative"`.
 * Drift in:
 *
 *   - `seededNodeIds` ordering → silently changes Output.echo
 *     ordering downstream consumers see.
 *   - parent edges → silently breaks DAG propagation (an edit
 *     to the style bible no longer flows through to scene / shot
 *     stale, the marquee user-visible value of the template).
 *   - body placeholder text → silently changes what fresh-project
 *     users see when they open a freshly-created narrative.
 *
 * The kdoc explicitly says "Wired via `parents` so DAG
 * propagation works from day zero" — pinning the parents is
 * what enforces that promise.
 *
 * Pins three correctness contracts:
 *
 *  1. **`seededNodeIds` ordering**: per the source
 *     `return s to listOf(characterId.value, styleId.value,
 *     worldId.value, storyId.value, sceneId.value, shotId.value)`.
 *     Drift would change `Output.seededNodeIds` order downstream.
 *
 *  2. **Parent-edge DAG topology**:
 *     - protagonist (CharacterRef) → no parents (root)
 *     - style (StyleBible) → no parents (root)
 *     - world-1 (NarrativeWorld) → [style]
 *     - story-1 (NarrativeStoryline) → [world-1]
 *     - scene-1 (NarrativeScene) → [story-1, protagonist]
 *     - shot-1 (NarrativeShot) → [scene-1]
 *     Drift in any single edge silently breaks DAG propagation.
 *
 *  3. **Body-content placeholders**: each node carries a
 *     specific `"TODO: ..."` string. Drift would silently change
 *     the user's first impression of a fresh project (e.g. drop
 *     "TODO:" prefix → users no longer see the convention they
 *     can grep for to find what to fill in).
 *
 * Plus structural pins:
 *   - 6 nodes returned in the Source.
 *   - Cross-binding integrity: scene's `characterIds` list
 *     contains the protagonist's id; shot's `sceneId` field
 *     references the scene. Drift would silently break the
 *     foundational consistency-binding wiring the template
 *     advertises as "DAG propagation works from day zero".
 */
class NarrativeTemplateTest {

    private val characterId = "protagonist"
    private val styleId = "style"
    private val worldId = "world-1"
    private val storyId = "story-1"
    private val sceneId = "scene-1"
    private val shotId = "shot-1"

    // ── 1. seededNodeIds ordering ───────────────────────────

    @Test fun seededNodeIdsOrderingMatchesKdocPromise() {
        // Marquee ordering pin: drift to alphabetical / different
        // sequence would silently shuffle Output.seededNodeIds
        // downstream.
        val (_, ids) = seedNarrativeTemplate()
        assertEquals(
            listOf(characterId, styleId, worldId, storyId, sceneId, shotId),
            ids,
            "seededNodeIds MUST match the canonical add-order: character → style → world → story → scene → shot",
        )
    }

    @Test fun seededNodeIdsHasExactlySixEntries() {
        val (_, ids) = seedNarrativeTemplate()
        assertEquals(6, ids.size, "narrative template seeds exactly 6 nodes")
    }

    // ── 2. Source has exactly 6 nodes by ID ─────────────────

    @Test fun sourceContainsAllSixNodeIds() {
        val (source, _) = seedNarrativeTemplate()
        val ids = source.nodes.map { it.id.value }.toSet()
        assertEquals(
            setOf(characterId, styleId, worldId, storyId, sceneId, shotId),
            ids,
            "source MUST contain exactly the 6 documented node IDs",
        )
    }

    @Test fun sourceNodeKindsMatchKdocClassification() {
        // Sister to existing CreateProjectFromTemplateToolTest pin
        // (this one runs at function level, not via tool dispatch).
        // Drift in any kind would mismatch the kdoc.
        val (source, _) = seedNarrativeTemplate()
        val kindByOd = source.nodes.associate { it.id.value to it.kind }
        assertEquals(ConsistencyKinds.CHARACTER_REF, kindByOd[characterId])
        assertEquals(ConsistencyKinds.STYLE_BIBLE, kindByOd[styleId])
        assertEquals(NarrativeNodeKinds.WORLD, kindByOd[worldId])
        assertEquals(NarrativeNodeKinds.STORYLINE, kindByOd[storyId])
        assertEquals(NarrativeNodeKinds.SCENE, kindByOd[sceneId])
        assertEquals(NarrativeNodeKinds.SHOT, kindByOd[shotId])
    }

    // ── 3. Parent-edge DAG topology ─────────────────────────

    @Test fun characterRefIsRootNode() {
        val parents = parentsOf(characterId)
        assertTrue(
            parents.isEmpty(),
            "$characterId MUST be a root (no parents); got: $parents",
        )
    }

    @Test fun styleBibleIsRootNode() {
        val parents = parentsOf(styleId)
        assertTrue(
            parents.isEmpty(),
            "$styleId MUST be a root; got: $parents",
        )
    }

    @Test fun worldHasStyleBibleAsParent() {
        // Marquee DAG-edge pin: an edit to the style bible
        // propagates through world → storyline → scene → shot.
        // This single edge is the entrypoint of the chain.
        assertEquals(
            listOf(SourceRef(SourceNodeId(styleId))),
            parentsOf(worldId),
            "$worldId MUST have $styleId as its only parent",
        )
    }

    @Test fun storyHasWorldAsParent() {
        assertEquals(
            listOf(SourceRef(SourceNodeId(worldId))),
            parentsOf(storyId),
            "$storyId MUST have $worldId as its only parent",
        )
    }

    @Test fun sceneHasStoryAndCharacterAsParents() {
        // Marquee dual-parent pin: scene depends on BOTH the
        // story (for plot context) AND the character (for who's
        // in the scene). Drift to "story only" silently breaks
        // character-update propagation; drift to "character only"
        // breaks plot-update propagation.
        val parents = parentsOf(sceneId)
        assertEquals(
            listOf(SourceRef(SourceNodeId(storyId)), SourceRef(SourceNodeId(characterId))),
            parents,
            "$sceneId MUST have BOTH $storyId AND $characterId as parents (in that order); got: $parents",
        )
    }

    @Test fun shotHasSceneAsParent() {
        assertEquals(
            listOf(SourceRef(SourceNodeId(sceneId))),
            parentsOf(shotId),
            "$shotId MUST have $sceneId as its only parent",
        )
    }

    // ── 4. Body-content placeholders ────────────────────────

    @Test fun characterRefBodyHasProtagonistPlaceholder() {
        val body = nodeOf(characterId).asCharacterRef()
        assertNotNull(body, "$characterId MUST decode as CharacterRefBody")
        assertEquals("protagonist", body.name)
        assertEquals(
            "TODO: describe the protagonist",
            body.visualDescription,
            "drift to drop 'TODO:' prefix would silently change the convention users grep for",
        )
        // No reference assets / loraPin / voiceId by default.
        assertTrue(body.referenceAssetIds.isEmpty(), "no default reference assets")
        assertTrue(body.loraPin == null, "no default LoRA pin")
        assertTrue(body.voiceId == null, "no default voiceId")
    }

    @Test fun styleBibleBodyHasStylePlaceholder() {
        val body = nodeOf(styleId).asStyleBible()
        assertNotNull(body)
        assertEquals("style", body.name)
        assertEquals("TODO: describe the visual style", body.description)
    }

    @Test fun worldBodyHasWorldPlaceholder() {
        val body = nodeOf(worldId).asNarrativeWorld()
        assertNotNull(body)
        assertEquals("world", body.name)
        assertEquals("TODO: describe the world / setting", body.description)
    }

    @Test fun storylineBodyHasOneSentencePitchPlaceholder() {
        val body = nodeOf(storyId).asNarrativeStoryline()
        assertNotNull(body)
        assertEquals("TODO: one-sentence pitch", body.logline)
    }

    @Test fun sceneBodyHasOpeningTitleAndCharacterBinding() {
        // Marquee scene-binding pin: scene title is `"opening"`
        // (drift would change first-scene UX); action is
        // placeholder; characterIds list contains the protagonist's
        // id (cross-binding integrity — drift to empty list would
        // silently break the foundational character→scene wiring).
        val body = nodeOf(sceneId).asNarrativeScene()
        assertNotNull(body)
        assertEquals("opening", body.title)
        assertEquals(
            "TODO: describe what happens in the opening scene",
            body.action,
        )
        assertEquals(
            listOf(characterId),
            body.characterIds,
            "scene.characterIds MUST contain the protagonist's id (cross-binding pin)",
        )
    }

    @Test fun shotBodyHasSceneBindingAndWideFraming() {
        // Marquee shot-binding pin: shot.sceneId references the
        // scene's id (cross-binding); framing is "wide" (the
        // documented opening-shot default); action is placeholder.
        val body = nodeOf(shotId).asNarrativeShot()
        assertNotNull(body)
        assertEquals(
            sceneId,
            body.sceneId,
            "shot.sceneId MUST reference the scene's id (cross-binding pin)",
        )
        assertEquals(
            "wide",
            body.framing,
            "default opening-shot framing MUST be 'wide' (drift to 'medium' / 'close-up' silently changes UX)",
        )
        assertEquals(
            "TODO: describe the first shot",
            body.action,
        )
    }

    // ── helpers ─────────────────────────────────────────────

    private fun parentsOf(id: String): List<SourceRef> {
        val (source, _) = seedNarrativeTemplate()
        return source.nodes.first { it.id.value == id }.parents
    }

    private fun nodeOf(id: String): io.talevia.core.domain.source.SourceNode {
        val (source, _) = seedNarrativeTemplate()
        return source.nodes.first { it.id.value == id }
    }
}
