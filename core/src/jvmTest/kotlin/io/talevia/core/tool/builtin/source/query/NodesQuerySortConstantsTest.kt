package io.talevia.core.tool.builtin.source.query

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for the sort-key constants in
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/source/query/NodesQuery.kt:142-145`.
 * Cycle 264 audit: 0 test refs against [SORT_BY_KIND],
 * [SORT_BY_REVISION_DESC], or [VALID_SORT_KEYS]. (`SORT_BY_ID`
 * has indirect coverage via integration tests but its literal
 * value isn't pinned alongside the canonical sort-key set.)
 *
 * Same audit-pattern fallback as cycles 207-263.
 *
 * The three `SORT_BY_*` constants are the wire strings the
 * agent emits via `source_query(sortBy=...)`. The dispatcher
 * matches the lowercased input against [VALID_SORT_KEYS] —
 * drift in any constant value silently invalidates that
 * sortBy:
 *
 *  - `SORT_BY_ID = "id"` (default)
 *  - `SORT_BY_KIND = "kind"`
 *  - `SORT_BY_REVISION_DESC = "revision-desc"` (load-bearing
 *    hyphen-separator drift to underscore would silently
 *    reject the agent's natural form)
 *
 * Cycle 244's [SourceQueryToolSchemaTest] pins the
 * LLM-visible description (`"id (default) | kind |
 * revision-desc"`) but NOT the constant values themselves —
 * if the description string drifts to match a new constant
 * value, the description-test would still pass while
 * dispatch silently breaks.
 *
 * Pins three correctness contracts:
 *
 *  1. **Per-constant string value pin** for each of the 3
 *     SORT_BY_* keys. Drift to camelCase / different
 *     separator surfaces here.
 *
 *  2. **`VALID_SORT_KEYS` set** is exactly the 3 constants
 *     (no more, no less). Drift to add a 4th unsupported
 *     sort key without dispatcher logic update would surface
 *     here; drift to drop one would silently make it invalid
 *     even though the LLM still emits it.
 *
 *  3. **All values are lowercase** — per
 *     `NodesQuery.kt:25` the input is `.lowercase()`-d
 *     before the `in VALID_SORT_KEYS` check. Drift to
 *     mixed-case constants would silently reject the
 *     normalized input.
 */
class NodesQuerySortConstantsTest {

    @Test fun sortByIdIsLiteralLowercaseId() {
        assertEquals(
            "id",
            SORT_BY_ID,
            "SORT_BY_ID MUST be exactly 'id' (the canonical default sort key)",
        )
    }

    @Test fun sortByKindIsLiteralLowercaseKind() {
        assertEquals(
            "kind",
            SORT_BY_KIND,
            "SORT_BY_KIND MUST be exactly 'kind'",
        )
    }

    @Test fun sortByRevisionDescUsesHyphenSeparator() {
        // Marquee separator pin: `revision-desc` uses HYPHEN,
        // not underscore (`revision_desc`) or camelCase
        // (`revisionDesc`). The agent emits the form per
        // SourceQueryTool's description string; drift in the
        // constant would silently reject the agent's natural
        // input even though the description still says
        // `revision-desc`.
        assertEquals(
            "revision-desc",
            SORT_BY_REVISION_DESC,
            "SORT_BY_REVISION_DESC MUST use HYPHEN separator (NOT underscore / camelCase)",
        )
    }

    // ── 2. VALID_SORT_KEYS membership ───────────────────────

    @Test fun validSortKeysHasExactlyThreeEntries() {
        // Marquee count pin: the 3 supported sort keys form
        // exactly the valid set. Drift to add a 4th
        // unsupported key without dispatcher logic update
        // would silently let the agent emit a key that
        // matches the validation but crashes at the `when`
        // arm with "unreachable".
        assertEquals(
            3,
            VALID_SORT_KEYS.size,
            "VALID_SORT_KEYS MUST have exactly 3 entries (SORT_BY_ID + SORT_BY_KIND + SORT_BY_REVISION_DESC)",
        )
    }

    @Test fun validSortKeysContainsCanonicalThreeKeys() {
        assertEquals(
            setOf("id", "kind", "revision-desc"),
            VALID_SORT_KEYS,
            "VALID_SORT_KEYS MUST be exactly {id, kind, revision-desc}",
        )
    }

    @Test fun validSortKeysContainsAllThreeConstants() {
        // Sister pin: each constant individually appears in
        // the set. Drift to omit a constant from the set
        // would silently invalidate that sort key even though
        // the constant still exists.
        assertTrue(
            SORT_BY_ID in VALID_SORT_KEYS,
            "SORT_BY_ID MUST be in VALID_SORT_KEYS",
        )
        assertTrue(
            SORT_BY_KIND in VALID_SORT_KEYS,
            "SORT_BY_KIND MUST be in VALID_SORT_KEYS",
        )
        assertTrue(
            SORT_BY_REVISION_DESC in VALID_SORT_KEYS,
            "SORT_BY_REVISION_DESC MUST be in VALID_SORT_KEYS",
        )
    }

    // ── 3. Lowercase invariant ──────────────────────────────

    @Test fun allSortKeysAreLowercase() {
        // Marquee normalisation pin: per `NodesQuery.kt:25`
        // input is `.lowercase()`-d before the membership
        // check. Drift to mixed-case constants (e.g.
        // `"ID"` / `"Kind"`) would silently reject the
        // normalized input.
        for (key in VALID_SORT_KEYS) {
            assertEquals(
                key.lowercase(),
                key,
                "sort key '$key' MUST be lowercase to match the normalized input",
            )
            assertTrue(
                key.isNotBlank(),
                "sort key MUST be non-blank",
            )
        }
    }

    @Test fun validSortKeysCaseSensitiveLookupRejectsUppercase() {
        // Pin: drift to "case-insensitive contains" at the
        // set level would let `"ID"` pass without
        // normalisation in dispatch. The dispatcher's
        // `.lowercase()` call is what handles agent-side
        // case variation.
        assertEquals(false, "ID" in VALID_SORT_KEYS)
        assertEquals(false, "Kind" in VALID_SORT_KEYS)
        assertEquals(false, "REVISION-DESC" in VALID_SORT_KEYS)
    }

    @Test fun validSortKeysRejectsTypos() {
        // Defensive: drift to add typo'd key would surface
        // here.
        for (typo in listOf(
            "i_d", "kinds", "revision_desc", "revisionDesc",
            "asc", "desc", "createdAt", "updated",
        )) {
            assertEquals(
                false,
                typo in VALID_SORT_KEYS,
                "typo '$typo' MUST NOT be in VALID_SORT_KEYS",
            )
        }
    }
}
