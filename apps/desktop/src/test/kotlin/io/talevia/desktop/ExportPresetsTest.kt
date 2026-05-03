package io.talevia.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [ResolutionPreset], [FpsPreset], and
 * [defaultModelFor] — `apps/desktop/src/main/kotlin/io/talevia/desktop/ExportPresets.kt`.
 * Cycle 238 audit: 0 test refs against any of the three.
 *
 * Same audit-pattern fallback as cycles 207-237.
 *
 * The two enums and the `defaultModelFor` helper sit between the
 * Desktop chat panel UI and the `export` / `submit_message` tool
 * inputs — a UI dropdown picks one of the preset entries, the desktop
 * code passes `width`/`height`/`value` straight into the tool input,
 * the agent calls FFmpeg / Media3 with whatever lands. Drift in any
 * single numeric value would silently produce the wrong-resolution /
 * wrong-fps export with no error surface (the export still
 * "succeeds", just at the wrong target). Drift in `defaultModelFor`
 * would silently route fresh chat sessions to the wrong model —
 * billed differently, perceived quality differently.
 *
 * Three correctness contracts pinned:
 *
 *  1. **Project preset's null contract.** Both enums' `Project`
 *     entry MUST have `null` for its override field — drift to a
 *     non-null value would silently override the project's
 *     OutputProfile even when the user picked "Project" (i.e. the
 *     "no override" lane). This is the marquee opt-out invariant.
 *
 *  2. **Concrete preset numbers match their labels exactly.**
 *     `HD720 = 1280x720`, `FullHD = 1920x1080`, `UHD4K = 3840x2160`,
 *     `Fps24 = 24`, `Fps30 = 30`, `Fps60 = 60`. Drift to "rounded
 *     down to common-vendor" values (1080 → 1024, 4K → 4096) would
 *     break compatibility with downstream tools that branch on
 *     standard resolutions; drift in fps would silently produce
 *     stutter or 2x-real-time output.
 *
 *  3. **`defaultModelFor` provider lookup is exhaustive +
 *     fallback-safe.** Known providers (anthropic / openai) MUST
 *     return the canonical default model id; any other provider id
 *     (including unknown / empty) MUST fall back to `"default"`.
 *     Drift in the canonical defaults would silently route every
 *     fresh chat to a different model class than expected (cost +
 *     quality differ). Drift in the fallback to throw / return null
 *     would crash the chat panel on first turn.
 *
 * Plus enum-stability pins:
 *   - `ResolutionPreset.entries.size == 4` — drift to "drop UHD4K"
 *     would break the dropdown UI silently; drift to "add a new
 *     preset" without test coverage would let it slip through.
 *   - `FpsPreset.entries.size == 4` — same.
 *   - Labels match expected display strings — drift would surface
 *     in the dropdown but tests catch it earlier.
 */
class ExportPresetsTest {

    // ── 1. Project preset's null contract ───────────────────

    @Test fun resolutionProjectPresetCarriesNullOverride() {
        // Marquee "no override" pin: ResolutionPreset.Project means
        // "respect the project's OutputProfile". Drift to non-null
        // would silently override the project even when the user
        // picked the no-op lane.
        val p = ResolutionPreset.Project
        assertNull(p.width, "Project preset's width override MUST be null (no override)")
        assertNull(p.height, "Project preset's height override MUST be null (no override)")
        assertEquals("Project", p.label, "Project preset's label MUST be 'Project'")
    }

    @Test fun fpsProjectPresetCarriesNullOverride() {
        // Sibling pin to ResolutionPreset.Project — same opt-out
        // contract.
        val p = FpsPreset.Project
        assertNull(p.value, "FpsPreset.Project's override MUST be null (use project fps)")
        assertEquals(
            "Project fps",
            p.label,
            "FpsPreset.Project's label MUST be 'Project fps' (distinguishes from concrete fps)",
        )
    }

    // ── 2. Concrete resolution preset numbers ───────────────

    @Test fun hd720PresetIs1280x720() {
        // Marquee resolution pin: drift to 1366x768 / 1024x576 / etc.
        // would silently break HD720 exports. Both width AND height
        // pinned — single-axis drift would still pass a "sum of
        // pixels" check but break aspect ratio.
        val p = ResolutionPreset.HD720
        assertEquals(1280, p.width)
        assertEquals(720, p.height)
        assertEquals("720p", p.label)
    }

    @Test fun fullHdPresetIs1920x1080() {
        val p = ResolutionPreset.FullHD
        assertEquals(1920, p.width)
        assertEquals(1080, p.height)
        assertEquals("1080p", p.label)
    }

    @Test fun uhd4kPresetIs3840x2160() {
        // Marquee 4K pin: drift to 4096x2160 (DCI 4K) would silently
        // change pixel area and break Anthropic / OpenAI vision
        // tokenisation budgets. UHD (3840x2160) is the consumer 4K
        // — pinning the consumer flavor.
        val p = ResolutionPreset.UHD4K
        assertEquals(3840, p.width)
        assertEquals(2160, p.height)
        assertEquals("4K", p.label)
    }

    // ── 3. Concrete fps preset values ───────────────────────

    @Test fun fps24Preset() {
        val p = FpsPreset.Fps24
        assertEquals(24, p.value)
        assertEquals("24", p.label)
    }

    @Test fun fps30Preset() {
        val p = FpsPreset.Fps30
        assertEquals(30, p.value)
        assertEquals("30", p.label)
    }

    @Test fun fps60Preset() {
        val p = FpsPreset.Fps60
        assertEquals(60, p.value)
        assertEquals("60", p.label)
    }

    // ── 4. Enum-stability pins ──────────────────────────────

    @Test fun resolutionPresetHasExactlyFourEntries() {
        // Pin: 4 entries (Project, HD720, FullHD, UHD4K). Drift to
        // "drop UHD4K" silently breaks the 4K dropdown lane; drift
        // to "add 8K" without test coverage would let it ship with
        // unmatched downstream support. Either direction surfaces here.
        assertEquals(
            4,
            ResolutionPreset.entries.size,
            "ResolutionPreset MUST have exactly 4 entries (Project + 3 concrete) — got ${ResolutionPreset.entries.size}",
        )
        // Identity check: entries match expected names.
        val names = ResolutionPreset.entries.map { it.name }.toSet()
        assertEquals(setOf("Project", "HD720", "FullHD", "UHD4K"), names)
    }

    @Test fun fpsPresetHasExactlyFourEntries() {
        assertEquals(4, FpsPreset.entries.size)
        val names = FpsPreset.entries.map { it.name }.toSet()
        assertEquals(setOf("Project", "Fps24", "Fps30", "Fps60"), names)
    }

    @Test fun nonProjectResolutionPresetsAlwaysHaveBothDimensions() {
        // Cross-axis pin: any non-Project preset must have BOTH
        // width and height non-null. Drift to "only width set"
        // would crash the export pipeline (or silently default
        // height to a stale value).
        for (preset in ResolutionPreset.entries) {
            if (preset == ResolutionPreset.Project) continue
            assertTrue(
                preset.width != null && preset.height != null,
                "${preset.name} must have both width and height non-null (got ${preset.width}x${preset.height})",
            )
        }
    }

    @Test fun nonProjectFpsPresetsAlwaysHaveValue() {
        for (preset in FpsPreset.entries) {
            if (preset == FpsPreset.Project) continue
            assertTrue(
                preset.value != null,
                "${preset.name} must have value non-null (got ${preset.value})",
            )
        }
    }

    // ── 5. defaultModelFor lookup ───────────────────────────

    @Test fun defaultModelForAnthropicIsClaudeOpus47() {
        // Marquee canonical-model pin: a fresh Desktop chat that
        // routes via "anthropic" lands on `claude-opus-4-7` per the
        // CLAUDE.md model table. Drift would silently route to a
        // different model class with different cost / quality.
        assertEquals("claude-opus-4-7", defaultModelFor("anthropic"))
    }

    @Test fun defaultModelForOpenAiIsGpt54Mini() {
        assertEquals("gpt-5.4-mini", defaultModelFor("openai"))
    }

    @Test fun defaultModelForUnknownProviderFallsBackToDefault() {
        // Marquee fallback pin: any unknown provider id falls back
        // to the literal `"default"` string (consumed by the chat
        // panel as "let the container's default ProviderRegistry
        // pick"). Drift to throw or return null would crash the
        // chat panel on first turn for a brand-new provider.
        assertEquals("default", defaultModelFor("gemini"))
        assertEquals("default", defaultModelFor("replicate"))
        assertEquals("default", defaultModelFor("anthropic-fake"))
    }

    @Test fun defaultModelForBlankProviderFallsBackToDefault() {
        // Pin: blank / empty provider id falls back. The `when`
        // doesn't have a `""` arm so it lands in `else -> "default"`.
        // Drift to "throw on blank" would crash callers passing
        // an unset ProviderRegistry default.
        assertEquals("default", defaultModelFor(""))
        assertEquals("default", defaultModelFor("   "))
    }

    @Test fun defaultModelForIsCaseSensitive() {
        // Pin: `when` uses string equality, NOT case-insensitive
        // matching. Drift to `equalsIgnoreCase` would match
        // "ANTHROPIC" → "claude-opus-4-7" but the rest of the
        // wiring expects lowercase provider ids; loosening here
        // would let a typo pass and eventually fail at provider
        // resolution with a less obvious error.
        assertEquals("default", defaultModelFor("Anthropic"))
        assertEquals("default", defaultModelFor("ANTHROPIC"))
        assertEquals("default", defaultModelFor("OpenAI"))
    }
}
