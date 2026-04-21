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
        // kind strings so the agent routes `import_source_node` correctly.
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
        "define_character_ref",
        "list_source_nodes",
        // parentIds — cross-refs in the source DAG (VISION §3.3 / §5.1).
        "parentIds",
        // Project lifecycle — the agent must know it can bootstrap projects itself.
        "create_project",
        "list_projects",
        "get_project_state",
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
        // The mutation half of the regenerate-after-stale loop.
        "replace_clip",
        // The missing scalpel — clip deletion sits alongside split / replace.
        "remove_clip",
        // The ripple-delete chain partner — same-track reposition by id.
        "move_clip",
        // Re-trim after creation — edits sourceRange without losing bound filters.
        "trim_clip",
        // Audio volume control — adjust playback level on an audio clip in place.
        "set_clip_volume",
        // Visual transform editor — opacity / scale / translate / rotate setter.
        "set_clip_transform",
        // Frame extraction — video→image helper that unlocks describe_asset on
        // video assets and reference-image chaining into generate_image/video.
        "extract_frame",
        // Project-level named snapshots (VISION §3.4) — survive across chat sessions.
        "save_project_snapshot",
        "list_project_snapshots",
        "restore_project_snapshot",
        // Fork — closes the third VISION §3.4 leg ("可分支").
        "fork_project",
        // Diff — closes the VISION §3.4 "可 diff" property alongside snapshot + fork.
        "diff_projects",
        // Node-level diff — the zoom-in sibling of diff_projects (VISION §5.1 "改一个 source 节点").
        "diff_source_nodes",
        // Import — closes the VISION §3.4 "可组合" leg (cross-project source-node reuse).
        "import_source_node",
        // Atomic source-id refactor — rewrites node, parent-refs, clip bindings, lockfile.
        "rename_source_node",
        // Generic body editor — kind-agnostic body replace for genre / imported nodes.
        "update_source_node_body",
        // Traditional color grading — LUT enforcement on style_bible.lutReference
        // (VISION §3.3 traditional lane).
        "apply_lut",
        // Lockfile observability (VISION §3.1) — enumerate AIGC productions.
        "list_lockfile_entries",
        // Seed / lockfile discipline (VISION §3.1)
        "seed",
        "cacheHit",
        // Dual user (VISION §4)
        "high-level",
        "precise",
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
}
