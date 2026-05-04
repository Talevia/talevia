package io.talevia.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for the security-bound input-length constants in
 * `apps/server/src/main/kotlin/io/talevia/server/ServerValidation.kt`.
 * Cycle 275 audit: 0 test refs against [MAX_TEXT_FIELD_LENGTH]
 * or [MAX_TITLE_LENGTH] (the existing
 * [ServerValidationTest] covers the helper functions
 * `requireLength` / `requireReasonableId` but not the bound
 * constants themselves).
 *
 * Same audit-pattern fallback as cycles 207-274.
 *
 * The two constants are the security boundary against
 * adversarial JSON-stuffing attacks on user-supplied text
 * fields:
 *
 *   - `MAX_TEXT_FIELD_LENGTH = 128 * 1024 = 131072` — applied
 *     to prompts and other long-form text fields. Per the
 *     kdoc: "the largest Anthropic context window is 200k
 *     tokens ≈ 600k chars; we never want a single request
 *     body to approach that."
 *   - `MAX_TITLE_LENGTH = 256` — applied to short strings
 *     like session titles where 256 is a hard plenty.
 *
 * Drift signals:
 *   - Drift to remove the `* 1024` factor in
 *     MAX_TEXT_FIELD_LENGTH would silently shrink the cap
 *     to 128 chars (LLM prompts are typically 100s of
 *     chars at minimum — most legitimate prompts would
 *     fail).
 *   - Drift to a smaller MAX_TITLE_LENGTH (e.g. 64) would
 *     silently reject most legitimate session titles.
 *   - Drift to an enormous MAX_TEXT_FIELD_LENGTH would
 *     remove the security guard, silently letting
 *     adversaries stuff multi-MB JSON into a single field.
 *   - Drift to MAX_TITLE_LENGTH > MAX_TEXT_FIELD_LENGTH
 *     would create an inconsistent semantic (titles tighter
 *     than prompts is the design intent).
 *
 * Pins three correctness contracts:
 *
 *  1. **Exact values** for each constant — the security
 *     boundary must not silently shift.
 *
 *  2. **Cross-constant invariant**: titles tighter than
 *     prompts (MAX_TITLE_LENGTH < MAX_TEXT_FIELD_LENGTH).
 *     Drift to invert would create a semantic mismatch.
 *
 *  3. **Magnitude sanity**: both constants in expected
 *     order-of-magnitude ranges (titles in hundreds of
 *     chars; prompts in 100s of KB) so a typo (extra/
 *     missing zero / wrong factor) surfaces here.
 */
class ServerValidationConstantsTest {

    @Test fun maxTextFieldLengthIs128KiB() {
        // Marquee value pin: 128 * 1024 = 131072 chars
        // (128 KiB at 1 char/byte). Drift to drop the
        // `* 1024` factor would silently cap text fields
        // at 128 chars (most legitimate prompts fail).
        assertEquals(
            131_072,
            MAX_TEXT_FIELD_LENGTH,
            "MAX_TEXT_FIELD_LENGTH MUST be 128 * 1024 = 131072 chars (128 KiB cap)",
        )
    }

    @Test fun maxTextFieldLengthEquals128TimesKilo() {
        // Sister redundant pin: also assert via the
        // multiplicative form so a refactor that breaks
        // either expression silently surfaces here.
        assertEquals(
            128 * 1024,
            MAX_TEXT_FIELD_LENGTH,
            "MAX_TEXT_FIELD_LENGTH must equal 128 * 1024",
        )
    }

    @Test fun maxTitleLengthIs256Chars() {
        // Marquee value pin: 256 chars for short strings
        // (session titles, etc.). Drift to 64 would silently
        // reject most legitimate session titles.
        assertEquals(
            256,
            MAX_TITLE_LENGTH,
            "MAX_TITLE_LENGTH MUST be 256 chars",
        )
    }

    @Test fun titlesAreTighterThanTextFields() {
        // Marquee cross-constant invariant pin: per the kdoc,
        // titles are intentionally tighter than long-form
        // text fields. Drift to invert (or equate) would
        // create a semantic mismatch with the field's
        // declared role.
        assertTrue(
            MAX_TITLE_LENGTH < MAX_TEXT_FIELD_LENGTH,
            "MAX_TITLE_LENGTH ($MAX_TITLE_LENGTH) MUST be tighter than MAX_TEXT_FIELD_LENGTH ($MAX_TEXT_FIELD_LENGTH)",
        )
    }

    @Test fun textFieldLengthIsInExpectedKilobyteRange() {
        // Sanity magnitude pin: 64 KiB ≤ MAX_TEXT_FIELD_LENGTH
        // ≤ 1 MiB. Drift outside this range almost certainly
        // indicates a typo (e.g. dropped multiplier → 128;
        // extra zero → 1.3M; bytes-vs-chars confusion → 1024).
        // 64 KiB lower bound ≈ 16k tokens — covers any
        // realistic prompt; 1 MiB upper bound holds the
        // security guarantee.
        assertTrue(
            MAX_TEXT_FIELD_LENGTH in 64 * 1024..1024 * 1024,
            "MAX_TEXT_FIELD_LENGTH MUST be in [64 KiB, 1 MiB] range; got: $MAX_TEXT_FIELD_LENGTH",
        )
    }

    @Test fun titleLengthIsInExpectedShortStringRange() {
        // Sanity magnitude pin: 64 ≤ MAX_TITLE_LENGTH ≤ 1024.
        // Outside this range almost certainly a typo (e.g.
        // bytes confusion → 256000; or character-only intent
        // forgotten → 16).
        assertTrue(
            MAX_TITLE_LENGTH in 64..1024,
            "MAX_TITLE_LENGTH MUST be in [64, 1024] range; got: $MAX_TITLE_LENGTH",
        )
    }

    @Test fun requireLengthAcceptsExactlyAtMaxTextFieldLength() {
        // Coupling pin: requireLength + MAX_TEXT_FIELD_LENGTH
        // form a contract — string of exactly 131072 chars
        // passes; 131073 fails. Drift in either constant or
        // the comparison operator (drift to `<` instead of
        // `<=`) surfaces here.
        requireLength("x".repeat(MAX_TEXT_FIELD_LENGTH), MAX_TEXT_FIELD_LENGTH, "field")
        // No assertion needed — function returns Unit on
        // pass, throws on fail.
    }

    @Test fun requireLengthAcceptsExactlyAtMaxTitleLength() {
        // Sister coupling pin for the title constant.
        requireLength("x".repeat(MAX_TITLE_LENGTH), MAX_TITLE_LENGTH, "title")
    }
}
