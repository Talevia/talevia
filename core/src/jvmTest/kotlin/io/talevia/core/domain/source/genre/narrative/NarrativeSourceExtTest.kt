package io.talevia.core.domain.source.genre.narrative

import io.talevia.core.AssetId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.stale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for the Narrative genre's typed builders +
 * accessors in
 * `core/domain/source/genre/narrative/NarrativeSourceExt.kt`.
 * Four paired writer/reader extensions wrap the kind-tagged
 * `SourceNode.body` JsonElement encode/decode round-trip,
 * with an additional `parents` parameter for explicit DAG
 * wiring (world ← storyline ← scene ← shot). Cycle 150 audit:
 * 116 LOC, 0 transitive test refs (sister of
 * `VlogSourceExtTest` from cycle 149 — same template
 * extended with parents-edge coverage).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Each writer stamps the genre-namespaced kind
 *    constant.** `narrative.world` / `narrative.storyline` /
 *    `narrative.scene` / `narrative.shot`. Cross-genre
 *    namespace prefix prevents collision with vlog /
 *    musicmv / etc.
 *
 * 2. **Readers return null on kind mismatch — never throw.**
 *    Per kdoc: "callers can do kind-dispatch with a `when`/
 *    `let` chain without try/catch."
 *
 * 3. **`parents` parameter wires the DAG edge at construction
 *    time.** Per kdoc: "Wiring parents at construction is
 *    what lets edits to a world propagate through the DAG
 *    lane (`Source.stale`) to every downstream scene and
 *    shot." Default empty list — narrative authors that skip
 *    parents get a flat DAG (no propagation), which is a
 *    legitimate authoring shape but not the typical one.
 */
class NarrativeSourceExtTest {

    // ── kind constants (sanity) ──────────────────────────────────

    @Test fun kindConstantsUseGenreNamespacedConvention() {
        assertTrue(NarrativeNodeKinds.WORLD.startsWith("narrative."))
        assertTrue(NarrativeNodeKinds.STORYLINE.startsWith("narrative."))
        assertTrue(NarrativeNodeKinds.SCENE.startsWith("narrative."))
        assertTrue(NarrativeNodeKinds.SHOT.startsWith("narrative."))
    }

    // ── writers stamp correct kind ───────────────────────────────

    @Test fun addNarrativeWorldStampsWorldKind() {
        val source = Source.EMPTY.addNarrativeWorld(
            id = SourceNodeId("w1"),
            body = NarrativeWorldBody(name = "neo-tokyo", description = "cyberpunk 2087"),
        )
        assertEquals(NarrativeNodeKinds.WORLD, source.byId.getValue(SourceNodeId("w1")).kind)
    }

    @Test fun addNarrativeStorylineStampsStorylineKind() {
        val source = Source.EMPTY.addNarrativeStoryline(
            id = SourceNodeId("st1"),
            body = NarrativeStorylineBody(logline = "exiles return"),
        )
        assertEquals(NarrativeNodeKinds.STORYLINE, source.byId.getValue(SourceNodeId("st1")).kind)
    }

    @Test fun addNarrativeSceneStampsSceneKind() {
        val source = Source.EMPTY.addNarrativeScene(
            id = SourceNodeId("sc1"),
            body = NarrativeSceneBody(title = "border checkpoint"),
        )
        assertEquals(NarrativeNodeKinds.SCENE, source.byId.getValue(SourceNodeId("sc1")).kind)
    }

    @Test fun addNarrativeShotStampsShotKind() {
        val source = Source.EMPTY.addNarrativeShot(
            id = SourceNodeId("sh1"),
            body = NarrativeShotBody(sceneId = "sc1", framing = "close-up"),
        )
        assertEquals(NarrativeNodeKinds.SHOT, source.byId.getValue(SourceNodeId("sh1")).kind)
    }

    // ── readers return null on kind mismatch ─────────────────────

    @Test fun worldReaderReturnsNullForSceneNode() {
        val source = Source.EMPTY.addNarrativeScene(
            id = SourceNodeId("sc1"),
            body = NarrativeSceneBody(title = "x"),
        )
        assertNull(source.byId.getValue(SourceNodeId("sc1")).asNarrativeWorld())
    }

    @Test fun shotReaderReturnsNullForStorylineNode() {
        val source = Source.EMPTY.addNarrativeStoryline(
            id = SourceNodeId("st1"),
            body = NarrativeStorylineBody(logline = "x"),
        )
        assertNull(source.byId.getValue(SourceNodeId("st1")).asNarrativeShot())
    }

    @Test fun readersReturnNullForCrossGenreKind() {
        // Pin: a vlog kind read by a narrative accessor returns
        // null. Same kind-mismatch check, different genre origin.
        val foreignNode = SourceNode(
            id = SourceNodeId("foreign"),
            kind = "vlog.raw_footage",
        )
        assertNull(foreignNode.asNarrativeWorld())
        assertNull(foreignNode.asNarrativeStoryline())
        assertNull(foreignNode.asNarrativeScene())
        assertNull(foreignNode.asNarrativeShot())
    }

    // ── round-trip ──────────────────────────────────────────────

    @Test fun roundTripPreservesWorldBody() {
        val original = NarrativeWorldBody(
            name = "edo-japan",
            description = "1850s pre-restoration",
            era = "edo-period",
            referenceAssetIds = listOf(AssetId("ref-1"), AssetId("ref-2")),
        )
        val source = Source.EMPTY.addNarrativeWorld(SourceNodeId("w"), original)
        assertEquals(
            original,
            source.byId.getValue(SourceNodeId("w")).asNarrativeWorld(),
        )
    }

    @Test fun roundTripPreservesShotBodyWithOptionalsFilled() {
        // Pin: NarrativeShotBody has 5 optional / defaulted
        // fields. Round-trip preserves all combinations of
        // null / non-null exactly.
        val original = NarrativeShotBody(
            sceneId = "sc-7",
            framing = "wide",
            cameraMovement = "slow push-in",
            action = "she walks toward camera",
            dialogue = "you came back",
            speakerId = "ch-mei",
            targetDurationSeconds = 4.5,
        )
        val source = Source.EMPTY.addNarrativeShot(SourceNodeId("sh"), original)
        val readBack = source.byId.getValue(SourceNodeId("sh")).asNarrativeShot()
        assertEquals(original, readBack)
        // Spot-check: dialogue + speakerId are sibling optionals
        // (one without the other is meaningless); both round-
        // trip non-null.
        assertEquals("you came back", readBack!!.dialogue)
        assertEquals("ch-mei", readBack.speakerId)
    }

    @Test fun roundTripPreservesShotBodyWithAllOptionalsNull() {
        val original = NarrativeShotBody(sceneId = "sc-min")
        val source = Source.EMPTY.addNarrativeShot(SourceNodeId("sh-min"), original)
        val readBack = source.byId.getValue(SourceNodeId("sh-min")).asNarrativeShot()
        assertEquals(original, readBack)
        assertNull(readBack!!.dialogue, "optional null preserved")
        assertNull(readBack.speakerId)
        assertNull(readBack.targetDurationSeconds)
    }

    // ── parents wiring ──────────────────────────────────────────

    @Test fun addNarrativeWorldDefaultsParentsToEmptyList() {
        // Pin: parents default to []. World is typically the
        // root of a narrative DAG — no upstream nodes.
        val source = Source.EMPTY.addNarrativeWorld(
            id = SourceNodeId("w"),
            body = NarrativeWorldBody(name = "n", description = "d"),
        )
        assertEquals(emptyList(), source.byId.getValue(SourceNodeId("w")).parents)
    }

    @Test fun addNarrativeSceneAcceptsExplicitParents() {
        // The marquee parents-pass-through pin: scene wired to
        // world + storyline parents lets `Source.stale(world)`
        // mark the scene downstream (DAG propagation).
        val source = Source.EMPTY
            .addNarrativeWorld(SourceNodeId("w"), NarrativeWorldBody(name = "n", description = "d"))
            .addNarrativeStoryline(SourceNodeId("st"), NarrativeStorylineBody(logline = "x"))
            .addNarrativeScene(
                id = SourceNodeId("sc"),
                body = NarrativeSceneBody(title = "checkpoint"),
                parents = listOf(SourceRef(SourceNodeId("w")), SourceRef(SourceNodeId("st"))),
            )
        val sceneNode = source.byId.getValue(SourceNodeId("sc"))
        assertEquals(
            listOf(SourceRef(SourceNodeId("w")), SourceRef(SourceNodeId("st"))),
            sceneNode.parents,
            "parents threaded into the SourceNode at construction",
        )
    }

    @Test fun parentsWiringEnablesDagStalePropagation() {
        // Pin: the END-TO-END behavior the parents param
        // exists to enable. Edit world → world's stale closure
        // includes the downstream scene (via SourceMutations).
        // This is the semantic the kdoc cites as the rationale.
        val source = Source.EMPTY
            .addNarrativeWorld(SourceNodeId("w"), NarrativeWorldBody(name = "n", description = "d"))
            .addNarrativeScene(
                id = SourceNodeId("sc"),
                body = NarrativeSceneBody(title = "x"),
                parents = listOf(SourceRef(SourceNodeId("w"))),
            )
        // `stale(w)` returns the closure of nodes that an edit
        // to "w" would invalidate. With parents threaded, that
        // closure includes the scene.
        val staleClosure = source.stale(setOf(SourceNodeId("w")))
        assertTrue(
            SourceNodeId("sc") in staleClosure,
            "scene downstream of w is included in stale closure; got: $staleClosure",
        )
    }

    @Test fun parentsListAcceptsMultipleEdgesForOneNode() {
        // Pin: a shot wired to (scene, character_ref) gets both
        // parent edges. Both upstream invalidations propagate
        // to the same shot.
        val source = Source.EMPTY
            .addNarrativeScene(SourceNodeId("sc"), NarrativeSceneBody(title = "s"))
            .addNarrativeShot(
                id = SourceNodeId("sh"),
                body = NarrativeShotBody(sceneId = "sc", framing = "wide"),
                parents = listOf(
                    SourceRef(SourceNodeId("sc")),
                    SourceRef(SourceNodeId("character-mei")), // imagine a character_ref upstream
                ),
            )
        val shotNode = source.byId.getValue(SourceNodeId("sh"))
        assertEquals(2, shotNode.parents.size, "both parent edges wired")
    }

    // ── source mutation semantic ────────────────────────────────

    @Test fun multipleWritesAccumulateNodesInSource() {
        val source = Source.EMPTY
            .addNarrativeWorld(SourceNodeId("w"), NarrativeWorldBody(name = "n", description = "d"))
            .addNarrativeStoryline(SourceNodeId("st"), NarrativeStorylineBody(logline = "x"))
            .addNarrativeScene(SourceNodeId("sc"), NarrativeSceneBody(title = "y"))
            .addNarrativeShot(SourceNodeId("sh"), NarrativeShotBody(sceneId = "sc"))
        assertEquals(4, source.nodes.size)
        assertEquals(
            setOf(
                SourceNodeId("w"),
                SourceNodeId("st"),
                SourceNodeId("sc"),
                SourceNodeId("sh"),
            ),
            source.byId.keys,
        )
    }
}
