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
        // AIGC audio lane — TTS pairs with ASR for the round-trip.
        "synthesize_speech",
        // Character-voice pinning (VISION §5.5 audio lane) — overrides explicit voice.
        "voiceId",
        // Stale-clip detection — the lockfile-driven query that closes the
        // edit-character-then-regenerate loop (VISION §3.2).
        "find_stale_clips",
        // The mutation half of the regenerate-after-stale loop.
        "replace_clip",
        // Project-level named snapshots (VISION §3.4) — survive across chat sessions.
        "save_project_snapshot",
        "list_project_snapshots",
        "restore_project_snapshot",
        // Fork — closes the third VISION §3.4 leg ("可分支").
        "fork_project",
        // Diff — closes the VISION §3.4 "可 diff" property alongside snapshot + fork.
        "diff_projects",
        // Import — closes the VISION §3.4 "可组合" leg (cross-project source-node reuse).
        "import_source_node",
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
