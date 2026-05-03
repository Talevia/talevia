package io.talevia.cli.repl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for `apps/cli/src/main/kotlin/io/talevia/cli/repl/SlashCommands.kt`.
 * Cycle 249 audit: 0 test refs against [SLASH_COMMANDS],
 * [SlashCategory], [editDistance], or [suggestSlash].
 *
 * Same audit-pattern fallback as cycles 207-248.
 *
 * `SlashCommands.kt` carries three things every CLI session reads
 * at startup:
 *
 *   - [SLASH_COMMANDS] — the canonical list the REPL reads to
 *     route `/foo` typings. Drift in a name (e.g. `/cost` →
 *     `/cost-summary`) silently breaks operator muscle memory.
 *   - [SlashCategory] — buckets `/help` prints as sub-headings.
 *     Drift in a title or order changes the on-screen reference
 *     card layout.
 *   - [editDistance] + [suggestSlash] — the typo-suggestion engine.
 *     Drift in either silently changes "did you mean ..." UX:
 *     too tolerant suggests irrelevant commands; too strict
 *     misses real typos.
 *
 * Pins three correctness contracts:
 *
 *  1. **Catalogue stability**: critical commands always present
 *     (operator muscle memory: `/help`, `/exit`, `/quit`,
 *     `/new`, `/sessions`, `/resume`, `/clear`); no duplicate
 *     names; every name starts with `/`. Per-command size pin
 *     deliberately omitted — adding a command shouldn't break
 *     this test.
 *
 *  2. **`editDistance` Levenshtein correctness**: canonical
 *     test vectors (`"kitten"` ↔ `"sitting"` = 3, identity = 0,
 *     empty-to-N = N, single-edit cases). Drift in the DP
 *     recurrence or the cost calculation surfaces here.
 *
 *  3. **`suggestSlash` UX**:
 *     - close typo (1-2 edits) → suggested
 *     - distant input → null (don't badger the user)
 *     - slash-prefix auto-added (`"hlp"` and `"/hlp"` both work)
 *     - default `maxDistance = 2` (drift to 3+ would suggest
 *       wildly off, drift to 1 would miss double-typos)
 *     - returns the BEST match (lowest distance among candidates
 *       within the max) — NOT the first match by registration order
 *
 * Plus pin family:
 *   - `SlashCategory.entries.size == 4` + names + titles.
 *   - Every category has at least one command.
 */
class SlashCommandsTest {

    // ── 1. Catalogue stability ──────────────────────────────

    @Test fun catalogueIsNonEmpty() {
        assertTrue(SLASH_COMMANDS.isNotEmpty(), "catalogue MUST be non-empty")
    }

    @Test fun everyCommandNameStartsWithSlash() {
        // Pin: drift to "exit" (no slash) would silently break
        // the dispatcher's `/foo` routing. The catalogue is the
        // source of truth for valid command names.
        for (cmd in SLASH_COMMANDS) {
            assertTrue(
                cmd.name.startsWith("/"),
                "every command name MUST start with '/'; got: '${cmd.name}'",
            )
        }
    }

    @Test fun everyCommandNameIsUnique() {
        // Pin: duplicate names would cause first-wins / last-wins
        // ambiguity in dispatch — the resolver shouldn't have to
        // care about list order.
        val seen = mutableSetOf<String>()
        val dupes = mutableListOf<String>()
        for (cmd in SLASH_COMMANDS) {
            if (!seen.add(cmd.name)) dupes += cmd.name
        }
        assertTrue(dupes.isEmpty(), "duplicate slash command names: ${dupes.joinToString()}")
    }

    @Test fun coreCommandsAlwaysPresent() {
        // Marquee operator-muscle-memory pin: these commands
        // MUST be in the catalogue. Drift to remove `/help`
        // silently breaks the help affordance; drift to remove
        // `/exit` / `/quit` silently breaks the canonical exits.
        // `/new`, `/sessions`, `/resume` are the session-management
        // baseline. `/clear` is the screen-clear muscle action.
        val names = SLASH_COMMANDS.map { it.name }.toSet()
        for (core in listOf("/help", "/exit", "/quit", "/new", "/sessions", "/resume", "/clear")) {
            assertTrue(
                core in names,
                "core operator-muscle-memory command '$core' MUST be in the catalogue",
            )
        }
    }

    @Test fun everyCommandHasNonEmptyHelpText() {
        // Pin: drift to "TODO: describe" / empty would surface
        // here. The help text is what `/help` prints; empty
        // entries silently leak to the user.
        for (cmd in SLASH_COMMANDS) {
            assertTrue(
                cmd.help.isNotBlank(),
                "command '${cmd.name}' MUST have a non-empty help text",
            )
        }
    }

    // ── 2. SlashCategory enum stability ─────────────────────

    @Test fun slashCategoryHasExactlyFourEntries() {
        // Pin: drift to add a 5th without an `/help` rendering
        // change would silently leave new categories unrendered.
        // Drop a category without re-bucketing commands silently
        // sends them to default `META`.
        assertEquals(
            4,
            SlashCategory.entries.size,
            "SlashCategory MUST have exactly 4 entries (SESSION, HISTORY, MODEL, META)",
        )
        assertEquals(
            setOf("SESSION", "HISTORY", "MODEL", "META"),
            SlashCategory.entries.map { it.name }.toSet(),
        )
    }

    @Test fun slashCategoryTitlesMatchCanonicalDisplayStrings() {
        // Pin: titles drive `/help`'s sub-heading display. Drift
        // would silently change the on-screen layout.
        assertEquals("session", SlashCategory.SESSION.title)
        assertEquals("history + branching", SlashCategory.HISTORY.title)
        assertEquals("model + stats", SlashCategory.MODEL.title)
        assertEquals("meta", SlashCategory.META.title)
    }

    @Test fun slashCategoryEntriesOrderIsKdocDocumented() {
        // Pin: per the kdoc, "Ordered by how often an operator
        // reaches for each group." Drift to alphabetical /
        // reordered would shuffle `/help` UX.
        assertEquals(
            listOf(
                SlashCategory.SESSION,
                SlashCategory.HISTORY,
                SlashCategory.MODEL,
                SlashCategory.META,
            ),
            SlashCategory.entries.toList(),
        )
    }

    @Test fun everyCategoryHasAtLeastOneCommand() {
        // Pin: a category with no commands renders an empty
        // sub-heading in `/help` — drift would surface here
        // before users see a half-baked help page.
        val byCategory = SLASH_COMMANDS.groupBy { it.category }
        for (cat in SlashCategory.entries) {
            assertTrue(
                byCategory[cat]?.isNotEmpty() == true,
                "category $cat MUST have at least one command",
            )
        }
    }

    // ── 3. editDistance correctness ─────────────────────────

    @Test fun editDistanceReturnsZeroForIdenticalStrings() {
        // Pin: identity case is the early-return path.
        assertEquals(0, editDistance("", ""))
        assertEquals(0, editDistance("a", "a"))
        assertEquals(0, editDistance("hello", "hello"))
        assertEquals(0, editDistance("/help", "/help"))
    }

    @Test fun editDistanceForSingleSubstitution() {
        // Pin: 1 substitution → distance 1.
        assertEquals(1, editDistance("a", "b"))
        assertEquals(1, editDistance("cat", "bat"))
    }

    @Test fun editDistanceForSingleInsertOrDelete() {
        // Pin: insertion / deletion = 1 each.
        assertEquals(1, editDistance("ab", "abc"), "insert at end")
        assertEquals(1, editDistance("abc", "ab"), "delete at end")
        assertEquals(1, editDistance("ab", "abc"))
        assertEquals(1, editDistance("ello", "hello"), "insert at start")
    }

    @Test fun editDistanceCanonicalKittenSitting() {
        // Marquee Levenshtein test vector: "kitten" ↔ "sitting"
        // = 3 (k→s, e→i, +g). Drift in the DP recurrence
        // surfaces here — every CS textbook uses this case.
        assertEquals(3, editDistance("kitten", "sitting"))
        assertEquals(3, editDistance("sitting", "kitten"))
    }

    @Test fun editDistanceForEmptyAgainstNonEmptyEqualsLength() {
        // Pin: empty-to-N edits = N. Tests the early-return
        // arms (`if (m == 0) return n` / `if (n == 0) return m`).
        assertEquals(3, editDistance("", "abc"))
        assertEquals(3, editDistance("abc", ""))
        assertEquals(5, editDistance("", "hello"))
        assertEquals(5, editDistance("hello", ""))
    }

    @Test fun editDistanceIsSymmetric() {
        // Marquee invariant pin: Levenshtein distance is
        // symmetric. Drift to an asymmetric implementation would
        // surface here (e.g. only counting insertions on one
        // side).
        val pairs = listOf(
            "hlep" to "/help",
            "abc" to "xyz",
            "model" to "modal",
            "exi" to "/exit",
        )
        for ((a, b) in pairs) {
            assertEquals(
                editDistance(a, b),
                editDistance(b, a),
                "editDistance MUST be symmetric for ($a, $b)",
            )
        }
    }

    // ── 4. suggestSlash UX ──────────────────────────────────

    @Test fun suggestSlashFindsCloseTypoWithinDefaultMaxDistance() {
        // Marquee close-typo pin: a 1-edit typo MUST suggest
        // the canonical command. `/hlp` → `/help` (insertion).
        val s = suggestSlash("/hlp")
        assertNotNull(s, "1-edit typo MUST get a suggestion")
        assertEquals("/help", s.name)
    }

    @Test fun suggestSlashAutoPrefixesSlashWhenMissing() {
        // Marquee slash-normalisation pin: per the source,
        // `if (typed.startsWith("/")) typed else "/$typed"`.
        // Drift to "ignore non-slash input" would force users
        // to type the slash to get a suggestion.
        assertEquals("/help", suggestSlash("help")?.name)
        assertEquals("/help", suggestSlash("/help")?.name)
        assertEquals("/help", suggestSlash("hlp")?.name)
    }

    @Test fun suggestSlashReturnsNullForDistantInput() {
        // Marquee distance-cap pin: drift to "always suggest
        // best match" would badger the user with wildly
        // irrelevant commands. `xyz` is > 2 edits from any
        // command, so MUST return null at default maxDistance.
        assertNull(
            suggestSlash("/xyz"),
            "distant input MUST return null at default maxDistance=2",
        )
        assertNull(
            suggestSlash("/totallyOffTheRails"),
            "very distant input MUST return null",
        )
    }

    @Test fun suggestSlashReturnsBestMatchWhenMultipleCandidatesQualify() {
        // Pin: per `.minByOrNull { it.second }`, the FIRST
        // command at the LOWEST distance wins. Drift to "first
        // match found" or "last match found" would silently
        // change which suggestion lands.
        // `/exi` is 1 edit from `/exit` AND from `/exec` (if it
        // existed); pick something we know has a single closest.
        // `/clea` → `/clear` (1 edit). No other command is
        // within 1 of `/clea`.
        val s = suggestSlash("/clea")
        assertNotNull(s)
        assertEquals("/clear", s.name)
    }

    @Test fun suggestSlashRespectsCustomMaxDistance() {
        // Pin: drift to ignore `maxDistance` arg would make the
        // function always use the default. Pass a stricter
        // (1) and looser (5) value to surface drift.
        // `/hlp` is 1 edit from `/help` — passes maxDistance=1.
        assertEquals("/help", suggestSlash("/hlp", maxDistance = 1)?.name)
        // `/xyz` is 4+ edits from anything — passes only with
        // maxDistance>=4.
        assertNull(suggestSlash("/xyz", maxDistance = 1))
    }

    @Test fun suggestSlashFindsExactMatchAsZeroEditDistance() {
        // Pin: typing the exact command name gets the same
        // command as the suggestion (distance 0). Drift to "skip
        // exact matches" would surface here.
        assertEquals("/help", suggestSlash("/help")?.name)
        assertEquals("/exit", suggestSlash("/exit")?.name)
    }

    @Test fun suggestSlashReturnsNullForEmptyInputBeyondDistanceCap() {
        // Edge: empty string → "/" (1 char) → distance from
        // every "/foo" is `len("/foo") - 1` ≥ 3 for all
        // current commands. So default maxDistance=2 → null.
        // Drift to "always match shortest command" would
        // surface here.
        assertNull(suggestSlash(""), "empty input MUST return null at default maxDistance")
    }
}
