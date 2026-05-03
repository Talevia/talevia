package io.talevia.core.tool.builtin.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct tests for [slugifyId] / [isValidSourceNodeIdSlug] —
 * `core/tool/builtin/source/SourceIdSlug.kt`. The
 * deterministic-id-derivation pair backing
 * `source_node_action(action="add")` (slugifyId) +
 * `action="rename"` (isValidSourceNodeIdSlug). Cycle 166
 * audit: 48 LOC, 0 direct test refs (existing tests use
 * `slugifyId(...)` for fixture setup but never pin its
 * behavior; `isValidSourceNodeIdSlug` is internal and
 * never tested).
 *
 * Three correctness contracts pinned:
 *
 * 1. **`slugifyId` uses `Char.isLetterOrDigit()` (admits
 *    CJK / accented / Cyrillic), NOT ASCII-only.** This
 *    is the **opposite** of [slugifyProjectId] (cycle
 *    164's audit). Drift either way would shift the slug
 *    set: forcing ASCII would lose the CJK names that
 *    LLMs naturally generate for character / brand nodes;
 *    relaxing to all chars would admit punctuation that
 *    the lockfile stores literally and breaks lookup.
 *
 * 2. **Empty cleaned slug → `"node"` fallback (NOT null).**
 *    Per kdoc-adjacent contract: `sanitised.ifEmpty {
 *    "node" }`. So pure-symbol input "  ! @ #" with prefix
 *    "character" produces "character-node" — a usable id,
 *    never a degenerate "character-" or null. Drift to ""
 *    fallback would emit ids ending with `-` that fail
 *    `isValidSourceNodeIdSlug`.
 *
 * 3. **`isValidSourceNodeIdSlug` enforces ASCII-only +
 *    alpha-num-bookend.** The validator's character set is
 *    NARROWER than slugifyId's emit set (intentionally
 *    rejects CJK that slugifyId admits). Plus boundary:
 *    must not start / end with `-`. Drift would either
 *    let in pathological rename inputs (`-mei`, `mei-`,
 *    `Mei`, `mei_a`) OR reject legitimate slugs.
 */
class SourceIdSlugTest {

    // ── slugifyId: documented examples ──────────────────────────

    @Test fun documentedExampleProducesExpectedSlug() {
        // Marquee example from kdoc: "Mei" + "character" →
        // "character-mei".
        assertEquals("character-mei", slugifyId("Mei", "character"))
    }

    @Test fun lowercasingIsApplied() {
        assertEquals("scene-mei", slugifyId("MEI", "scene"))
        assertEquals("character-helloworld", slugifyId("HelloWorld", "character"))
    }

    @Test fun digitsArePreservedInSlug() {
        assertEquals("scene-shot1", slugifyId("Shot1", "scene"))
        assertEquals("scene-2026", slugifyId("2026", "scene"))
    }

    // ── slugifyId: separator collapse ──────────────────────────

    @Test fun runsOfNonAlphanumericsCollapseToSingleHyphen() {
        // Pin: any run of non-letterOrDigit collapses to one
        // hyphen.
        assertEquals("character-mei-li", slugifyId("Mei  Li", "character"))
        assertEquals("character-mei-li", slugifyId("Mei!@#Li", "character"))
        assertEquals("character-mei-li", slugifyId("Mei---Li", "character"))
    }

    @Test fun trailingNonAlphanumericsAreStripped() {
        // Pin: `trimEnd('-')`. Drift would emit
        // "character-mei-" which fails the validator.
        assertEquals("character-mei", slugifyId("Mei!!!", "character"))
        assertEquals("character-mei", slugifyId("Mei   ", "character"))
    }

    @Test fun leadingNonAlphanumericsDoNotProduceLeadingHyphen() {
        // Pin: `lastWasSep = true` initially → leading
        // separators silently swallowed.
        assertEquals("character-mei", slugifyId("   Mei", "character"))
        assertEquals("character-mei", slugifyId("!!!Mei", "character"))
    }

    // ── slugifyId: CJK / accented are admitted ─────────────────

    @Test fun cjkCharactersAreAdmittedToSlug() {
        // The marquee divergence-from-ProjectIdSlug pin:
        // `Char.isLetterOrDigit()` returns true for CJK, so
        // these chars survive into the slug. Drift to ASCII-
        // only filtering would silently strip CJK names —
        // breaking the documented "LLM dispatches
        // source_node_action with the same id UI computes"
        // round-trip. "Mei 美" → space becomes hyphen, both
        // alphanumeric runs preserved.
        assertEquals("character-mei-美", slugifyId("Mei 美", "character"))
        // Pure CJK falls through; the kdoc mentions hand-
        // authored CJK ids like "美 (Mei)" — slugifyId emits
        // them as-is.
        assertEquals("character-美", slugifyId("美", "character"))
        assertEquals("scene-项目", slugifyId("项目", "scene"))
    }

    @Test fun accentedLatinCharactersAreAdmitted() {
        // Pin: `é`, `ñ`, `ü` are isLetterOrDigit → survive.
        // Different from ProjectIdSlug which strips them.
        assertEquals("scene-café", slugifyId("Café", "scene"))
        assertEquals("character-jalapeño", slugifyId("Jalapeño", "character"))
    }

    // ── slugifyId: empty fallback ──────────────────────────────

    @Test fun emptyInputFallsBackToNode() {
        // Marquee empty-fallback pin: empty string →
        // "$prefix-node" (NOT "$prefix-" or null).
        assertEquals("character-node", slugifyId("", "character"))
        assertEquals("scene-node", slugifyId("", "scene"))
    }

    @Test fun pureWhitespaceInputFallsBackToNode() {
        // Pin: pure whitespace produces empty `sanitised`
        // → falls back to "node".
        assertEquals("character-node", slugifyId("   ", "character"))
        assertEquals("character-node", slugifyId("\t\n  \t", "character"))
    }

    @Test fun pureSymbolInputFallsBackToNode() {
        // Pin: symbol-only input also produces empty
        // sanitised → fallback. Never produce "$prefix-"
        // (which would fail the validator).
        assertEquals("character-node", slugifyId("***", "character"))
        assertEquals("scene-node", slugifyId("!@#$%^&*()", "scene"))
    }

    // ── slugifyId: structural pins ────────────────────────────

    @Test fun resultAlwaysContainsExactlyOnePrefixHyphen() {
        // Pin: format is exactly "$prefix-$core". Drift to
        // double-prefix or no-prefix would break LLM's
        // ability to predict ids.
        val cases = mapOf(
            "Mei" to "character",
            "Hello World" to "scene",
            "" to "shot",
            "***" to "brand",
        )
        for ((name, prefix) in cases) {
            val slug = slugifyId(name, prefix)
            assertTrue(
                slug.startsWith("$prefix-"),
                "slug for ($name, $prefix) = '$slug' must start with '$prefix-'",
            )
            // Core (after prefix-) is non-empty.
            val core = slug.removePrefix("$prefix-")
            assertTrue(
                core.isNotEmpty(),
                "core after prefix is non-empty; got slug '$slug'",
            )
        }
    }

    // ── isValidSourceNodeIdSlug: happy path ────────────────────

    @Test fun validSlugsAreAccepted() {
        // Pin: all-ASCII a-z / 0-9 / `-`, alpha-num-
        // bookended.
        assertTrue(isValidSourceNodeIdSlug("mei"))
        assertTrue(isValidSourceNodeIdSlug("character-mei"))
        assertTrue(isValidSourceNodeIdSlug("shot-1"))
        assertTrue(isValidSourceNodeIdSlug("scene-a"))
        assertTrue(isValidSourceNodeIdSlug("brand-talevia"))
        assertTrue(isValidSourceNodeIdSlug("a"))
        assertTrue(isValidSourceNodeIdSlug("a1"))
        assertTrue(isValidSourceNodeIdSlug("1a"))
    }

    // ── isValidSourceNodeIdSlug: empty / boundary ─────────────

    @Test fun emptyOrBlankIsRejected() {
        // Marquee empty-rejection pin.
        assertFalse(isValidSourceNodeIdSlug(""))
        assertFalse(isValidSourceNodeIdSlug("   "))
        assertFalse(isValidSourceNodeIdSlug("\t"))
    }

    @Test fun leadingOrTrailingHyphenIsRejected() {
        // Marquee bookend-must-be-alphanumeric pin. Drift
        // would let slugifyId's accidental "-prefix"
        // outputs through (though slugifyId itself trims
        // these — defense in depth).
        assertFalse(isValidSourceNodeIdSlug("-mei"))
        assertFalse(isValidSourceNodeIdSlug("mei-"))
        assertFalse(isValidSourceNodeIdSlug("-mei-"))
        // Single hyphen alone is starts-with-and-ends-with
        // → rejected.
        assertFalse(isValidSourceNodeIdSlug("-"))
    }

    // ── isValidSourceNodeIdSlug: char-set ─────────────────────

    @Test fun uppercaseLettersAreRejected() {
        // Pin: ASCII a-z only — uppercase rejected so a
        // rename to "Mei" or "Character-Mei" fails before
        // mutating.
        assertFalse(isValidSourceNodeIdSlug("Mei"))
        assertFalse(isValidSourceNodeIdSlug("character-Mei"))
        assertFalse(isValidSourceNodeIdSlug("MEI"))
    }

    @Test fun underscoresSpacesAndOtherSeparatorsRejected() {
        // Pin: only `-` is allowed as separator. Underscore,
        // space, dot, slash all rejected.
        assertFalse(isValidSourceNodeIdSlug("character_mei"))
        assertFalse(isValidSourceNodeIdSlug("character mei"))
        assertFalse(isValidSourceNodeIdSlug("character.mei"))
        assertFalse(isValidSourceNodeIdSlug("character/mei"))
        assertFalse(isValidSourceNodeIdSlug("character:mei"))
    }

    @Test fun cjkAndAccentedCharsAreRejected() {
        // Marquee "validator narrower than emitter" pin:
        // slugifyId admits CJK / accented (because
        // `isLetterOrDigit`) but isValidSourceNodeIdSlug
        // rejects them (because explicit a-z range only).
        // This means a slug minted by slugifyId from a CJK
        // name would FAIL the validator on rename — that
        // tension is intentional but worth pinning so a
        // future "harmonize" refactor doesn't silently
        // change validator behavior.
        assertFalse(isValidSourceNodeIdSlug("character-美"))
        assertFalse(isValidSourceNodeIdSlug("scene-café"))
        assertFalse(isValidSourceNodeIdSlug("проект"))
    }

    // ── slugifyId / isValidSourceNodeIdSlug interaction ───────

    @Test fun asciiSlugifyIdResultRoundTripsThroughValidator() {
        // Pin: an ASCII-only name fed through slugifyId
        // produces a slug that DOES pass the validator.
        // This is the documented round-trip for hand-
        // authored ids the LLM might re-generate.
        val cases = listOf(
            "Mei" to "character",
            "Hello World" to "scene",
            "" to "shot", // → "shot-node", still valid.
            "***" to "brand", // → "brand-node", valid.
            "Talevia Brand 2026" to "brand",
        )
        for ((name, prefix) in cases) {
            val slug = slugifyId(name, prefix)
            assertTrue(
                isValidSourceNodeIdSlug(slug),
                "slugifyId('$name', '$prefix') = '$slug' must pass validator",
            )
        }
    }

    @Test fun cjkSlugifyIdResultDoesNotRoundTripThroughValidator() {
        // Negative interaction pin: CJK input produces a
        // slug containing CJK chars that the validator
        // rejects. This is the intentional asymmetry —
        // pinning it so the tension is visible.
        val slug = slugifyId("美", "character")
        assertFalse(
            isValidSourceNodeIdSlug(slug),
            "slugifyId('美', 'character') = '$slug' contains CJK and FAILS validator (intentional asymmetry)",
        )
    }
}
