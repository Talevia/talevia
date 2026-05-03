package io.talevia.core.provider.anthropic

import io.talevia.core.session.FinishReason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests for [mapAnthropicStopReason] —
 * `core/provider/anthropic/AnthropicRequestBody.kt:184`. Maps the
 * Anthropic-side `stop_reason` enum onto Talevia's normalised
 * [FinishReason]. Cycle 221 audit: 0 direct test refs (the sister
 * `buildAnthropicRequestBody` is covered by `AnthropicProviderTest`,
 * but the stop-reason mapper has been on its own since landing).
 *
 * Same audit-pattern fallback as cycles 207-220. Drift in this
 * mapping silently mis-classifies the agent loop's view of why a
 * turn ended — `MAX_TOKENS` instead of `END_TURN` would trigger an
 * unnecessary continuation; `STOP` instead of `TOOL_CALLS` would
 * make the loop think no tool was invoked when one was.
 *
 * Three correctness contracts pinned:
 *
 *  1. **Documented Anthropic enum values map to specific
 *     FinishReason variants:**
 *     - `"end_turn"` → END_TURN (the canonical assistant-finished
 *       signal).
 *     - `"max_tokens"` → MAX_TOKENS (loop should retry the same
 *       turn with a higher max_completion_tokens).
 *     - `"stop_sequence"` → STOP (sequence-matched termination,
 *       distinct from end_turn).
 *     - `"tool_use"` → TOOL_CALLS (anchors that the assistant
 *       turn included tool invocations the loop must dispatch).
 *
 *  2. **`"refusal"` and `"stop"` collapse to STOP.** Anthropic's
 *     newer refusal terminations and legacy `stop` enum both fall
 *     into the safest neutral terminator. Drift to "introduce
 *     REFUSAL" without updating the mapper would leave refusals
 *     silently classified as MAX_TOKENS or similar.
 *
 *  3. **Unknown / null → STOP fallback.** Per kdoc: "the safest
 *     default". An unrecognised future Anthropic enum value
 *     (e.g. a new `"safety_block"` reason) maps to STOP rather
 *     than crashing or returning null. Drift to throw would break
 *     every dispatch when Anthropic ships a new reason.
 */
class AnthropicStopReasonTest {

    // ── 1. Documented enum mappings ─────────────────────────

    @Test fun endTurnMapsToEndTurn() {
        assertEquals(FinishReason.END_TURN, mapAnthropicStopReason("end_turn"))
    }

    @Test fun maxTokensMapsToMaxTokens() {
        assertEquals(FinishReason.MAX_TOKENS, mapAnthropicStopReason("max_tokens"))
    }

    @Test fun stopSequenceMapsToStop() {
        // Pin: distinct enum from `end_turn`. Anthropic emits
        // stop_sequence when the response ended on a configured
        // stop sequence; we collapse to STOP because Talevia
        // doesn't expose a separate STOP_SEQUENCE variant.
        assertEquals(FinishReason.STOP, mapAnthropicStopReason("stop_sequence"))
    }

    @Test fun toolUseMapsToToolCalls() {
        // Marquee tool-anchoring pin: drift here would make the
        // loop think no tool was invoked when one was, dropping
        // the dispatch entirely.
        assertEquals(FinishReason.TOOL_CALLS, mapAnthropicStopReason("tool_use"))
    }

    // ── 2. Refusal + legacy stop collapse to STOP ──────────

    @Test fun refusalMapsToStop() {
        // Pin: Anthropic added refusal as a top-level stop_reason
        // in newer API versions. The mapper collapses it into STOP
        // (Talevia doesn't expose a REFUSAL variant). Drift to
        // "introduce REFUSAL FinishReason" would silently route
        // refusals through code paths expecting STOP.
        assertEquals(FinishReason.STOP, mapAnthropicStopReason("refusal"))
    }

    @Test fun legacyStopValueMapsToStop() {
        // Pin: per kdoc, `"stop"` is a legacy enum value that some
        // Anthropic responses still emit. Maps to STOP same as
        // `stop_sequence` and `refusal`.
        assertEquals(FinishReason.STOP, mapAnthropicStopReason("stop"))
    }

    // ── 3. Unknown / null fallback to STOP ─────────────────

    @Test fun unknownValueMapsToStopFallback() {
        // Marquee fallback pin: per kdoc "the safest default". An
        // unrecognised future Anthropic enum (e.g. `safety_block`
        // not yet documented) MUST map to STOP rather than crash
        // or return null. Drift to throw would break every
        // dispatch the day Anthropic ships a new reason.
        for (unknown in listOf("safety_block", "frobnicate", "future_reason", "")) {
            assertEquals(
                FinishReason.STOP,
                mapAnthropicStopReason(unknown),
                "unknown '$unknown' must map to STOP fallback",
            )
        }
    }

    @Test fun nullMapsToStopFallback() {
        // Pin: null input (response missing the field) falls through
        // to the same STOP default as unknown values.
        assertEquals(FinishReason.STOP, mapAnthropicStopReason(null))
    }

    // ── Exhaustive coverage of FinishReason variants used ──

    @Test fun allFourMappedFinishReasonVariantsReachable() {
        // Pin: the mapper produces 4 distinct FinishReason values
        // across its branches — END_TURN, MAX_TOKENS, STOP,
        // TOOL_CALLS. Drift to "always STOP" or "drop a branch"
        // would collapse the variant set; this test catches that
        // by computing the reached set.
        val reached = listOf("end_turn", "max_tokens", "stop_sequence", "tool_use")
            .map { mapAnthropicStopReason(it) }
            .toSet()
        assertEquals(
            setOf(
                FinishReason.END_TURN,
                FinishReason.MAX_TOKENS,
                FinishReason.STOP,
                FinishReason.TOOL_CALLS,
            ),
            reached,
            "all 4 mapped FinishReason variants must be reachable",
        )
    }
}
