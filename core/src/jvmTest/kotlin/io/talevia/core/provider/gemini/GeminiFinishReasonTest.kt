package io.talevia.core.provider.gemini

import io.talevia.core.session.FinishReason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * String-set drift pins for [mapGeminiFinishReason] — the Gemini
 * `finishReason` + `sawToolCall` → [FinishReason] mapper, parallel
 * to the existing
 * [io.talevia.core.provider.anthropic.mapAnthropicStopReason] and
 * the cycle-319 sibling
 * [io.talevia.core.provider.openai.mapOpenAiFinishReason].
 *
 * **Why this is a richer pin surface than Anthropic / OpenAi**:
 * Gemini's mapper takes TWO inputs (raw + sawToolCall) and the
 * `"STOP"` value resolves to TOOL_CALLS or END_TURN depending on
 * the boolean. So drift can sneak in via either input axis — pinning
 * each (raw, sawToolCall) combination independently is the only
 * way to surface single-axis regressions cleanly.
 *
 * **5-string safety filter OR-list (`SAFETY` / `RECITATION` /
 * `BLOCKLIST` / `PROHIBITED_CONTENT` / `SPII`)**: each pinned
 * individually. Dropping `"SPII"` from the list would silently route
 * SPII-blocked responses through the default STOP fallback, making
 * them indistinguishable from a normal completion in cost / retry /
 * UI logic — same drift class as cycle 316 RetryClassifier OR-list
 * pins.
 *
 * Cycle 319 (audit-pattern fallback): refactored
 * `GeminiProvider.private fun mapFinish` to top-level `internal fun
 * mapGeminiFinishReason` so this test file can pin variants
 * directly. Symmetric with the OpenAi sibling refactor in the
 * same cycle.
 */
class GeminiFinishReasonTest {

    // ── STOP × sawToolCall axis (the boolean-disambiguation pin) ──

    @Test fun stopWithToolCallMapsToToolCalls() {
        // The disambiguation that requires the second input: `"STOP"` +
        // sawToolCall=true means the agent emitted a tool call, so the
        // session's stop is "agent waiting on tool result", not a real
        // turn-end. Drift to ignore sawToolCall would silently kill
        // the agent loop mid-tool-call dispatch.
        assertEquals(
            FinishReason.TOOL_CALLS,
            mapGeminiFinishReason("STOP", sawToolCall = true),
            "STOP + sawToolCall=true MUST be TOOL_CALLS (agent loop must dispatch)",
        )
    }

    @Test fun stopWithoutToolCallMapsToEndTurn() {
        assertEquals(
            FinishReason.END_TURN,
            mapGeminiFinishReason("STOP", sawToolCall = false),
            "STOP + sawToolCall=false MUST be END_TURN (clean turn end)",
        )
    }

    @Test fun nullWithToolCallMapsToToolCalls() {
        // Gemini's mid-stream chunks may have null finishReason but
        // include functionCall parts. Same disambiguation as STOP —
        // sawToolCall flips to TOOL_CALLS.
        assertEquals(
            FinishReason.TOOL_CALLS,
            mapGeminiFinishReason(null, sawToolCall = true),
        )
    }

    @Test fun nullWithoutToolCallMapsToStop() {
        // null + sawToolCall=false defaults to STOP (the universal
        // "agent finished, no surprises" bucket).
        assertEquals(
            FinishReason.STOP,
            mapGeminiFinishReason(null, sawToolCall = false),
        )
    }

    // ── MAX_TOKENS pin (boolean-independent) ──────────────────────

    @Test fun maxTokensMapsToMaxTokensRegardlessOfToolCall() {
        // MAX_TOKENS is the "client-set cap reached" signal that
        // shouldn't be overridden by sawToolCall — even mid-tool-
        // call, hitting max_tokens means the response was truncated
        // and the user might want to resume. Pin both boolean
        // values to the same outcome.
        assertEquals(FinishReason.MAX_TOKENS, mapGeminiFinishReason("MAX_TOKENS", sawToolCall = false))
        assertEquals(FinishReason.MAX_TOKENS, mapGeminiFinishReason("MAX_TOKENS", sawToolCall = true))
    }

    // ── 5-string safety filter OR-list (one pin each) ─────────────

    @Test fun safetyMapsToContentFilter() {
        // Gemini's primary safety category — most common content
        // filter trigger.
        assertEquals(FinishReason.CONTENT_FILTER, mapGeminiFinishReason("SAFETY", sawToolCall = false))
    }

    @Test fun recitationMapsToContentFilter() {
        // Gemini-specific: response would have copied training data
        // verbatim (copyright avoidance). Distinct from SAFETY but
        // still routes to CONTENT_FILTER on Talevia's side because
        // user-facing semantics are identical: "the model declined
        // to complete this turn for safety reasons".
        assertEquals(FinishReason.CONTENT_FILTER, mapGeminiFinishReason("RECITATION", sawToolCall = false))
    }

    @Test fun blocklistMapsToContentFilter() {
        // Gemini-specific: term-list match. Pin: not all 3 Google-
        // safety terms collapse to SAFETY in our mapper; each must
        // be in the OR-list separately.
        assertEquals(FinishReason.CONTENT_FILTER, mapGeminiFinishReason("BLOCKLIST", sawToolCall = false))
    }

    @Test fun prohibitedContentMapsToContentFilter() {
        // Gemini-specific: hard-blocked categories (CSAM, etc).
        assertEquals(
            FinishReason.CONTENT_FILTER,
            mapGeminiFinishReason("PROHIBITED_CONTENT", sawToolCall = false),
        )
    }

    @Test fun spiiMapsToContentFilter() {
        // Sensitive Personally Identifiable Information detection.
        // The most likely OR-list candidate to drift (least
        // recognizable acronym; easy to delete in a "tidy up the
        // string set" refactor without realising it's a real
        // production filter category). Pin protects against silent
        // SPII-route loss.
        assertEquals(FinishReason.CONTENT_FILTER, mapGeminiFinishReason("SPII", sawToolCall = false))
    }

    @Test fun safetyFiltersIgnoreSawToolCall() {
        // Safety filters should NOT be overridden by sawToolCall —
        // a content-filter trigger means the response was blocked
        // regardless of whether tool calls were attempted earlier.
        // Pin both boolean values to the same outcome for one
        // representative filter.
        assertEquals(FinishReason.CONTENT_FILTER, mapGeminiFinishReason("SAFETY", sawToolCall = true))
        assertEquals(FinishReason.CONTENT_FILTER, mapGeminiFinishReason("SAFETY", sawToolCall = false))
    }

    // ── Fallback pins ─────────────────────────────────────────────

    @Test fun unknownStringMapsToStopFallback() {
        // Any unrecognised non-null raw → STOP. Same fallback as
        // Anthropic / OpenAi mappers — never silently signal ERROR.
        assertEquals(FinishReason.STOP, mapGeminiFinishReason("UNKNOWN_REASON", sawToolCall = false))
        assertEquals(FinishReason.STOP, mapGeminiFinishReason("", sawToolCall = false))
    }

    @Test fun unknownStringFallbackIsBooleanIndependent() {
        // The else branch doesn't consult sawToolCall — pin observed
        // behaviour so a refactor that "harmonises" the else with
        // the null branch (which DOES consult sawToolCall) lands
        // in test-red.
        assertEquals(FinishReason.STOP, mapGeminiFinishReason("UNKNOWN_REASON", sawToolCall = true))
        assertEquals(FinishReason.STOP, mapGeminiFinishReason("UNKNOWN_REASON", sawToolCall = false))
    }

    @Test fun caseSensitiveUppercaseOnly() {
        // Gemini's protocol uses uppercase finishReason; the mapper
        // does NOT do case-insensitive matching. Pin observed
        // behaviour so a future refactor (or copy-paste from OpenAi's
        // lowercase mapper) doesn't silently start matching mixed
        // case.
        assertEquals(FinishReason.STOP, mapGeminiFinishReason("stop", sawToolCall = false))
        assertEquals(FinishReason.STOP, mapGeminiFinishReason("Stop", sawToolCall = false))
        assertEquals(FinishReason.STOP, mapGeminiFinishReason("safety", sawToolCall = false))
    }

    @Test fun allFiveMappedFinishReasonVariantsReachable() {
        // Coverage tally: STOP / END_TURN / TOOL_CALLS / MAX_TOKENS /
        // CONTENT_FILTER are the 5 outputs this mapper produces.
        // (ERROR + CANCELLED never reached — they come from the
        // outer LlmEvent layer, not the finishReason field.) Refactor
        // introducing a new branch must add a matching pin.
        val reachable = setOf(
            mapGeminiFinishReason("STOP", sawToolCall = true),
            mapGeminiFinishReason("STOP", sawToolCall = false),
            mapGeminiFinishReason("MAX_TOKENS", sawToolCall = false),
            mapGeminiFinishReason("SAFETY", sawToolCall = false),
            mapGeminiFinishReason("RECITATION", sawToolCall = false),
            mapGeminiFinishReason("BLOCKLIST", sawToolCall = false),
            mapGeminiFinishReason("PROHIBITED_CONTENT", sawToolCall = false),
            mapGeminiFinishReason("SPII", sawToolCall = false),
            mapGeminiFinishReason(null, sawToolCall = true),
            mapGeminiFinishReason(null, sawToolCall = false),
            mapGeminiFinishReason("WHATEVER", sawToolCall = false),
        )
        assertEquals(
            setOf(
                FinishReason.STOP,
                FinishReason.END_TURN,
                FinishReason.TOOL_CALLS,
                FinishReason.MAX_TOKENS,
                FinishReason.CONTENT_FILTER,
            ),
            reachable,
            "Gemini mapper produces exactly these 5 FinishReason variants today",
        )
    }
}
