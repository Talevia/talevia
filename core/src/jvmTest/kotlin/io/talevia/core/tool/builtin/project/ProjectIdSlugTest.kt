package io.talevia.core.tool.builtin.project

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [slugifyProjectId] / [resolveDefaultHomeProjectId] —
 * `core/tool/builtin/project/ProjectIdSlug.kt`. Title-to-id
 * derivation for `project_action(kind="lifecycle",
 * args={action="create"})`. Cycle 164 audit: 46 LOC, 0
 * direct test refs (the functions are exercised through
 * full-tool integration tests but their own contracts —
 * ASCII-only filtering, separator collapse, CJK/symbol →
 * null, explicit-id precedence, UUID fallback — were never
 * pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **ASCII-alphanumeric-only filtering, NOT
 *    `Char.isLetterOrDigit()`.** Per kdoc: "Restricted to
 *    ASCII alphanumerics on purpose: the resulting id ends
 *    up as a default directory name in
 *    [FileProjectStore], which validates against
 *    `[A-Za-z0-9_.-]{1,200}`. `Char.isLetterOrDigit()` is
 *    true for CJK / Cyrillic / accented letters, which
 *    would slip past slug sanitisation and then crash at
 *    the bundle-path step." Drift to `isLetterOrDigit()`
 *    would silently produce slugs the FileProjectStore
 *    then rejects with "invalid path."
 *
 * 2. **Empty / unslug-able input → null (NOT empty
 *    string).** CJK-only titles, all-symbol titles ("***"),
 *    pure whitespace must return null so callers fall
 *    through to UUID. Drift to "" would create projects
 *    with the literal id `proj-`.
 *
 * 3. **`resolveDefaultHomeProjectId` precedence is
 *    explicit > slug > UUID.** Explicit non-blank wins
 *    unconditionally; explicit blank/null falls through to
 *    slug; slug-fail falls through to UUID. Drift to "slug
 *    > explicit" would silently rename user-supplied ids.
 */
class ProjectIdSlugTest {

    // ── happy path ─────────────────────────────────────────────

    @Test fun documentedExampleProducesExpectedSlug() {
        // The kdoc's marquee example: "Graduation Vlog 2026"
        // → `proj-graduation-vlog-2026`.
        assertEquals(
            "proj-graduation-vlog-2026",
            slugifyProjectId("Graduation Vlog 2026"),
        )
    }

    @Test fun lowercasingIsAppliedBeforeFiltering() {
        // Pin: input is lowercased before alphanumeric check.
        // Drift to "filter then lowercase" wouldn't change
        // behavior here (a-z range is identical to A-Z when
        // lowered) but the kdoc documents the order.
        assertEquals("proj-hello", slugifyProjectId("HELLO"))
        // CamelCase has no separator → lowercases to "helloworld"
        // (NOT word-boundary-split). Pin the actual behavior so a
        // future "smart split on case-change" refactor would surface.
        assertEquals("proj-helloworld", slugifyProjectId("HelloWorld"))
    }

    @Test fun digitsArePreservedInSlug() {
        assertEquals("proj-2026", slugifyProjectId("2026"))
        assertEquals("proj-v2", slugifyProjectId("V2"))
    }

    // ── separator collapse ─────────────────────────────────────

    @Test fun runsOfNonAlphanumericsCollapseToSingleHyphen() {
        // Marquee separator-collapse pin: any run of non-
        // alphanumerics (spaces, symbols, punctuation,
        // existing hyphens, mixed) collapses to exactly one
        // hyphen. Drift to "preserve count" would emit
        // "proj-a---b" for "a   b".
        assertEquals("proj-a-b", slugifyProjectId("a   b"))
        assertEquals("proj-a-b", slugifyProjectId("a!@#b"))
        assertEquals("proj-a-b", slugifyProjectId("a!@# b"))
        assertEquals("proj-a-b", slugifyProjectId("a---b"))
    }

    @Test fun trailingNonAlphanumericsAreStripped() {
        // Pin: `trimEnd('-')`. Drift to keeping the trailing
        // hyphen would emit "proj-graduation-".
        assertEquals("proj-graduation", slugifyProjectId("graduation   "))
        assertEquals("proj-graduation", slugifyProjectId("graduation!!!"))
        assertEquals("proj-graduation", slugifyProjectId("graduation---"))
    }

    @Test fun leadingNonAlphanumericsDoNotProduceLeadingHyphen() {
        // Pin: `lastWasSep = true` initially → the leading
        // separator run is silently swallowed (it doesn't
        // emit a hyphen). Drift to "always emit hyphen" would
        // produce "proj--graduation".
        assertEquals("proj-graduation", slugifyProjectId("   graduation"))
        assertEquals("proj-graduation", slugifyProjectId("!!!graduation"))
    }

    // ── unslug-able inputs → null ──────────────────────────────

    @Test fun emptyStringInputReturnsNull() {
        // Marquee null-on-empty pin: empty input → null, NOT
        // "proj-". Drift to "" would create projects literally
        // named "proj-".
        assertNull(slugifyProjectId(""))
    }

    @Test fun pureWhitespaceInputReturnsNull() {
        assertNull(slugifyProjectId("   "))
        assertNull(slugifyProjectId("\t\n  \t"))
    }

    @Test fun pureSymbolInputReturnsNull() {
        // Pin: all-symbol title ("***") → null, NOT "proj-".
        // Per kdoc: "all-symbol titles like `***`" are the
        // unslug-able case the function handles by falling
        // through to UUID.
        assertNull(slugifyProjectId("***"))
        assertNull(slugifyProjectId("!@#$%^&*()"))
        assertNull(slugifyProjectId("---"))
    }

    @Test fun pureCjkInputReturnsNull() {
        // The marquee ASCII-only pin: CJK characters slip
        // past `Char.isLetterOrDigit()` but are explicitly
        // rejected here because the FileProjectStore's
        // `[A-Za-z0-9_.-]` regex would reject them
        // downstream. Drift to `isLetterOrDigit()` would
        // produce a slug the bundle-path step then crashes
        // on.
        assertNull(slugifyProjectId("项目"), "CJK only → null")
        assertNull(slugifyProjectId("テスト"), "Japanese only → null")
        assertNull(slugifyProjectId("проект"), "Cyrillic only → null")
    }

    @Test fun pureAccentedLatinInputReturnsNull() {
        // Pin: pure accented letters (no plain ASCII a-z)
        // → null. `é`, `ñ`, `ü` are letterOrDigit but NOT
        // a-z. Drift would crash bundle-path.
        assertNull(slugifyProjectId("éñü"))
        assertNull(slugifyProjectId("ÄÖÜ"))
    }

    // ── mixed CJK / accented + ASCII ──────────────────────────

    @Test fun mixedCjkAndAsciiKeepsOnlyAscii() {
        // Pin: ASCII chars survive, CJK chars trigger the
        // separator path. "Café 2026" → "proj-caf-2026"
        // (the `é` is treated as a non-alphanumeric and
        // collapses with the trailing space into one
        // hyphen).
        assertEquals("proj-caf-2026", slugifyProjectId("Café 2026"))
        assertEquals("proj-2026", slugifyProjectId("项目 2026"))
        assertEquals("proj-meeting-notes", slugifyProjectId("会议 Meeting Notes"))
    }

    @Test fun emojiInputBehavesAsSeparator() {
        // Pin: emoji are non-alphanumeric → trigger separator
        // path. Drift to "preserve emoji" would produce slugs
        // FileProjectStore rejects.
        assertEquals("proj-vlog", slugifyProjectId("🎬 Vlog"))
        assertEquals("proj-2026-recap", slugifyProjectId("2026 🎉 Recap"))
    }

    // ── existing hyphens / underscores / dots ────────────────

    @Test fun existingHyphensCollapseLikeAnyOtherSeparator() {
        // Pin: existing hyphens are non-alphanumeric ASCII
        // (NOT in a-z / 0-9), so they trigger the separator
        // path. Drift to "preserve hyphens" would skip the
        // collapse logic for already-slugged inputs.
        assertEquals("proj-graduation-vlog", slugifyProjectId("graduation-vlog"))
    }

    @Test fun underscoreAndDotAreAlsoSeparators() {
        // Pin: underscore / dot are not in `a..z` or `0..9`
        // → treated as separators. Even though
        // FileProjectStore's regex allows them, the slug
        // output uses hyphens uniformly.
        assertEquals("proj-graduation-vlog", slugifyProjectId("graduation_vlog"))
        assertEquals("proj-vlog-2026", slugifyProjectId("vlog.2026"))
    }

    // ── result format ────────────────────────────────────────

    @Test fun nonNullResultAlwaysStartsWithProjPrefix() {
        // Pin: every non-null return starts with the
        // "proj-" prefix. Drift to "no prefix" would
        // collide with user-supplied ids.
        val titles = listOf("a", "abc", "Hello World", "2026", "v1")
        for (t in titles) {
            val slug = slugifyProjectId(t)
            assertNotNull(slug)
            assertTrue(
                slug.startsWith("proj-"),
                "slug for '$t' must start with 'proj-'; got '$slug'",
            )
        }
    }

    @Test fun nonNullResultMatchesFileProjectStoreRegex() {
        // Pin: every non-null slug satisfies
        // `[A-Za-z0-9_.-]{1,200}` (the FileProjectStore
        // validator). This is the function's whole reason
        // for existing — drift would surface as "slug-then-
        // crash" at bundle creation.
        val regex = Regex("[A-Za-z0-9_.-]{1,200}")
        val titles = listOf(
            "a",
            "Graduation Vlog 2026",
            "Café 2026",
            "🎬 Vlog",
            "项目 Meeting",
            "***hello***",
        )
        for (t in titles) {
            val slug = slugifyProjectId(t) ?: continue
            assertTrue(
                regex.matches(slug),
                "slug for '$t' = '$slug' must match FileProjectStore regex",
            )
        }
    }

    // ── resolveDefaultHomeProjectId precedence ────────────────

    @Test fun explicitIdWinsOverSlugableTitle() {
        // The marquee precedence pin: explicit id (non-
        // blank) wins unconditionally over a slug-able
        // title. Drift would silently rename the user's id.
        assertEquals(
            "user-chosen-id",
            resolveDefaultHomeProjectId(
                explicitId = "user-chosen-id",
                title = "Graduation Vlog 2026",
            ),
        )
    }

    @Test fun explicitIdEvenWhenWeirdWinsOverSlug() {
        // Pin: explicit id is returned VERBATIM. The slug
        // function's ASCII-only restriction does NOT apply
        // to caller-supplied ids — that's the
        // FileProjectStore's job to validate, not this
        // resolver's.
        assertEquals(
            "literal-cjk-项目",
            resolveDefaultHomeProjectId(
                explicitId = "literal-cjk-项目",
                title = "ignored",
            ),
        )
    }

    @Test fun blankExplicitIdFallsThroughToSlug() {
        // Pin: `takeIf { it.isNotBlank() }` — empty / blank
        // explicit ids fall through. Drift to "trim and
        // accept blank" would produce projects with empty
        // id.
        assertEquals(
            "proj-graduation",
            resolveDefaultHomeProjectId(
                explicitId = "",
                title = "graduation",
            ),
        )
        assertEquals(
            "proj-graduation",
            resolveDefaultHomeProjectId(
                explicitId = "   ",
                title = "graduation",
            ),
        )
    }

    @Test fun nullExplicitIdFallsThroughToSlug() {
        assertEquals(
            "proj-graduation",
            resolveDefaultHomeProjectId(
                explicitId = null,
                title = "Graduation",
            ),
        )
    }

    @Test fun unslugableTitleWithNullExplicitFallsThroughToUuid() {
        // The marquee UUID-fallback pin: when both the
        // explicit id is null/blank AND the title is
        // unslug-able, the function mints a UUID. Pin the
        // shape (UUID format) without pinning a literal
        // value (UUIDs are random by definition).
        val result = resolveDefaultHomeProjectId(
            explicitId = null,
            title = "项目",
        )
        // UUID format: 8-4-4-4-12 hex digits.
        val uuidRegex = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        assertTrue(
            uuidRegex.matches(result),
            "expected UUID format, got: $result",
        )
    }

    @Test fun blankExplicitWithUnslugableTitleFallsThroughToUuid() {
        // Pin: combined fallback path — blank explicit AND
        // unslug-able title → UUID. Drift to throwing or
        // returning empty would crash the create flow on
        // every CJK-only title that didn't supply an id.
        val result = resolveDefaultHomeProjectId(
            explicitId = "  ",
            title = "***",
        )
        val uuidRegex = Regex(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
        assertTrue(uuidRegex.matches(result), "expected UUID, got: $result")
    }

    @Test fun consecutiveCallsToUuidFallbackProduceDistinctIds() {
        // Pin: UUID fallback is genuinely random (not a
        // shared singleton). Drift to a constant fallback
        // would silently collide every default-home create
        // for unslug-able titles.
        val a = resolveDefaultHomeProjectId(explicitId = null, title = "项目")
        val b = resolveDefaultHomeProjectId(explicitId = null, title = "项目")
        assertTrue(a != b, "consecutive UUIDs differ; got '$a' and '$b'")
    }
}
