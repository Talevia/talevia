package io.talevia.core.agent.prompt

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Direct content tests for [PROMPT_BUILD_SYSTEM] —
 * `core/src/commonMain/kotlin/io/talevia/core/agent/prompt/PromptBuildSystem.kt:11`.
 * Cycle 285 audit: 0 prior test refs.
 *
 * Same audit-pattern fallback as cycles 207-284. Continues the
 * prompt-content family (cycles 281 PROMPT_DUAL_USER, 282
 * PROMPT_AIGC_LANE, 283 PROMPT_EDITING_LANE, 284
 * PROMPT_EXTERNAL_LANE).
 *
 * `PROMPT_BUILD_SYSTEM` is **the first section** in
 * `TALEVIA_SYSTEM_PROMPT_BASE` — it sets the mental model the
 * entire rest of the prompt builds on. Token cost ~700-900 per
 * turn. Sections covered:
 *
 *   - Opening identity ("You are Talevia, an AI video editor")
 *   - # Build-system mental model (Source → Compiler → Artifact)
 *   - # Consistency bindings (VISION §3.3)
 *   - # Traditional color grading (LUT)
 *   - # Seed discipline
 *   - # Lockfile / render cache
 *   - # Output profile (render spec vs timeline authoring)
 *
 * Drift signals:
 *   - **Drift in opening identity** ("an AI assistant" /
 *     "video creator") → LLM loses Talevia self-concept; tool-
 *     dispatch grounding weakens.
 *   - **Drop "Never claim an edit happened unless a tool call
 *     succeeded"** → LLM regresses to "I edited the timeline"
 *     hallucinations.
 *   - **Drift in Source → Compiler → Artifact triad** → LLM
 *     loses the mental model that source nodes drive AIGC and
 *     timelines render artifacts.
 *   - **Drift in lockfile cache-key tuple** → LLM mis-keys cache
 *     hits, re-paying for identical AIGC dispatches.
 *   - **Drop output-profile-vs-timeline disambiguation** → LLM
 *     mutates Timeline.resolution when user asks "render at 4K",
 *     corrupting the authoring canvas.
 *
 * Pins via marker-substring presence on the whitespace-flat
 * view.
 */
class PromptBuildSystemTest {

    private val flat: String = PROMPT_BUILD_SYSTEM.replace(Regex("\\s+"), " ")

    // ── Opening identity + tool-dispatch grounding ──────────

    @Test fun openingIdentityAndToolDispatchGrounding() {
        // Marquee identity pin: opening sentence sets agent's
        // self-concept. Drift to "AI assistant" / "video
        // creator" weakens grounding.
        assertTrue(
            "You are Talevia, an AI video editor" in flat,
            "MUST contain the canonical opening identity",
        )
        assertTrue(
            "describes creative intent in natural language" in flat,
            "MUST anchor the user-side modality (natural language intent)",
        )
        assertTrue(
            "dispatch Tools that mutate the canonical Project / Timeline" in flat,
            "MUST anchor the agent-side modality (typed Tools mutating canonical state)",
        )
        assertTrue(
            "owned by the Core" in flat,
            "MUST anchor Core-ownership of Project/Timeline (drift to platform-owned would re-enable parallel state)",
        )
    }

    @Test fun neverClaimEditWithoutToolCall() {
        // Marquee anti-hallucination pin: drift to soften
        // this would re-enable "I edited the timeline"
        // hallucinations.
        assertTrue(
            "Never claim an edit happened unless a tool call succeeded" in flat,
            "MUST contain the never-hallucinate-edits directive verbatim",
        )
    }

    // ── # Build-system mental model — triad ─────────────────

    @Test fun sourceCompilerArtifactTriad() {
        // Marquee triad pin: every Project is the (Source,
        // Compiler, Artifact) tuple. Drift in any leg
        // silently drops the build-system metaphor.
        assertTrue(
            "Source → Compiler → Artifact" in flat,
            "MUST anchor the Source → Compiler → Artifact triad",
        )
        assertTrue(
            "Source = structured creative material" in flat,
            "MUST define Source leg",
        )
        assertTrue(
            "Compiler = your Tool calls" in flat,
            "MUST define Compiler leg",
        )
        assertTrue(
            "Artifact = the rendered file" in flat,
            "MUST define Artifact leg",
        )
    }

    @Test fun fiveGenreKindsAllListed() {
        // Marquee genre-coverage pin: lane MUST list all 5
        // canonical genres (vlog / narrative / musicmv /
        // tutorial / ad). Drift to drop one would silently
        // make the LLM blind to that genre's source-node
        // shape at planning time.
        for (kindMarker in listOf(
            "vlog.raw_footage", "vlog.edit_intent", "vlog.style_preset",
            "narrative.world", "narrative.storyline", "narrative.scene", "narrative.shot",
            "musicmv.track", "musicmv.visual_concept", "musicmv.performance_shot",
            "tutorial.script", "tutorial.broll_library", "tutorial.brand_spec",
            "ad.brand_brief", "ad.product_spec", "ad.variant_request",
        )) {
            assertTrue(
                kindMarker in flat,
                "lane MUST list source-node kind '$kindMarker'",
            )
        }
    }

    @Test fun threeConsistencyKindsAllListed() {
        // Pin: 3 consistency kinds. Drift to drop one
        // silently makes consistency-folding less complete.
        for (kind in listOf(
            "core.consistency.character_ref",
            "core.consistency.style_bible",
            "core.consistency.brand_palette",
        )) {
            assertTrue(
                kind in flat,
                "lane MUST list consistency kind '$kind'",
            )
        }
    }

    // ── # Consistency bindings — id convention + write API ─

    @Test fun consistencyIdConventionWithKindStemPrefix() {
        // Marquee pin: id-prefix-with-kind-stem convention
        // makes source_query selects clean.
        assertTrue(
            "character-mei" in flat,
            "MUST cite character-mei id convention example",
        )
        assertTrue(
            "style-warm" in flat,
            "MUST cite style-warm id convention example",
        )
        assertTrue(
            "brand-acme" in flat,
            "MUST cite brand-acme id convention example",
        )
        assertTrue(
            "kindPrefix=core.consistency." in flat,
            "MUST anchor kindPrefix=core.consistency. as the canonical scoped query",
        )
    }

    @Test fun updateSourceNodeBodyWholeReplacementSemantic() {
        // Marquee patch-semantic pin: update_source_node_body
        // is whole-body replacement, NOT delta. Drift to
        // delta semantics silently loses fields.
        assertTrue(
            "update_source_node_body" in flat,
            "MUST name the update_source_node_body tool",
        )
        assertTrue(
            "whole-body replacement" in flat,
            "MUST anchor whole-body-replacement (not delta) semantic",
        )
        assertTrue(
            "keep every field you want to retain" in flat,
            "MUST instruct the read-mutate-write discipline",
        )
        assertTrue(
            "Bumps `contentHash`" in flat,
            "MUST anchor that every body-write bumps contentHash",
        )
    }

    @Test fun parentIdsDagCascadeSemantic() {
        // Pin: parentIds drive contentHash cascade through
        // the DAG. Drift to "parents are documentation only"
        // would silently break stale-clip detection.
        assertTrue(
            "`parentIds`" in flat,
            "MUST name the parentIds field",
        )
        assertTrue(
            "cascade contentHash changes" in flat,
            "MUST anchor cascade semantic via parent links",
        )
        assertTrue(
            "don't add parents \"for documentation\"" in flat,
            "MUST forbid documentation-only parents (silently break cascade)",
        )
    }

    @Test fun renameAtomicallyRefactorsAllReferences() {
        // Marquee pin: rename rewrites node + descendants'
        // parent-refs + clips' sourceBinding + lockfile
        // entries in one atomic mutation. Drift to partial
        // rename silently breaks references.
        assertTrue(
            "source_node_action(action=\"rename\", oldId, newId)" in flat,
            "MUST document the rename action signature",
        )
        assertTrue(
            "atomically refactors" in flat,
            "MUST anchor atomic-rename invariant",
        )
        assertTrue(
            "every clip's `sourceBinding` set" in flat,
            "MUST list clip.sourceBinding rewrite",
        )
        assertTrue(
            "every lockfile entry's binding" in flat,
            "MUST list lockfile binding rewrite",
        )
        assertTrue(
            "lowercase letters / digits / `-`" in flat,
            "MUST document the slug-shape constraint",
        )
        assertTrue(
            "same-id is a no-op" in flat,
            "MUST anchor same-id no-op semantic",
        )
    }

    // ── regenerate_stale_clips one-shot path ────────────────

    @Test fun regenerateStaleClipsAndExportBlocking() {
        // Marquee pin: regenerate_stale_clips one-shot path
        // + Export-blocks-on-stale + allowStale=true escape
        // hatch.
        assertTrue(
            "regenerate_stale_clips" in flat,
            "MUST name regenerate_stale_clips tool",
        )
        assertTrue(
            "one tool that handles the full" in flat,
            "MUST anchor regenerate_stale_clips as the one-shot path",
        )
        assertTrue(
            "Single consent covers the whole batch" in flat,
            "MUST anchor batch-consent invariant",
        )
        assertTrue(
            "ExportTool refuses stale renders" in flat,
            "MUST anchor export-blocks-on-stale invariant",
        )
        assertTrue(
            "allowStale=true" in flat,
            "MUST document allowStale=true escape hatch",
        )
    }

    // ── # Traditional color grading (LUT) ──────────────────

    @Test fun applyLutTwoInputShapesAndStyleBiblePreference() {
        // Marquee pin: lutAssetId vs styleBibleId, exactly
        // one at a time + style_bible is preferred path.
        assertTrue("apply_lut" in flat, "MUST name apply_lut tool")
        assertTrue(
            "`.cube` / `.3dl`" in flat ||
                ".cube / .3dl" in flat,
            "MUST list supported LUT formats",
        )
        assertTrue(
            "exactly one at a time" in flat,
            "MUST anchor exactly-one-input invariant on apply_lut",
        )
        assertTrue(
            "preferred path when a project has a style_bible" in flat,
            "MUST anchor style_bible-preference for LUT routing",
        )
    }

    @Test fun lutEngineParityAndCubeRestrictions() {
        // Pin: 3-engine parity (FFmpeg lut3d / Media3
        // SingleColorLut / AVFoundation CIColorCube) + .cube
        // v1.0 only + no DOMAIN_MIN/MAX + no 1D LUTs.
        assertTrue(
            "FFmpeg renders this via `lut3d`" in flat,
            "MUST anchor FFmpeg engine path",
        )
        assertTrue(
            "Media3 (Android) bakes it via `SingleColorLut`" in flat,
            "MUST anchor Android engine path",
        )
        assertTrue(
            "AVFoundation (iOS) bakes it via `CIColorCube`" in flat,
            "MUST anchor iOS engine path",
        )
        assertTrue(
            "Non-default `DOMAIN_MIN/MAX` and 1D LUTs are rejected" in flat,
            "MUST anchor v1 LUT format restrictions",
        )
    }

    // ── # Seed discipline ───────────────────────────────────

    @Test fun seedDisciplineForCacheReuse() {
        // Marquee pin: explicit seeds + reuse-on-same-look
        // semantic.
        assertTrue(
            "Prefer explicit seeds for AIGC" in flat,
            "MUST anchor explicit-seed preference",
        )
        assertTrue(
            "miss the lockfile cache" in flat,
            "MUST justify with cache-miss penalty for client-minted seeds",
        )
        assertTrue(
            "\"same look\"" in flat || "same look" in flat,
            "MUST cite the 'same look' user-utterance trigger",
        )
        assertTrue(
            "reuse the seed" in flat,
            "MUST direct toward seed-reuse on same-look intent",
        )
    }

    // ── # Lockfile / render cache ───────────────────────────

    @Test fun lockfileCacheKeyTupleAndForceRenderEscape() {
        // Marquee pin: 7-field cache-key tuple + cacheHit=true
        // semantic + don't-forceRender-without-explicit-ask.
        assertTrue(
            "(tool, model, version, seed, effective prompt, dimensions, bindings)" in flat,
            "MUST enumerate the AIGC cache-key tuple verbatim (drift would silently re-key)",
        )
        assertTrue(
            "cacheHit=true" in flat,
            "MUST document cacheHit=true return signal",
        )
        assertTrue(
            "without a provider call" in flat,
            "MUST anchor that cache-hits skip provider dispatch",
        )
        assertTrue(
            "(timeline, outputSpec)" in flat,
            "MUST anchor export cache-key tuple",
        )
        assertTrue(
            "Don't pass `forceRender=true` unless the user explicitly asked" in flat,
            "MUST forbid speculative forceRender",
        )
    }

    // ── # Output profile vs timeline ────────────────────────

    @Test fun outputProfileVsTimelineAuthoringDistinction() {
        // Marquee pin: outputProfile (render spec) is
        // separate from Timeline.resolution (authoring
        // canvas). Drift to conflate would have LLM mutate
        // the wrong field on "render at 4K".
        assertTrue(
            "Project.outputProfile" in flat,
            "MUST name the outputProfile field",
        )
        assertTrue(
            "render spec" in flat,
            "MUST anchor outputProfile as the render-spec",
        )
        assertTrue(
            "Timeline.resolution" in flat &&
                "Timeline.frameRate" in flat,
            "MUST name the authoring-canvas fields it's separate from",
        )
        assertTrue(
            "authoring" in flat,
            "MUST anchor authoring-canvas framing",
        )
        assertTrue(
            "set_output_profile" in flat,
            "MUST name set_output_profile as the right tool",
        )
        assertTrue(
            "not some timeline tool" in flat,
            "MUST explicitly disclaim timeline-tool route for output-spec changes",
        )
        assertTrue(
            "render at 4K" in flat,
            "MUST cite the canonical 'render at 4K' user-utterance example",
        )
    }

    @Test fun outputProfileChangeInvalidatesRenderCache() {
        // Pin: changing output profile naturally invalidates
        // render cache (it's part of cache key). Drift to
        // require manual invalidation would re-enable stale
        // exports after profile change.
        assertTrue(
            "invalidates the render cache naturally" in flat,
            "MUST anchor natural-invalidation semantic on profile change",
        )
        assertTrue(
            "without any extra invalidation step" in flat,
            "MUST forbid manual-invalidation requirement",
        )
    }

    // ── Cross-section invariants ────────────────────────────

    @Test fun staleClipsQuerySurfaceUbiquitous() {
        // Marquee pin: project_query(select=stale_clips) is
        // the canonical drift-detection surface mentioned in
        // multiple contexts (after edits, after consistency
        // changes, before regenerate). Pin presence ≥ 4
        // mentions (drift to fewer would re-enable LLM
        // forgetting to check drift).
        val matches = "project_query(select=stale_clips)".toRegex(RegexOption.LITERAL).findAll(flat).count()
        assertTrue(
            matches >= 4,
            "lane MUST mention project_query(select=stale_clips) at least 4× across consistency / regen / export sections; got: $matches",
        )
    }

    @Test fun lengthIsBoundedAndMeaningful() {
        // Pin: PROMPT_BUILD_SYSTEM is the foundational lane —
        // largest mental-model carrier. Length band:
        // 4000-12000 chars (~700-900 tokens).
        val s = PROMPT_BUILD_SYSTEM
        assertTrue(
            s.length > 4000,
            "lane content MUST be > 4000 chars; got: ${s.length}",
        )
        assertTrue(
            s.length < 12_000,
            "lane content MUST be < 12000 chars; got: ${s.length}",
        )
    }

    @Test fun laneIsTrimmedNoLeadingOrTrailingBlankLines() {
        val s = PROMPT_BUILD_SYSTEM
        assertTrue(
            s == s.trim(),
            "lane MUST be trimmed (no leading/trailing whitespace)",
        )
    }
}
