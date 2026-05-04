package io.talevia.core.provider.openai

import io.talevia.core.session.FinishReason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * String-set drift pins for [mapOpenAiFinishReason] — the OpenAI
 * `finish_reason` → [FinishReason] mapper, parallel to the existing
 * [io.talevia.core.provider.anthropic.mapAnthropicStopReason] (9 tests
 * pinned cycle <pre-281>) and the new
 * [io.talevia.core.provider.gemini.mapGeminiFinishReason] (parallel
 * pin file in this cycle).
 *
 * **Why per-string pinning matters**: each branch in the `when (raw)`
 * is load-bearing for production correctness. A refactor that drops
 * `"function_call"` from the TOOL_CALLS OR-list would silently
 * misclassify every legacy OpenAI `function_call` finish as STOP —
 * the agent loop would treat the turn as "done", leaving any tool
 * arg-stream half-emitted with no pending dispatch. Same drift class
 * as cycle 311/312/316/318 — pin every load-bearing literal so a
 * refactor lands in test-red instead of silent misbehaviour.
 *
 * Cycle 319 (audit-pattern fallback): refactored
 * `OpenAiProvider.private fun mapFinish` to top-level `internal fun
 * mapOpenAiFinishReason` so this test file can pin variants
 * directly without instantiating the provider. Mirrors Anthropic's
 * pre-existing top-level structure.
 */
class OpenAiFinishReasonTest {

    @Test fun stopMapsToStop() {
        assertEquals(FinishReason.STOP, mapOpenAiFinishReason("stop"))
    }

    @Test fun lengthMapsToMaxTokens() {
        // OpenAI's `"length"` = client-side max_tokens cap reached.
        // Drift to STOP would silently lose the "you can resume by
        // raising max_tokens" UX signal.
        assertEquals(FinishReason.MAX_TOKENS, mapOpenAiFinishReason("length"))
    }

    @Test fun toolCallsMapsToToolCalls() {
        assertEquals(FinishReason.TOOL_CALLS, mapOpenAiFinishReason("tool_calls"))
    }

    @Test fun functionCallMapsToToolCalls() {
        // Legacy OpenAI completions API used `"function_call"` (singular)
        // before the move to `"tool_calls"`. Both still seen in production
        // depending on which API a model lineage exposes; the OR-list keeps
        // both pointed at the same Talevia-side category.
        assertEquals(FinishReason.TOOL_CALLS, mapOpenAiFinishReason("function_call"))
    }

    @Test fun contentFilterMapsToContentFilter() {
        assertEquals(FinishReason.CONTENT_FILTER, mapOpenAiFinishReason("content_filter"))
    }

    @Test fun unknownStringMapsToStopFallback() {
        // Any unrecognised raw → STOP fallback. Pin: do NOT silently
        // route to ERROR or some other hard signal — STOP is the
        // safest "agent finished" bucket.
        assertEquals(FinishReason.STOP, mapOpenAiFinishReason("totally_unknown"))
        assertEquals(FinishReason.STOP, mapOpenAiFinishReason(""))
    }

    @Test fun nullMapsToStopFallback() {
        // Null = SSE chunk had no finish_reason yet (pre-final). Same
        // STOP fallback as unknown — the agent shouldn't crash on
        // partial state.
        assertEquals(FinishReason.STOP, mapOpenAiFinishReason(null))
    }

    @Test fun caseSensitiveLowercaseOnly() {
        // OpenAI's protocol uses lowercase finish_reason; the mapper
        // does NOT do case-insensitive matching. Pin observed
        // behaviour so a future refactor (or copy-paste from Gemini's
        // uppercase mapper) doesn't silently start matching mixed
        // case.
        assertEquals(FinishReason.STOP, mapOpenAiFinishReason("STOP"))
        assertEquals(FinishReason.STOP, mapOpenAiFinishReason("Length")) // not MAX_TOKENS
        assertEquals(FinishReason.STOP, mapOpenAiFinishReason("Tool_Calls"))
    }

    @Test fun allFiveMappedFinishReasonVariantsReachable() {
        // Coverage tally: every FinishReason that mapOpenAiFinishReason
        // CAN return must be exercised above — STOP, MAX_TOKENS,
        // TOOL_CALLS, CONTENT_FILTER. (END_TURN, ERROR, CANCELLED are
        // never returned by this mapper — Anthropic uses END_TURN
        // for its semantically-equivalent stop reason; OpenAI does
        // not.) Pin: refactor introducing a new branch should add a
        // matching pin here.
        val reachable = setOf(
            mapOpenAiFinishReason("stop"),
            mapOpenAiFinishReason("length"),
            mapOpenAiFinishReason("tool_calls"),
            mapOpenAiFinishReason("function_call"),
            mapOpenAiFinishReason("content_filter"),
            mapOpenAiFinishReason("unknown"),
            mapOpenAiFinishReason(null),
        )
        assertEquals(
            setOf(
                FinishReason.STOP,
                FinishReason.MAX_TOKENS,
                FinishReason.TOOL_CALLS,
                FinishReason.CONTENT_FILTER,
            ),
            reachable,
            "OpenAi mapper produces exactly these 4 FinishReason variants today",
        )
    }
}
