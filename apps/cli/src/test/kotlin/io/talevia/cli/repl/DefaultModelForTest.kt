package io.talevia.cli.repl

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests for [defaultModelFor] —
 * `apps/cli/src/main/kotlin/io/talevia/cli/repl/Repl.kt:286`.
 * Cycle 273 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-272.
 *
 * **Triple-duplication caveat**: this is the THIRD copy of
 * `defaultModelFor` in the codebase. Cycles 238 / 240 pinned
 * the desktop and server copies respectively; this commit
 * pins the CLI copy. The three copies SHOULD agree (same
 * provider id → same canonical model id) but no shared
 * abstraction enforces parity. A bug fix landed in one copy
 * doesn't propagate to the other two.
 *
 * The CLI copy is consumed at `Repl.kt:82` —
 * `var modelId = defaultModelFor(provider.id)` — sets the
 * default model id when the user hasn't explicitly chosen one
 * via `/model`. Drift in any return value silently routes
 * fresh CLI sessions to the wrong model class (different cost
 * / quality), surfacing only as a billing surprise.
 *
 * Three copies pinned:
 *   - cycle 238: `apps/desktop/src/main/kotlin/io/talevia/desktop/ExportPresets.kt`
 *   - cycle 240: `apps/server/src/main/kotlin/io/talevia/server/ServerDtos.kt`
 *   - cycle 273 (this): `apps/cli/src/main/kotlin/io/talevia/cli/repl/Repl.kt`
 *
 * The triple-duplication signals a refactor opportunity —
 * tracked as `debt-consolidate-defaultModelFor-three-copies`
 * in BACKLOG (cycle 273 P2 append).
 *
 * Three correctness contracts pinned (sister to cycles
 * 238 / 240):
 *
 *  1. Canonical defaults match the other two copies.
 *  2. Fallback returns literal `"default"`.
 *  3. Case-sensitive matching.
 */
class DefaultModelForTest {

    @Test fun anthropicProviderResolvesToClaudeOpus47() {
        assertEquals("claude-opus-4-7", defaultModelFor("anthropic"))
    }

    @Test fun openaiProviderResolvesToGpt54Mini() {
        assertEquals("gpt-5.4-mini", defaultModelFor("openai"))
    }

    @Test fun unknownProviderFallsBackToLiteralDefault() {
        // Marquee fallback pin: any unknown provider id →
        // `"default"`. Drift to throw / null would crash
        // Repl.kt:82 at startup for a brand-new provider.
        assertEquals("default", defaultModelFor("gemini"))
        assertEquals("default", defaultModelFor("replicate"))
        assertEquals("default", defaultModelFor("anthropic-fake"))
    }

    @Test fun blankProviderFallsBackToDefault() {
        assertEquals("default", defaultModelFor(""))
        assertEquals("default", defaultModelFor("   "))
    }

    @Test fun providerIdMatchingIsCaseSensitive() {
        // Pin: `when` uses string equality; case matters.
        assertEquals("default", defaultModelFor("Anthropic"))
        assertEquals("default", defaultModelFor("ANTHROPIC"))
        assertEquals("default", defaultModelFor("OpenAI"))
        assertEquals("default", defaultModelFor("OPENAI"))
    }

    @Test fun whitespaceAroundProviderIdIsNotTrimmed() {
        // Pin: `" anthropic"` doesn't trim — drift to trim
        // would obscure typos at the call site.
        assertEquals("default", defaultModelFor(" anthropic"))
        assertEquals("default", defaultModelFor("anthropic "))
        assertEquals("default", defaultModelFor("\tanthropic"))
    }

    @Test fun returnsNonNullForAllInputs() {
        // Pin: function NEVER returns null / empty (signature
        // is String, not String?). `Repl.kt:82` reads the
        // result directly into `var modelId` without null
        // check.
        for (input in listOf("", "anthropic", "openai", "gemini", "x", " ")) {
            assertEquals(
                false,
                defaultModelFor(input).isEmpty(),
                "defaultModelFor('$input') MUST return a non-empty string",
            )
        }
    }

    @Test fun cliCopyMatchesDesktopAndServerCopies() {
        // Marquee triple-duplication parity pin: the three
        // copies (desktop / server / CLI) MUST produce the
        // same output for the same input. Drift in any one
        // would silently make `talevia` (CLI), `talevia
        // desktop`, and `talevia client` (CLI to server)
        // route fresh chats to different models for the same
        // provider.
        //
        // We can't import the other two copies (they're
        // `internal` to their respective modules), but we
        // can pin the values that MUST match what cycles
        // 238 / 240 documented as canonical:
        //   anthropic → claude-opus-4-7
        //   openai    → gpt-5.4-mini
        //   else      → default
        // If this CLI copy diverges, it'll fail one of the
        // three pins above — this test is a literal restate
        // for the parity intent.
        assertEquals("claude-opus-4-7", defaultModelFor("anthropic"))
        assertEquals("gpt-5.4-mini", defaultModelFor("openai"))
        assertEquals("default", defaultModelFor("gemini"))
    }
}
