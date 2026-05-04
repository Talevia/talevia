package io.talevia.core.tool.builtin.aigc

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.consistency.BrandPaletteBody
import io.talevia.core.domain.source.consistency.CharacterRefBody
import io.talevia.core.domain.source.consistency.StyleBibleBody
import io.talevia.core.domain.source.consistency.addBrandPalette
import io.talevia.core.domain.source.consistency.addCharacterRef
import io.talevia.core.domain.source.consistency.addStyleBible
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests for [AigcPipeline.foldPrompt] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/aigc/AigcPipeline.kt:69`.
 * Cycle 294 audit: only indirect coverage (via E2E tests
 * `RefactorLoopE2ETest`); the load-bearing 3-state
 * `bindingIds` semantic (null=auto / []=explicit-none /
 * non-empty=specific) had no unit-level pin.
 *
 * Same audit-pattern fallback as cycles 207-293.
 *
 * `AigcPipeline.foldPrompt` is the wrapper every AIGC tool
 * (`generate_image` / `generate_video` / `synthesize_speech` /
 * `generate_music`) calls before dispatching to the provider.
 * Drift in the 3-state semantic silently changes which
 * consistency nodes get folded into the prompt across
 * the entire fleet.
 *
 * Drift signals:
 *   - **Drift to treat null as []** (no folding) → silently
 *     disables auto-fold; `consistencyBindingIds=null`
 *     stops picking up project-wide consistency nodes.
 *   - **Drift to treat [] as null** (auto-fold) → caller
 *     can no longer explicitly opt out of folding.
 *   - **Drift to ignore listed bindingIds and fold
 *     everything** → silent overreach when caller wanted
 *     a specific subset.
 *   - **Drift in non-consistency-kind drop** → AIGC tools
 *     would surface garbage from non-consistency source
 *     nodes if a stale id is passed in `consistencyBindingIds`.
 *
 * Pins via direct construction of Project + Source +
 * consistency nodes via the `addStyleBible` / `addBrandPalette`
 * / `addCharacterRef` extension idiom (matches
 * [io.talevia.core.domain.source.consistency.PromptFoldingTest]).
 *
 * Note: `AigcPipeline` is `internal object` — accessible
 * from same-module tests via the package boundary.
 */
class AigcPipelineFoldPromptTest {

    private val pid = ProjectId("p1")

    private fun projectWithSource(source: Source): Project = Project(
        id = pid,
        timeline = Timeline(),
        source = source,
    )

    private fun projectWithStyle(): Project = projectWithSource(
        Source.EMPTY.addStyleBible(
            SourceNodeId("style-warm"),
            StyleBibleBody(
                name = "cinematic-warm",
                description = "warm teal/orange",
                moodKeywords = listOf("warm"),
            ),
        ),
    )

    private fun projectWithStyleAndCharacter(): Project = projectWithSource(
        Source.EMPTY
            .addStyleBible(
                SourceNodeId("style-warm"),
                StyleBibleBody(name = "cinematic-warm", description = "warm teal/orange"),
            )
            .addCharacterRef(
                SourceNodeId("char-mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair, round glasses"),
            ),
    )

    private fun projectWithEverything(): Project = projectWithSource(
        Source.EMPTY
            .addStyleBible(
                SourceNodeId("style-warm"),
                StyleBibleBody(name = "warm", description = "warm look"),
            )
            .addBrandPalette(
                SourceNodeId("brand-acme"),
                BrandPaletteBody(name = "acme", hexColors = listOf("#FF0000")),
            )
            .addCharacterRef(
                SourceNodeId("char-mei"),
                CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
            ),
    )

    // ── Three-state bindingIds semantic ─────────────────────

    @Test fun nullBindingsAutoFoldsAllProjectConsistencyNodes() {
        // Marquee state-1 pin: null bindingIds → auto-fold
        // EVERY consistency node in the project. Drift to
        // treat null as []-equivalent silently disables the
        // VISION §5.5 "cross-shot consistency without
        // explicit wiring" feature.
        val project = projectWithEverything()
        val folded = AigcPipeline.foldPrompt(
            project = project,
            basePrompt = "a sunset",
            bindingIds = null,
        )
        // All 3 nodes (style + brand + character) should be
        // applied.
        assertEquals(
            listOf("style-warm", "brand-acme", "char-mei").toSet(),
            folded.appliedNodeIds.toSet(),
            "null bindingIds MUST auto-fold ALL consistency nodes (3 distinct ids)",
        )
        assertTrue(
            "Style:" in folded.effectivePrompt,
            "auto-folded prompt MUST contain Style: fragment",
        )
        assertTrue(
            "Brand:" in folded.effectivePrompt,
            "auto-folded prompt MUST contain Brand: fragment",
        )
        assertTrue(
            "Character \"Mei\"" in folded.effectivePrompt,
            "auto-folded prompt MUST contain Character: fragment",
        )
    }

    @Test fun emptyBindingsExplicitlyOptsOutOfFolding() {
        // Marquee state-2 pin: empty bindingIds list →
        // explicit "no folding" — the SAME project that
        // auto-folds 3 nodes for `null` produces ZERO
        // applied nodes for `[]`. Drift to treat [] as
        // auto-fold equivalent silently breaks the explicit
        // opt-out path.
        val project = projectWithEverything()
        val folded = AigcPipeline.foldPrompt(
            project = project,
            basePrompt = "a sunset",
            bindingIds = emptyList(),
        )
        assertTrue(
            folded.appliedNodeIds.isEmpty(),
            "empty bindingIds MUST result in NO applied nodes (drift would surface here as ≥1 applied)",
        )
        // The base prompt MUST round-trip unchanged on
        // explicit opt-out (no Style:/Brand:/Character:
        // fragments).
        assertEquals(
            "a sunset",
            folded.effectivePrompt,
            "empty bindingIds MUST leave base prompt unchanged",
        )
        assertFalse(
            "Style:" in folded.effectivePrompt,
            "empty bindingIds MUST NOT inject Style: fragment",
        )
    }

    @Test fun nonEmptyBindingsFoldsOnlyTheListedSubset() {
        // Marquee state-3 pin: non-empty bindingIds folds
        // ONLY the listed nodes. Drift to fold everything
        // would silently overreach; drift to fold none would
        // silently underreach.
        val project = projectWithEverything()
        // Project has 3 consistency nodes; bind only the
        // style.
        val folded = AigcPipeline.foldPrompt(
            project = project,
            basePrompt = "a sunset",
            bindingIds = listOf(SourceNodeId("style-warm")),
        )
        assertEquals(
            listOf("style-warm"),
            folded.appliedNodeIds,
            "non-empty bindingIds MUST fold ONLY the listed subset (style, NOT brand or character)",
        )
        assertTrue(
            "Style:" in folded.effectivePrompt,
            "the listed style MUST be folded",
        )
        assertFalse(
            "Brand:" in folded.effectivePrompt,
            "unlisted brand MUST NOT be folded",
        )
        assertFalse(
            "Character" in folded.effectivePrompt,
            "unlisted character MUST NOT be folded",
        )
    }

    // ── Edge cases for non-empty bindings ──────────────────

    @Test fun nonEmptyBindingsDropsUnknownIdsSilently() {
        // Pin: per resolveConsistencyBindings, ids pointing
        // at missing nodes are silently dropped — better to
        // skip than crash the generation. Drift to throw on
        // unknown ids would surface as a generation failure
        // when stale binding ids slip through.
        val project = projectWithStyle()
        val folded = AigcPipeline.foldPrompt(
            project = project,
            basePrompt = "a sunset",
            bindingIds = listOf(
                SourceNodeId("style-warm"),       // exists
                SourceNodeId("does-not-exist"),   // unknown — silently dropped
            ),
        )
        assertEquals(
            listOf("style-warm"),
            folded.appliedNodeIds,
            "unknown ids MUST be silently dropped (resolved-ids-only invariant)",
        )
    }

    @Test fun nonEmptyBindingsDropsNonConsistencyKindIds() {
        // Pin: even when the id resolves to an existing
        // node, non-consistency-kind nodes are dropped. Pin
        // forms a stable boundary between "id missing" and
        // "id pointing at a non-consistency node" — both
        // get the same silent-drop behavior.
        val nonConsistencyNode = SourceNode(
            id = SourceNodeId("vlog-intent-1"),
            kind = "vlog.edit_intent",
            body = JsonObject(emptyMap()),
            parents = emptyList(),
        )
        // Build source with BOTH a style node + a non-
        // consistency node coexisting.
        val sourceWithStyle = Source.EMPTY.addStyleBible(
            SourceNodeId("style-warm"),
            StyleBibleBody("warm", "warm look"),
        )
        val mixedSource = sourceWithStyle.copy(
            nodes = sourceWithStyle.nodes + nonConsistencyNode,
        )
        val project = projectWithSource(mixedSource)

        val folded = AigcPipeline.foldPrompt(
            project = project,
            basePrompt = "a sunset",
            bindingIds = listOf(
                SourceNodeId("style-warm"),
                SourceNodeId("vlog-intent-1"), // exists but wrong kind — silently dropped
            ),
        )
        assertEquals(
            listOf("style-warm"),
            folded.appliedNodeIds,
            "non-consistency-kind ids MUST be silently dropped (only consistency kinds get folded)",
        )
    }

    // ── Auto-fold edge: project with no consistency nodes ─

    @Test fun nullBindingsOnEmptyProjectReturnsBasePromptUnchanged() {
        // Edge: null bindingIds + project with no
        // consistency nodes → equivalent to empty bindings,
        // base prompt round-trips. Drift in either direction
        // (e.g., auto-fold throws on empty source) would
        // surface here.
        val project = projectWithSource(Source.EMPTY)
        val folded = AigcPipeline.foldPrompt(
            project = project,
            basePrompt = "a sunset",
            bindingIds = null,
        )
        assertEquals("a sunset", folded.effectivePrompt)
        assertTrue(folded.appliedNodeIds.isEmpty())
    }

    @Test fun emptyBindingsOnEmptyProjectAlsoReturnsBasePromptUnchanged() {
        // Sister edge: explicit-none + empty source MUST be
        // identical to null + empty source.
        val project = projectWithSource(Source.EMPTY)
        val folded = AigcPipeline.foldPrompt(
            project = project,
            basePrompt = "a sunset",
            bindingIds = emptyList(),
        )
        assertEquals("a sunset", folded.effectivePrompt)
        assertTrue(folded.appliedNodeIds.isEmpty())
    }

    // ── Base prompt preservation across all 3 states ──────

    @Test fun basePromptAlwaysAtTailOfEffectivePromptAcrossAllThreeStates() {
        // Marquee tail-attention pin: per the
        // foldConsistencyIntoPrompt doc, base prompt sits at
        // the TAIL of the effective prompt because models
        // pay more attention there. Drift to put base at
        // the head would silently weaken every AIGC call's
        // subject signal.
        val project = projectWithStyleAndCharacter()
        val basePrompt = "a-distinctive-base-marker"

        // State 1: null bindings (auto-fold).
        val auto = AigcPipeline.foldPrompt(project, basePrompt, bindingIds = null)
        assertTrue(
            auto.effectivePrompt.endsWith(basePrompt),
            "auto-fold MUST place base prompt at tail; got: ${auto.effectivePrompt.takeLast(40)}",
        )

        // State 2: empty bindings — only base prompt
        // survives, so it IS the entire effective prompt.
        val none = AigcPipeline.foldPrompt(project, basePrompt, bindingIds = emptyList())
        assertEquals(basePrompt, none.effectivePrompt)

        // State 3: subset bindings.
        val subset = AigcPipeline.foldPrompt(
            project,
            basePrompt,
            bindingIds = listOf(SourceNodeId("style-warm")),
        )
        assertTrue(
            subset.effectivePrompt.endsWith(basePrompt),
            "subset-fold MUST place base prompt at tail; got: ${subset.effectivePrompt.takeLast(40)}",
        )
    }

    // ── appliedNodeIds ordering ────────────────────────────

    @Test fun appliedNodeIdsFollowDeclarationOrderNotFoldOrder() {
        // Marquee ordering pin: appliedNodeIds reflects the
        // DECLARATION order in source.nodes (NOT the fold
        // order in the effective prompt text).
        // foldConsistencyIntoPrompt iterates nodes in their
        // input order and appends each to `applied` as it
        // matches a kind branch — so applied mirrors the
        // input list order (= source.nodes declaration
        // order under auto-fold via consistencyNodes()).
        // The PROMPT text reorders to style→brand→character,
        // but applied does NOT.
        // Drift to reorder applied to match prompt text
        // would silently change clip.sourceBinding stability
        // across regenerations.
        val project = projectWithSource(
            Source.EMPTY
                // Declared in non-canonical order: character first.
                .addCharacterRef(
                    SourceNodeId("char-mei"),
                    CharacterRefBody(name = "Mei", visualDescription = "teal hair"),
                )
                .addBrandPalette(
                    SourceNodeId("brand-acme"),
                    BrandPaletteBody(name = "acme", hexColors = listOf("#FF0000")),
                )
                .addStyleBible(
                    SourceNodeId("style-warm"),
                    StyleBibleBody("warm", "warm look"),
                ),
        )
        val folded = AigcPipeline.foldPrompt(project, "x", bindingIds = null)
        // appliedNodeIds matches declaration order (char →
        // brand → style).
        assertEquals(
            listOf("char-mei", "brand-acme", "style-warm"),
            folded.appliedNodeIds,
            "appliedNodeIds MUST mirror declaration order (NOT fold order in prompt)",
        )
        // But the PROMPT text DOES reorder: style first,
        // brand second, character last. Pin both invariants.
        val styleIdx = folded.effectivePrompt.indexOf("Style:")
        val brandIdx = folded.effectivePrompt.indexOf("Brand:")
        val charIdx = folded.effectivePrompt.indexOf("Character")
        assertTrue(
            styleIdx in 0..<brandIdx,
            "prompt text MUST reorder to style → brand (NOT declaration order); got: ${folded.effectivePrompt}",
        )
        assertTrue(
            brandIdx < charIdx,
            "prompt text MUST place brand before character",
        )
    }
}
