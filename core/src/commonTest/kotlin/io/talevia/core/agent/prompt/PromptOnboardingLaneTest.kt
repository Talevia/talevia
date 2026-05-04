package io.talevia.core.agent.prompt

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Direct content tests for [PROMPT_ONBOARDING_LANE] —
 * `core/src/commonMain/kotlin/io/talevia/core/agent/prompt/PromptOnboardingLane.kt:24`.
 * Cycle 292 audit: 2 prior test refs total (one content
 * pin in [io.talevia.core.agent.GreenfieldOnboardingPromptTest]
 * for `style_bible` < `generate_image` ordering, plus
 * splicing tests in [io.talevia.core.agent.SystemPromptComposerTest]).
 * No content-level pins beyond that single ordering check.
 *
 * **Closes the prompt-content family** that started cycle 281
 * with PROMPT_DUAL_USER and covered the 6 static-base lanes
 * (cycles 281 PROMPT_DUAL_USER, 282 PROMPT_AIGC_LANE, 283
 * PROMPT_EDITING_LANE, 284 PROMPT_EXTERNAL_LANE, 285
 * PROMPT_BUILD_SYSTEM, 286 PROMPT_PROJECT). Cycle 292 covers
 * the **conditional onboarding lane** that splices ONLY when
 * `currentProjectId != null && projectIsGreenfield == true`.
 *
 * Same audit-pattern fallback as cycles 207-291.
 *
 * Reuses cycle 281-banked `flat` whitespace-collapsed view
 * idiom for cross-line phrase matching.
 *
 * `PROMPT_ONBOARDING_LANE` is conditional (~300 tokens when
 * spliced, 0 when not). Active only on the very first turn of
 * a brand-new project — traces show LLMs jump straight to
 * `generate_image` without first scaffolding a `style_bible`,
 * causing consistency-folding misses and breaking later
 * "make it warmer / keep that character" iterations.
 *
 * Drift signals:
 *   - **Drop the 4-step ordering** (genre → style_bible →
 *     genre nodes → AIGC dispatch) → LLM regresses to
 *     dispatching AIGC before scaffolding consistency.
 *   - **Drop the vlog-default-on-vague-intent rule** → LLM
 *     re-enables clarifying-question chains on "make
 *     something nice".
 *   - **Drop the 5-genre enumeration** → LLM loses awareness
 *     of which genre templates exist.
 *   - **Drop the id-prefix-with-kind-stem convention** →
 *     `source_query(select=nodes, kindPrefix=...)` returns
 *     unscoped lists.
 *   - **Drop the "lane disappears" self-deactivation note**
 *     → reader thinks the ~300 token surcharge is paid every
 *     turn forever.
 *
 * Pins via marker-substring presence on the whitespace-flat
 * view.
 */
class PromptOnboardingLaneTest {

    private val flat: String = PROMPT_ONBOARDING_LANE.replace(Regex("\\s+"), " ")

    // ── Header + framing ────────────────────────────────────

    @Test fun headerCitesVisionFourEmptyProjectLane() {
        // Marquee header pin: ties to VISION §4 "empty-project lane".
        // Drift to drop the §4 cite weakens the mental-model anchor.
        assertTrue(
            "# Greenfield onboarding (VISION §4 — empty-project lane)" in flat,
            "MUST contain VISION §4 'empty-project lane' header",
        )
    }

    @Test fun definesGreenfieldStateExplicitly() {
        // Pin: lane MUST explicitly define what greenfield
        // means ("no source nodes and no timeline tracks").
        // Drift to leave it implicit makes the lane trigger
        // less self-explanatory in trace review.
        assertTrue(
            "no source nodes and no timeline tracks" in flat,
            "MUST define greenfield as no source nodes + no timeline tracks",
        )
        assertTrue(
            "brand-new" in flat,
            "MUST anchor 'brand-new' qualifier",
        )
    }

    @Test fun saysSourceFirstWorkflow() {
        // Marquee discipline pin: the lane's purpose is
        // source-first BEFORE any AIGC dispatch.
        assertTrue(
            "source-first workflow before any AIGC dispatch" in flat,
            "MUST anchor source-first-before-AIGC discipline phrase",
        )
    }

    // ── Step 1: genre inference + vlog default ──────────────

    @Test fun fiveGenresEnumeratedForInference() {
        // Pin: lane MUST list all 5 canonical genre slugs.
        // Drift to drop one would make that genre invisible
        // to the auto-classifier on greenfield turns.
        for (genre in listOf("vlog", "narrative", "musicmv", "tutorial", "ad")) {
            assertTrue(
                genre in flat,
                "MUST enumerate '$genre' as a genre option",
            )
        }
    }

    @Test fun vagueIntentDefaultsToVlog() {
        // Marquee fallback pin: "make something nice" /
        // "帮我做一个" → default to vlog AND report inline.
        // Drift to default to a different genre (or to ask
        // a clarifying question) reverts to the failure mode
        // the lane was added to fix.
        assertTrue(
            "default to vlog" in flat,
            "MUST contain 'default to vlog' fallback rule",
        )
        assertTrue(
            "make something nice" in flat,
            "MUST cite 'make something nice' as canonical vague-intent example",
        )
        assertTrue(
            "帮我做一个" in flat,
            "MUST cite '帮我做一个' as canonical CJK vague-intent example",
        )
        assertTrue(
            "report the pick inline" in flat,
            "MUST require reporting the inferred genre inline (not silent)",
        )
        assertTrue(
            "never chain a bullet menu of genre/duration/aspect questions" in flat,
            "MUST forbid bullet-menu clarifying questions on greenfield",
        )
    }

    // ── Step 2: style_bible scaffold + consistency context ─

    @Test fun styleBibleScaffoldBeforeAigcDispatch() {
        // Marquee ordering pin: style_bible comes BEFORE any
        // AIGC dispatch. Drift to flip ordering re-enables
        // the consistency-folding miss the lane fixes.
        // This also doubles as the existing
        // GreenfieldOnboardingPromptTest pin, but at the
        // marker-substring level rather than indexOf math.
        assertTrue(
            "Scaffold at least one `core.consistency.style_bible` before any" in flat,
            "MUST instruct scaffolding style_bible BEFORE AIGC dispatch",
        )
        assertTrue(
            "AIGC output misses consistency folding" in flat,
            "MUST justify with the consistency-folding-miss argument",
        )
    }

    @Test fun staleClipsSurfacingMechanismMentioned() {
        // Pin: lane MUST tie style_bible scaffolding to
        // future "make it warmer / keep that character"
        // iterations via stale_clips drift detection. Drift
        // to omit makes the lane feel arbitrary.
        assertTrue(
            "make it warmer" in flat,
            "MUST cite 'make it warmer' as canonical iteration example",
        )
        assertTrue(
            "keep that character" in flat,
            "MUST cite 'keep that character' as canonical iteration example",
        )
        assertTrue(
            "project_query(select=stale_clips)" in flat,
            "MUST name project_query(select=stale_clips) as the drift detection surface",
        )
    }

    @Test fun characterRefAndBrandPaletteScaffoldHints() {
        // Pin: scaffold hints for character_ref (per named
        // character) + brand_palette (when genre implies
        // brand). Drift to drop either weakens the
        // greenfield scaffold that the LLM relies on.
        assertTrue(
            "core.consistency.character_ref" in flat,
            "MUST mention character_ref kind for named characters",
        )
        assertTrue(
            "core.consistency.brand_palette" in flat,
            "MUST mention brand_palette kind for branded content",
        )
        assertTrue(
            "ads, tutorials" in flat,
            "MUST cite ads + tutorials as brand-palette-implying genres",
        )
    }

    @Test fun idPrefixWithKindStemConvention() {
        // Marquee id-convention pin: lane MUST cite the
        // canonical id prefixes (style-warm, character-mei,
        // brand-acme). Drift to a different convention or
        // drop entirely makes
        // `source_query(select=nodes, kindPrefix=...)` queries
        // less effective.
        for (idExample in listOf("style-warm", "character-mei", "brand-acme")) {
            assertTrue(
                idExample in flat,
                "MUST cite '$idExample' as canonical id convention example",
            )
        }
        assertTrue(
            "source_node_action(action=\"add\")" in flat,
            "MUST point at source_node_action(add) as the scaffold tool",
        )
    }

    // ── Step 3: Genre-specific source nodes ─────────────────

    @Test fun fivePerGenreScaffoldRecipesEnumerated() {
        // Pin: per-genre scaffold recipes for all 5 canonical
        // genres. Drift to drop any genre's recipe would
        // make that genre's greenfield scaffold less
        // discoverable.
        // vlog:
        assertTrue(
            "vlog.edit_intent" in flat &&
                "vlog.raw_footage" in flat,
            "MUST cite vlog scaffold (edit_intent + raw_footage)",
        )
        // narrative:
        assertTrue(
            "narrative.storyline" in flat &&
                "narrative.scene" in flat &&
                "narrative.shot" in flat,
            "MUST cite narrative scaffold (storyline + scene + shot)",
        )
        // musicmv:
        assertTrue(
            "musicmv.track" in flat &&
                "musicmv.visual_concept" in flat,
            "MUST cite musicmv scaffold (track + visual_concept)",
        )
        // tutorial:
        assertTrue(
            "tutorial.script" in flat &&
                "tutorial.brand_spec" in flat,
            "MUST cite tutorial scaffold (script + brand_spec)",
        )
        // ad:
        assertTrue(
            "ad.brand_brief" in flat &&
                "ad.product_spec" in flat &&
                "ad.variant_request" in flat,
            "MUST cite ad scaffold (brand_brief + product_spec + variant_request per cut)",
        )
    }

    @Test fun structuredHandlesArgumentForNodes() {
        // Pin: lane MUST anchor on "structured handles to
        // mutate". Drift to omit weakens the rationale.
        assertTrue(
            "structured handles to mutate" in flat,
            "MUST anchor structured-handles-to-mutate rationale for genre nodes",
        )
    }

    // ── Step 4: AIGC dispatch with consistencyBindingIds ───

    @Test fun fourAigcToolsListedForGreenfieldDispatch() {
        // Pin: lane MUST list all 4 AIGC tools as the
        // canonical first-dispatch surface. Drift to drop
        // one (e.g. omit synthesize_speech) makes that
        // modality less discoverable on greenfield.
        for (tool in listOf(
            "generate_image",
            "generate_video",
            "synthesize_speech",
            "generate_music",
        )) {
            assertTrue(
                tool in flat,
                "MUST cite $tool as a greenfield AIGC dispatch target",
            )
        }
    }

    @Test fun consistencyBindingIdsParameterRequiredOnEveryCall() {
        // Marquee binding-discipline pin: every AIGC call
        // MUST pass consistencyBindingIds. Drift to soften
        // ("optional") re-enables the consistency-folding
        // miss the lane fixes.
        assertTrue(
            "passing the consistency node ids in `consistencyBindingIds` on every call" in flat,
            "MUST require consistencyBindingIds on every AIGC call",
        )
    }

    @Test fun clipActionAddTimelinePlacementInstruction() {
        // Pin: lane MUST instruct dropping returned assetId
        // onto a track via clip_action(add). Drift to omit
        // makes the LLM stop at the AIGC dispatch step.
        assertTrue(
            "Drop the returned `assetId` onto a timeline track via" in flat,
            "MUST instruct timeline-add via clip_action(add)",
        )
        assertTrue(
            "clip_action(action=\"add\")" in flat,
            "MUST name the clip_action(add) tool invocation",
        )
    }

    // ── "Just make a video" minimum-viable shortcut ────────

    @Test fun justMakeAVideoShortcutMandatesStyleBibleAndOneShot() {
        // Marquee shortcut pin: when user says "just make a
        // video" / "随便出一版", lane MUST instruct still
        // doing steps 2-3 (a single style_bible + one
        // generated shot). Drift to skip steps 2-3 re-enables
        // the consistency-folding miss for greenfield.
        assertTrue(
            "just make a video" in flat,
            "MUST cite 'just make a video' as canonical minimum-viable trigger",
        )
        // The CJK phrase is line-wrapped in source as "随便\n
        // 出一版" — after whitespace-collapse the two halves
        // are joined by a single space. Pin presence of both
        // halves (they're adjacent in semantic terms even
        // when wrapped).
        assertTrue(
            "随便" in flat && "出一版" in flat,
            "MUST cite '随便出一版' as canonical CJK minimum-viable trigger (line-wrapped allowed)",
        )
        assertTrue(
            "pick vlog" in flat,
            "MUST direct toward vlog as the minimum-viable genre default",
        )
        assertTrue(
            "cinematic-warm" in flat,
            "MUST cite cinematic-warm as canonical minimum-viable style_bible",
        )
        assertTrue(
            "don't skip steps 2–3" in flat ||
                "don't skip steps 2-3" in flat,
            "MUST forbid skipping steps 2-3 even on minimum-viable shortcut",
        )
    }

    @Test fun singleStyleBibleAsMinimumViableScaffold() {
        // Pin: explicit "A single scaffolded style_bible is
        // the minimum" — drift to demand more on the
        // shortcut path makes the v1 turn slower.
        assertTrue(
            "A single scaffolded style_bible is the minimum" in flat,
            "MUST anchor single-style_bible as minimum scaffold for minimum-viable shortcut",
        )
        assertTrue(
            "regenerable" in flat,
            "MUST justify with future-edit-regenerability argument",
        )
    }

    // ── Self-deactivation note ──────────────────────────────

    @Test fun laneDisappearsOnceProjectIsPopulated() {
        // Marquee self-deactivation pin: lane MUST end with
        // explicit "lane disappears" / "one-time orientation
        // nudge" framing. Drift to omit makes a reader (LLM
        // or human) think the ~300 token surcharge is paid
        // every turn forever.
        assertTrue(
            "lane disappears the moment the project has any track or source node" in flat,
            "MUST anchor lane-disappears self-deactivation contract",
        )
        assertTrue(
            "one-time orientation nudge" in flat,
            "MUST anchor 'one-time orientation nudge' framing",
        )
        assertTrue(
            "not a recurring tax" in flat,
            "MUST disclaim recurring-tax framing for the token surcharge",
        )
    }

    // ── Length + trim contracts ─────────────────────────────

    @Test fun lengthIsBoundedAndMeaningful() {
        // Pin: per the doc-comment, ~300 tokens when
        // spliced. Char band: ≥ 1500 (substantive) and
        // ≤ 5000 (not bloated).
        val s = PROMPT_ONBOARDING_LANE
        assertTrue(
            s.length > 1500,
            "lane content MUST be > 1500 chars (drift to no-op surfaces here); got: ${s.length}",
        )
        assertTrue(
            s.length < 5000,
            "lane content MUST be < 5000 chars (drift to bloated surfaces here); got: ${s.length}",
        )
    }

    @Test fun laneIsTrimmedNoLeadingOrTrailingBlankLines() {
        // Pin: lane is `.trimIndent()` then spliced via
        // `joinToString("\n\n")` with sister sections.
        // Leading / trailing whitespace would corrupt the
        // section-separator invariant the composer relies on.
        val s = PROMPT_ONBOARDING_LANE
        assertTrue(
            s == s.trim(),
            "lane MUST be trimmed (no leading/trailing whitespace)",
        )
    }
}
