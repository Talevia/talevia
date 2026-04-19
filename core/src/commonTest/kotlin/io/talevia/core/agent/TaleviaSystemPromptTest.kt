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
        // Project lifecycle — the agent must know it can bootstrap projects itself.
        "create_project",
        "list_projects",
        "get_project_state",
        // ML enhancement lane — ASR transcription is the first ML tool.
        "transcribe_asset",
        // AIGC audio lane — TTS pairs with ASR for the round-trip.
        "synthesize_speech",
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
