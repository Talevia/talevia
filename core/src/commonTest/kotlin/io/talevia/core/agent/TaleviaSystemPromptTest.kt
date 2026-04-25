package io.talevia.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the system prompt. Every key phrase listed below teaches the
 * model a specific VISION invariant; silently deleting any of them regresses model
 * behavior in ways a typecheck can't catch. This test fails loudly when that happens.
 */
class TaleviaSystemPromptTest {

    private val keyPhrases = listOf(
        // Build-system mental model (VISION §2)
        "Build-system",
        "Source",
        "Compiler",
        "Artifact",
        // Consistency bindings (VISION §3.3)
        "consistencyBindingIds",
        "character_ref",
        "style_bible",
        "brand_palette",
        // Genre coverage (VISION §2) — prompt must teach all five named genres'
        // kind strings so the agent routes `source_node_action(action="import")` correctly.
        "vlog.raw_footage",
        "narrative.shot",
        "musicmv.track",
        "musicmv.visual_concept",
        "musicmv.performance_shot",
        "tutorial.script",
        "tutorial.broll_library",
        "tutorial.brand_spec",
        "ad.brand_brief",
        "ad.product_spec",
        "ad.variant_request",
        // Source-mutation tooling teaches the model how to *create* the bindings it
        // is told to pass — the prompt-fold logic is dead without these tools.
        // Post-`debt-source-consolidate-add-remove-fork` (2026-04-24) the add / remove /
        // fork verbs are actions on `source_node_action`, not standalone tool ids.
        "source_node_action(action=\"add\"",
        "update_source_node_body",
        "source_query",
        // parentIds — cross-refs in the source DAG (VISION §3.3 / §5.1).
        "parentIds",
        // Project lifecycle — the agent must know it can bootstrap projects itself.
        "create_project",
        "list_projects",
        // Cycle 129: `get_project_state` was folded into
        // `project_query(select=project_metadata)`; the system prompt
        // teaches the new path now.
        "project_query(select=project_metadata)",
        // ML enhancement lane — ASR transcription is the first ML tool.
        "transcribe_asset",
        // Vision describe — the image-side counterpart to ASR.
        "describe_asset",
        // Batch subtitles — the pair that closes the transcribe → caption loop.
        "add_subtitles",
        // AIGC audio lane — TTS pairs with ASR for the round-trip.
        "synthesize_speech",
        // AIGC video lane (VISION §2 "文生视频") — Sora-backed text-to-video.
        "generate_video",
        // Character-voice pinning (VISION §5.5 audio lane) — overrides explicit voice.
        "voiceId",
        // Stale-clip detection — the lockfile-driven query that closes the
        // edit-character-then-regenerate loop (VISION §3.2).
        "find_stale_clips",
        // The mutation half of the regenerate-after-stale loop — post phase-3
        // (2026-04-24) the verb is an action on `clip_action`, not a
        // standalone tool id.
        "clip_action(action=\"replace\")",
        // The missing scalpel — clip deletion sits alongside split / replace.
        // Post-`debt-video-clip-consolidate-verbs-phase-1` (2026-04-23) the verb is
        // an action on `clip_action`, not a standalone tool id.
        "clip_action(action=\"remove\")",
        // The ripple-delete chain partner — same-track reposition by id.
        "move_clip",
        // Re-trim after creation — edits sourceRange without losing bound filters.
        "trim_clip",
        // Audio volume control — adjust playback level on an audio clip in place.
        "clip_set_action(field=\"volume\"",
        // Visual transform editor — opacity / scale / translate / rotate setter.
        "clip_set_action(field=\"transform\"",
        // Frame extraction — video→image helper that unlocks describe_asset on
        // video assets and reference-image chaining into generate_image/video.
        "extract_frame",
        // Project-level named snapshots (VISION §3.4) — survive across chat sessions.
        "project_snapshot_action",
        "project_query(select=snapshots)",
        // Fork — closes the third VISION §3.4 leg ("可分支").
        "fork_project",
        // Diff — closes the VISION §3.4 "可 diff" property alongside snapshot + fork.
        "diff_projects",
        // Node-level diff — the zoom-in sibling of diff_projects (VISION §5.1 "改一个 source 节点").
        "diff_source_nodes",
        // Import — closes the VISION §3.4 "可组合" leg (cross-project source-node reuse).
        "source_node_action(action=\"import\"",
        // Atomic source-id refactor — rewrites node, parent-refs, clip bindings, lockfile.
        "source_node_action(action=\"rename\"",
        // Generic body editor — kind-agnostic body replace for genre / imported nodes.
        "update_source_node_body",
        // Traditional color grading — LUT enforcement on style_bible.lutReference
        // (VISION §3.3 traditional lane).
        "apply_lut",
        // Lockfile observability (VISION §3.1) — enumerate AIGC productions.
        "project_query(select=lockfile_entries)",
        // Seed / lockfile discipline (VISION §3.1)
        "seed",
        "cacheHit",
        // Dual user (VISION §4)
        "high-level",
        "precise",
        // Bias toward action — prevents the "open a 4-bullet menu of
        // style/duration/aspect/mood questions" failure mode on "make me a X".
        "Bias toward action",
        "standing order",
        "Make it look good",
    )

    @Test fun promptContainsAllNorthStarKeyPhrases() {
        val prompt = taleviaSystemPrompt()
        for (phrase in keyPhrases) {
            assertTrue(phrase in prompt, "system prompt must still mention '$phrase'")
        }
    }

    @Test fun extraSuffixAppendsWithBlankLineSeparator() {
        val prompt = taleviaSystemPrompt(extraSuffix = "Runtime: test harness.")
        assertTrue(prompt.endsWith("Runtime: test harness."))
        assertTrue("\n\nRuntime:" in prompt, "suffix must be separated by a blank line")
    }

    @Test fun blankSuffixIsIgnored() {
        assertEquals(taleviaSystemPrompt(), taleviaSystemPrompt(extraSuffix = ""))
        assertEquals(taleviaSystemPrompt(), taleviaSystemPrompt(extraSuffix = "   "))
    }

    @Test fun sectionsAppearInExpectedOrder() {
        // The prompt body is sharded across four topical section files (decision
        // `docs/decisions/2026-04-21-debt-split-taleviasystemprompt.md`). Ordering
        // matters for LLM priming: mental model first, then consistency + mechanics,
        // then dual-user + AIGC, then project lifecycle, then editing + externals.
        // This test is the regression lock — if someone shuffles the composer
        // joinToString order, it fails loud.
        val prompt = taleviaSystemPrompt()
        val anchors = listOf(
            "# Build-system mental model", // PROMPT_BUILD_SYSTEM opens the body
            "# Consistency bindings",
            "# Seed discipline",
            "# Two kinds of users", // PROMPT_AIGC_LANE starts
            "# AIGC video (text-to-video)",
            "# Project lifecycle", // PROMPT_PROJECT starts
            "# Project snapshots",
            "# Removing clips", // PROMPT_EDITING_LANE starts
            "# External files (fs tools)", // PROMPT_EXTERNAL_LANE starts
            "# Session-project binding",
            "# Rules",
            "# Bias toward action",
        )
        val indices = anchors.map { it to prompt.indexOf(it) }
        indices.forEach { (anchor, idx) ->
            assertTrue(idx >= 0, "section anchor not found: '$anchor'")
        }
        // Strictly monotonic ordering: each anchor must come after the previous one.
        for (i in 1 until indices.size) {
            val (prev, prevIdx) = indices[i - 1]
            val (curr, currIdx) = indices[i]
            assertTrue(
                currIdx > prevIdx,
                "section '$curr' (idx=$currIdx) must appear after '$prev' (idx=$prevIdx) — composer order drifted?",
            )
        }
    }

    @Test fun sectionsAreSeparatedByExactlyOneBlankLine() {
        // The composer uses `joinToString("\n\n")` so adjacent sections meet on a
        // double-newline. Triple-newline would mean a stray blank line sneaked into
        // one section's body; single-newline would mean the join string drifted.
        val prompt = taleviaSystemPrompt()
        val buildToConsistency = "invalidation step.\n\n# Two kinds of users"
        assertTrue(
            buildToConsistency in prompt,
            "section-A → section-B boundary must be a single blank line (got: '${
                prompt.substringAfter("invalidation step.").take(40)
            }')",
        )
        // Verify no triple-newline exists anywhere in the body.
        assertTrue("\n\n\n" !in prompt, "prompt must not contain a blank double-line anywhere")
    }
}
