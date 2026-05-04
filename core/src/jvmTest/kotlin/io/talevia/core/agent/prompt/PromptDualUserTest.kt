package io.talevia.core.agent.prompt

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Direct content tests for [PROMPT_DUAL_USER] —
 * `core/src/commonMain/kotlin/io/talevia/core/agent/prompt/PromptDualUser.kt:29`.
 * Cycle 281 audit: 0 test refs (sister lanes
 * PROMPT_AIGC_LANE / PROMPT_EDITING_LANE / PROMPT_EXTERNAL_LANE
 * also currently 0 — this is the highest-leverage one because
 * it carries the small-user / pro-user calibration contract
 * VISION §4 hangs on).
 *
 * Same audit-pattern fallback as cycles 207-280 — template-pin
 * family.
 *
 * `PROMPT_DUAL_USER` is in the static base prompt (every turn,
 * not greenfield-conditional), spliced into
 * `TALEVIA_SYSTEM_PROMPT_BASE` between PROMPT_BUILD_SYSTEM and
 * PROMPT_AIGC_LANE. It costs ~250-300 tokens/turn and encodes
 * the core behavior contract: when to act autonomously vs when
 * to execute exactly. Drift to soften any of the "don't chain
 * bullet menus" / "prefer pro-user precision" anti-patterns
 * silently re-enables the failure modes traces show the LLM
 * defaulting to without the lane.
 *
 * Drift signals:
 *   - **Soften the bullet-menu anti-pattern** → LLM regresses
 *     to clarifying-question chains on vague intent.
 *   - **Remove "prefer pro-user precision" tiebreaker** → LLM
 *     starts auto-expanding scope on specific instructions
 *     ("improve adjacent things").
 *   - **Drop the shared-registry note** → LLM may shadow-create
 *     parallel state for "manual mode" instead of reusing
 *     source nodes from the autonomous phase.
 *
 * Pins via marker-substring presence (not byte-identical
 * snapshot). Marker substrings are chosen to be the smallest
 * canary that catches a *behaviour-changing* edit — not just
 * any wording polish. A copy-edit that tightens phrasing while
 * preserving the contract should NOT break these pins.
 */
class PromptDualUserTest {

    /**
     * Lane content with whitespace runs normalised to single
     * spaces, so cross-line phrases like "sensible\n  defaults"
     * match a flat "sensible defaults" substring. Pin tests use
     * this view when the substring spans a line wrap.
     */
    private val flat: String = PROMPT_DUAL_USER.replace(Regex("\\s+"), " ")

    @Test fun headerNamesVisionRefAndCjkTension() {
        // Marquee header pin: ties the lane back to VISION §4
        // and the 双用户张力 framing. Drift to drop the §4
        // citation or the CJK term silently weakens the
        // mental-model anchor.
        assertTrue(
            "# Two paths, one project" in flat,
            "header MUST name the dual-path framing; got first 200: ${flat.take(200)}",
        )
        assertTrue(
            "VISION §4" in flat,
            "header MUST cite VISION §4 (where the 双用户 tension lives)",
        )
        assertTrue(
            "双用户张力" in flat,
            "header MUST carry the 双用户张力 CJK marker (mirror of vision doc heading)",
        )
    }

    @Test fun bothUserTypesNamedInCjkAndEnglish() {
        // Pin: lane MUST surface both user types in BOTH
        // English (high-level intent / specific instruction)
        // AND CJK (小白用户 / 专家用户). Drift to drop either
        // mode silently breaks the parity that makes the lane
        // load-bearing for both audiences.
        assertTrue("小白用户" in flat, "MUST name 小白用户 (small-user) in CJK")
        assertTrue("专家用户" in flat, "MUST name 专家用户 (pro-user) in CJK")
        assertTrue(
            "small-user" in flat.lowercase(),
            "MUST name small-user in English",
        )
        assertTrue(
            "pro-user" in flat.lowercase(),
            "MUST name pro-user in English",
        )
    }

    @Test fun calibrationFramingIsOperationDepthNotSeparatePipelines() {
        // Marquee mental-model pin: per the doc-comment, the
        // two paths differ in "operation depth", NOT in
        // pipelines. Drift to "two pipelines" / "two
        // codebases" silently invites forking the system. The
        // lane MUST anchor on the depth-not-pipeline framing.
        assertTrue(
            "operation depth" in flat,
            "MUST name 'operation depth' as the discriminator (not 'separate pipelines')",
        )
        assertTrue(
            "not in pipelines" in flat,
            "MUST explicitly disclaim pipeline divergence",
        )
    }

    @Test fun smallUserModeForbidsBulletMenuClarifyingQuestions() {
        // Marquee anti-pattern pin #1: traces show the LLM
        // defaulting to bullet-list clarifying questions on
        // vague intent. The lane MUST forbid this verbatim.
        // Drift to soften ("Don't chain bullet menus") without
        // a strong replacement re-enables the failure mode.
        assertTrue(
            "Don't chain bullet menus" in flat,
            "small-user mode MUST forbid bullet-menu clarifying questions; got flat: $flat",
        )
        assertTrue(
            "sensible defaults" in flat,
            "small-user mode MUST tell the agent to pick sensible defaults instead",
        )
        assertTrue(
            "report them inline" in flat,
            "small-user mode MUST instruct to report defaults inline (not silently)",
        )
    }

    @Test fun proUserModeForbidsScopeExpansion() {
        // Marquee anti-pattern pin #2: traces show the LLM
        // auto-expanding scope on specific instructions ("I
        // also improved..."). Lane MUST forbid this.
        assertTrue(
            "Execute exactly that, nothing else" in flat,
            "pro-user mode MUST contain the exactness mandate verbatim",
        )
        assertTrue(
            "Don't expand scope" in flat,
            "pro-user mode MUST forbid scope expansion",
        )
        assertTrue(
            "improve" in flat.lowercase(),
            "pro-user mode MUST address the 'improve adjacent things' anti-pattern",
        )
    }

    @Test fun mixedModeIsExplicitlyHandled() {
        // Pin: ambiguous-mixed input is the third case the
        // lane MUST handle (without it, the LLM falls back to
        // one polar mode and mis-handles the named scope).
        assertTrue(
            "ambiguous" in flat.lowercase(),
            "MUST name the mixed/ambiguous third case",
        )
        assertTrue(
            "Never expand beyond" in flat,
            "mixed mode MUST forbid expanding beyond named scope",
        )
    }

    @Test fun sharedRegistryNotePresentNoParallelState() {
        // Marquee shared-state pin: the lane MUST tell the
        // agent that 小白 → 专家 handoff reuses source nodes
        // without "translation". Drift to drop this re-enables
        // shadow-state for "manual mode".
        assertTrue(
            "same source DAG and tool registry" in flat,
            "MUST anchor on shared source DAG + tool registry across modes",
        )
        assertTrue(
            "shadow-create parallel state" in flat,
            "MUST forbid parallel-state creation for 'manual mode'",
        )
    }

    @Test fun tieBreakerPrefersProUserPrecision() {
        // Marquee tiebreaker pin: when calibration is
        // uncertain, the lane MUST tilt toward pro-user
        // precision (under-execution recoverable, over-
        // execution not).
        assertTrue(
            "When in doubt" in flat,
            "MUST contain 'When in doubt' tiebreaker phrasing",
        )
        assertTrue(
            "prefer pro-user precision" in flat,
            "tiebreaker MUST prefer pro-user precision (not the polar opposite)",
        )
        assertTrue(
            "harder to undo" in flat,
            "tiebreaker MUST justify with the over-execution-irreversible argument",
        )
    }

    @Test fun smallUserExampleIsConcreteVlogPhrasing() {
        // Pin: the lane MUST carry at least one CONCRETE
        // small-user example (not abstract description) so the
        // LLM has a recognisable pattern. Drift to genericise
        // weakens the few-shot signal.
        assertTrue(
            "make me a vlog" in flat ||
                "draft something nice" in flat ||
                "随便整一个" in flat,
            "MUST carry at least one concrete small-user example phrase (vlog / draft / 随便整一个)",
        )
    }

    @Test fun proUserExampleIsConcreteEditPhrasing() {
        // Sister concrete-example pin for pro-user mode.
        assertTrue(
            "compress 0:12-0:18 by 0.3 dB" in flat ||
                "swap the third clip's LUT" in flat ||
                "rename character" in flat,
            "MUST carry at least one concrete pro-user instruction example",
        )
    }

    @Test fun lengthIsBoundedAndMeaningful() {
        // Pin: lane is in the static base — every turn pays
        // its token cost. Per its doc-comment, it should be
        // ~250-300 tokens. Sanity-check the char-length is in
        // a sane band: not so short it's no-op, not so long
        // it's bloated. ~250-300 tokens ≈ 1000-1500 chars
        // (mix of CJK + English, CJK ≈ 1.5 chars/token,
        // English ≈ 4 chars/token).
        val s = PROMPT_DUAL_USER
        assertTrue(
            s.length > 600,
            "lane content MUST be > 600 chars (drift to no-op surfaces here); got: ${s.length}",
        )
        assertTrue(
            s.length < 4000,
            "lane content MUST be < 4000 chars (drift to bloated surfaces here); got: ${s.length}",
        )
    }

    @Test fun laneIsTrimmedNoLeadingOrTrailingBlankLines() {
        // Pin: the lane is `.trimIndent()` then assembled
        // via `joinToString("\n\n")` with sister lanes.
        // Leading / trailing whitespace in the val itself
        // would corrupt the section-separator invariant. Pin
        // documents the trim contract.
        val s = PROMPT_DUAL_USER
        assertTrue(
            s == s.trim(),
            "lane MUST be trimmed (no leading/trailing whitespace); got front=${s.take(20).map { it.code }} back=${s.takeLast(20).map { it.code }}",
        )
    }
}
