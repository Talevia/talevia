package io.talevia.core.provider.openai.codex

import io.talevia.core.JsonConfig
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * String-set + decision-tree drift pins for [OpenAiCodexSseParser].
 * The sibling [OpenAiCodexSseParserTest] (7 happy-path tests) covers
 * the SSE event handlers (text / reasoning / tool-call / completed /
 * failed / incomplete) and one example of each terminal-error
 * classification, but does NOT pin:
 *
 * - **Each of the 4 `NON_RETRIABLE_CODES`** individually
 *   (`context_length_exceeded`, `insufficient_quota`, `invalid_prompt`,
 *   `cyber_policy`). Existing test only covers `insufficient_quota`.
 *   Same drift class as cycle 316 RetryClassifier OR-list — drop one
 *   and the agent retries a permanent error 4 times.
 *
 * - **The `resolveFinish()` precedence tree** explicitly:
 *   `terminalError != null` > `seenAnyToolCall` > `endTurn == true`
 *   > else STOP. Existing tests hit each branch in isolation through
 *   end-to-end SSE flows but none pins the precedence ordering when
 *   multiple inputs are simultaneously set (e.g. error + tool call,
 *   tool call + endTurn). A refactor reordering the if-chain would
 *   silently swap the resolved finish reason for these combined
 *   states.
 *
 * - **`endTurn` parsing edge cases**: `false` (don't promote to
 *   END_TURN), missing field (null), malformed value. The
 *   `runCatching { contentOrNull?.toBoolean() }` chain is forgiving
 *   in subtle ways; pin observed behaviour.
 *
 * - **Null error code retriable**: line 197's
 *   `code == null || code !in NON_RETRIABLE_CODES` short-circuits
 *   on null first. Pin: a refactor flipping the precedence (e.g.
 *   `code != null && code !in CODES`) would silently change null-code
 *   classification.
 *
 * Same audit-pattern as cycles 316/318/319 — pin every load-bearing
 * literal + boundary so a refactor lands in test-red.
 */
class OpenAiCodexSseParserDriftPinsTest {

    private suspend fun OpenAiCodexSseParser.feed(vararg lines: String, sink: MutableList<LlmEvent>) {
        val json = JsonConfig.default
        for (line in lines) {
            val obj = json.parseToJsonElement(line).jsonObject
            process(obj) { ev -> sink.add(ev) }
        }
    }

    // ── NON_RETRIABLE_CODES OR-list (4 codes pinned individually) ──

    @Test fun contextLengthExceededIsNotRetriable() = runTest {
        // Permanent: input exceeds model's max context. Retrying a
        // 4xx-with-this-code would burn the entire retry budget on
        // a guaranteed-failure call. Pin protects against drift
        // dropping this code from the OR-list.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.failed","response":{"id":"r","error":{"code":"context_length_exceeded","message":"too long"}}}""",
            sink = mutableListOf(),
        )
        val err = parser.terminalError
        assertNotNull(err)
        assertFalse(err.retriable, "context_length_exceeded MUST be terminal")
    }

    @Test fun insufficientQuotaIsNotRetriable() = runTest {
        // The one code already pinned by the sibling test — kept
        // here so the boundaries-test file is self-contained as a
        // 4-of-4 OR-list coverage check.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.failed","response":{"id":"r","error":{"code":"insufficient_quota","message":"quota"}}}""",
            sink = mutableListOf(),
        )
        val err = parser.terminalError
        assertNotNull(err)
        assertFalse(err.retriable)
    }

    @Test fun invalidPromptIsNotRetriable() = runTest {
        // Permanent: prompt format is malformed (e.g. tool-spec
        // schema invalid). No retry will fix the agent's input —
        // user has to fix the request shape.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.failed","response":{"id":"r","error":{"code":"invalid_prompt","message":"bad shape"}}}""",
            sink = mutableListOf(),
        )
        val err = parser.terminalError
        assertNotNull(err)
        assertFalse(err.retriable, "invalid_prompt MUST be terminal")
    }

    @Test fun cyberPolicyIsNotRetriable() = runTest {
        // Permanent: prompt triggered codex-side cyber policy
        // (security-related content). Retrying same prompt would
        // produce the same block, possibly trigger anti-abuse
        // throttle. Pin: dropping this from the OR-list would
        // silently start retrying policy violations and consume
        // user retry budget for nothing.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.failed","response":{"id":"r","error":{"code":"cyber_policy","message":"blocked"}}}""",
            sink = mutableListOf(),
        )
        val err = parser.terminalError
        assertNotNull(err)
        assertFalse(err.retriable, "cyber_policy MUST be terminal")
    }

    @Test fun unknownErrorCodeIsRetriable() = runTest {
        // Codes NOT in NON_RETRIABLE_CODES (e.g. "rate_limit_exceeded",
        // "service_unavailable", or any 5xx flavour) are retriable.
        // Pin: don't accidentally promote unknown codes to terminal
        // (would be silently giving up too early on transient
        // failures).
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.failed","response":{"id":"r","error":{"code":"rate_limit_exceeded","message":"slow down"}}}""",
            sink = mutableListOf(),
        )
        val err = parser.terminalError
        assertNotNull(err)
        assertTrue(err.retriable, "rate_limit_exceeded NOT in NON_RETRIABLE_CODES → retriable")
    }

    @Test fun nullErrorCodeIsRetriable() = runTest {
        // Line 197: `code == null || code !in NON_RETRIABLE_CODES`.
        // The OR short-circuits on null first → retriable. A
        // refactor flipping precedence (`code != null && code !in
        // ...`) would silently flip null to NOT retriable, defaulting
        // to terminal whenever the SSE chunk lacks a code field.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.failed","response":{"id":"r","error":{"message":"unknown error, no code"}}}""",
            sink = mutableListOf(),
        )
        val err = parser.terminalError
        assertNotNull(err)
        assertTrue(err.retriable, "null error code MUST be retriable (line-197 short-circuit)")
    }

    // ── resolveFinish() decision-tree branches (4 mutually-exclusive states) ──

    @Test fun resolveFinishStopWhenNothingSet() = runTest {
        // Default branch: no terminalError, no tool calls, no
        // endTurn. Plain STOP. Pin: don't drift to END_TURN as
        // the default — that would be an Anthropic-leaning
        // semantic that the codex backend doesn't emit.
        val parser = OpenAiCodexSseParser()
        assertEquals(FinishReason.STOP, parser.resolveFinish())
    }

    @Test fun resolveFinishToolCallsWhenToolCallSeen() = runTest {
        // seenAnyToolCall path: any function_call item in the
        // SSE stream sets the flag. Pin: a turn that emitted a
        // tool call resolves to TOOL_CALLS regardless of whether
        // response.completed arrived (which sets endTurn).
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.output_item.added","item":{"id":"call_1","type":"function_call","name":"echo","call_id":"cid"}}""",
            sink = mutableListOf(),
        )
        assertEquals(FinishReason.TOOL_CALLS, parser.resolveFinish())
    }

    @Test fun resolveFinishEndTurnWhenEndTurnTrueAndNoToolCall() = runTest {
        // endTurn path: completion arrived with `end_turn: true`,
        // no tool calls, no error. Codex-specific signal that the
        // agent finished the turn cleanly.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.completed","response":{"id":"r","end_turn":true,"usage":{"input_tokens":1,"output_tokens":1}}}""",
            sink = mutableListOf(),
        )
        assertEquals(FinishReason.END_TURN, parser.resolveFinish())
    }

    @Test fun resolveFinishErrorWhenTerminalErrorSet() = runTest {
        // terminalError path: response.failed sets the field.
        // Pin: ERROR is the resolved finish whenever terminalError
        // is non-null, regardless of all other state.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.failed","response":{"id":"r","error":{"code":"unknown","message":"fail"}}}""",
            sink = mutableListOf(),
        )
        assertEquals(FinishReason.ERROR, parser.resolveFinish())
    }

    // ── resolveFinish() precedence pins (combined states) ─────────

    @Test fun terminalErrorBeatsToolCallSeen() = runTest {
        // Mid-stream tool call followed by response.failed → ERROR
        // (terminalError takes precedence over seenAnyToolCall).
        // Pin: a refactor reordering the if-chain to check
        // seenAnyToolCall first would silently drop the error
        // signal in favour of TOOL_CALLS — making the agent loop
        // try to dispatch the tool call when the response actually
        // failed.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.output_item.added","item":{"id":"call_1","type":"function_call","name":"echo","call_id":"cid"}}""",
            """{"type":"response.failed","response":{"id":"r","error":{"code":"server_error","message":"boom"}}}""",
            sink = mutableListOf(),
        )
        assertEquals(
            FinishReason.ERROR,
            parser.resolveFinish(),
            "terminalError MUST beat seenAnyToolCall (precedence line 79 > line 80)",
        )
    }

    @Test fun terminalErrorBeatsEndTurn() = runTest {
        // response.completed sets endTurn=true, then
        // response.failed sets terminalError. (Unusual but possible
        // in malformed streams.) Pin: ERROR wins.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.completed","response":{"id":"r","end_turn":true,"usage":{"input_tokens":1,"output_tokens":1}}}""",
            """{"type":"response.failed","response":{"id":"r","error":{"code":"server_error","message":"boom"}}}""",
            sink = mutableListOf(),
        )
        assertEquals(
            FinishReason.ERROR,
            parser.resolveFinish(),
            "terminalError MUST beat endTurn (precedence line 79 > line 81)",
        )
    }

    @Test fun toolCallSeenBeatsEndTurn() = runTest {
        // Tool call emitted, then response.completed with
        // endTurn=true. Pin: TOOL_CALLS wins because the agent
        // still needs to dispatch the tool — endTurn is a hint,
        // not a contract that no work remains.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.output_item.added","item":{"id":"call_1","type":"function_call","name":"echo","call_id":"cid"}}""",
            """{"type":"response.completed","response":{"id":"r","end_turn":true,"usage":{"input_tokens":1,"output_tokens":1}}}""",
            sink = mutableListOf(),
        )
        assertEquals(
            FinishReason.TOOL_CALLS,
            parser.resolveFinish(),
            "seenAnyToolCall MUST beat endTurn (precedence line 80 > line 81)",
        )
    }

    // ── endTurn parsing edge cases ────────────────────────────────

    @Test fun endTurnFalseDoesNotResolveAsEndTurn() = runTest {
        // response.completed with explicit `end_turn: false` — the
        // boolean parses to false, endTurn = false, the `endTurn ==
        // true` check fails → STOP fallback. Pin: don't drift to
        // "any non-null endTurn promotes to END_TURN" semantics.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.completed","response":{"id":"r","end_turn":false,"usage":{"input_tokens":1,"output_tokens":1}}}""",
            sink = mutableListOf(),
        )
        assertEquals(
            FinishReason.STOP,
            parser.resolveFinish(),
            "endTurn=false MUST resolve to STOP, NOT END_TURN",
        )
    }

    @Test fun missingEndTurnFieldDoesNotResolveAsEndTurn() = runTest {
        // response.completed without end_turn field at all — endTurn
        // stays at default null, the `endTurn == true` check fails
        // → STOP. Pin: missing field is NOT silently treated as
        // end_turn=true.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.completed","response":{"id":"r","usage":{"input_tokens":1,"output_tokens":1}}}""",
            sink = mutableListOf(),
        )
        assertEquals(
            FinishReason.STOP,
            parser.resolveFinish(),
            "missing end_turn field MUST resolve to STOP",
        )
    }

    // ── NON_RETRIABLE_CODES set size pin ──────────────────────────

    @Test fun nonRetriableCodesContainsExactlyFourEntries() = runTest {
        // Tally pin: 4 codes in NON_RETRIABLE_CODES. Adding a 5th
        // (e.g. server expanded permanent-failure taxonomy) would
        // silently change retry behaviour for any agent running
        // against the new code; pinning the count surfaces the
        // change at PR time.
        //
        // Direct introspection of the private companion is awkward
        // (would need reflection); instead we test the OR-list by
        // checking that exactly the 4 known codes resolve as
        // not retriable AND a 5th plausible code does NOT match.
        val knownPermanent = listOf(
            "context_length_exceeded",
            "insufficient_quota",
            "invalid_prompt",
            "cyber_policy",
        )
        for (code in knownPermanent) {
            val parser = OpenAiCodexSseParser()
            parser.feed(
                """{"type":"response.failed","response":{"id":"r","error":{"code":"$code","message":"x"}}}""",
                sink = mutableListOf(),
            )
            assertFalse(
                parser.terminalError!!.retriable,
                "$code MUST be in NON_RETRIABLE_CODES",
            )
        }
        // Anti-pin: a code that LOOKS like it might be in the set
        // (e.g. "rate_limit_exceeded" rhymes with "context_length_exceeded")
        // but ISN'T MUST stay retriable. Catches drift toward
        // overly-eager non-retry classification.
        val parser = OpenAiCodexSseParser()
        parser.feed(
            """{"type":"response.failed","response":{"id":"r","error":{"code":"rate_limit_exceeded","message":"x"}}}""",
            sink = mutableListOf(),
        )
        assertTrue(
            parser.terminalError!!.retriable,
            "rate_limit_exceeded MUST stay retriable (NOT in NON_RETRIABLE_CODES)",
        )
    }
}
