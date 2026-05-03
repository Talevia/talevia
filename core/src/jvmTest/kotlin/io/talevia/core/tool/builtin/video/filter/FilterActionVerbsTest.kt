package io.talevia.core.tool.builtin.video.filter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [FILTER_ACTION_VERBS] /
 * [FILTER_ACTION_VERBS_BY_ID] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/video/filter/FilterActionVerbs.kt`.
 * Cycle 260 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-259. Sister to
 * cycle 259's `SOURCE_QUERY_SELECTS` plugin-registry pin —
 * same shape (`List<X>` + `associateBy` lookup map) for the
 * action-verb (cycle 161) side instead of the query-select
 * (cycle 154) side.
 *
 * `FILTER_ACTION_VERBS` is the cycle-161 plugin-shape registry
 * for `filter_action`'s 3 verbs (`apply` / `remove` /
 * `apply_lut`). The dispatcher reads it at execute() time to
 * look up the verb handler; `FILTER_ACTION_VERBS_BY_ID` is the
 * indexed form for O(1) lookup (consumed by
 * `FilterActionTool.execute()` line 253).
 *
 * Drift signals (sister to cycle 259's):
 *   - New verb object created but NOT added to the registry →
 *     silently never dispatched (`runFooFilter` becomes dead
 *     code).
 *   - Two verbs sharing the same `id` → `associateBy` keeps
 *     LAST occurrence; first becomes dead code without a
 *     compile error.
 *   - Per-verb id drifts from the canonical schema enum
 *     (`apply` / `remove` / `apply_lut`) → user-facing input
 *     never matches registry key.
 *
 * Pins three correctness contracts:
 *
 *  1. **Registry has exactly 3 entries** — kdoc-canonical
 *     count for the filter_action verb family. Drift to add
 *     a 4th without test coverage surfaces here. Plus
 *     duplicate-id guard.
 *
 *  2. **Each verb's `id` matches the canonical wire string**:
 *     `ApplyFilterActionVerb.id == "apply"`,
 *     `RemoveFilterActionVerb.id == "remove"`,
 *     `ApplyLutFilterActionVerb.id == "apply_lut"`. These
 *     strings are LLM-visible (the agent emits
 *     `action="apply"` etc.) — drift would silently break
 *     dispatch for a typo.
 *
 *  3. **`FILTER_ACTION_VERBS_BY_ID` integrity**: 3 entries
 *     (no id collisions); every id resolves to the right
 *     verb object instance; unknown id returns null;
 *     case-sensitive lookup.
 *
 * Plus singleton-instance pin: registry entries MUST be the
 * SAME `internal object` instances (referential identity via
 * `===`).
 */
class FilterActionVerbsTest {

    // ── 1. Registry size + canonical id set ─────────────────

    @Test fun registryHasExactlyThreeEntries() {
        // Marquee count pin: 3 verbs per the kdoc. Drift to add
        // a 4th without test coverage surfaces here.
        assertEquals(
            3,
            FILTER_ACTION_VERBS.size,
            "FILTER_ACTION_VERBS MUST have exactly 3 entries (apply / remove / apply_lut)",
        )
    }

    @Test fun registryIdsAreCanonicalWireStrings() {
        // Marquee canonical-set pin: the 3 verb ids match
        // exactly the LLM-visible wire strings the schema enum
        // declares.
        val ids = FILTER_ACTION_VERBS.map { it.id }.toSet()
        assertEquals(
            setOf("apply", "remove", "apply_lut"),
            ids,
            "registry ids MUST match the canonical {apply, remove, apply_lut} set",
        )
    }

    @Test fun registryHasNoDuplicateIds() {
        // Pin: drift to "two verbs share id" would let
        // associateBy collapse one entry — the survivor wins
        // dispatch silently.
        val ids = FILTER_ACTION_VERBS.map { it.id }
        assertEquals(
            ids.size,
            ids.toSet().size,
            "registry MUST NOT have duplicate ids (associateBy would silently drop one); got: $ids",
        )
    }

    // ── 2. Per-verb id matches canonical wire string ────────

    @Test fun applyVerbIdIsApply() {
        assertEquals(
            "apply",
            ApplyFilterActionVerb.id,
            "ApplyFilterActionVerb.id MUST be 'apply' (LLM-visible wire string)",
        )
    }

    @Test fun removeVerbIdIsRemove() {
        assertEquals(
            "remove",
            RemoveFilterActionVerb.id,
            "RemoveFilterActionVerb.id MUST be 'remove' (LLM-visible wire string)",
        )
    }

    @Test fun applyLutVerbIdIsApplyLut() {
        // Pin: snake_case `apply_lut` (NOT `applyLut` /
        // `apply-lut` / `applyLUT`). The schema enum / LLM
        // emits the snake form.
        assertEquals(
            "apply_lut",
            ApplyLutFilterActionVerb.id,
            "ApplyLutFilterActionVerb.id MUST be 'apply_lut' (snake_case, NOT camelCase)",
        )
    }

    // ── 3. FILTER_ACTION_VERBS_BY_ID integrity ──────────────

    @Test fun byIdMapHasThreeEntries() {
        // Pin: associateBy preserves the 3 entries (no id
        // collisions). Drift would surface as size != 3.
        assertEquals(
            3,
            FILTER_ACTION_VERBS_BY_ID.size,
            "FILTER_ACTION_VERBS_BY_ID MUST have exactly 3 entries",
        )
    }

    @Test fun byIdMapResolvesEachCanonicalIdToExpectedVerb() {
        // Marquee dispatch-correctness pin: looking up the
        // canonical id returns the matching verb object.
        // Drift in associateBy or in the per-verb id would
        // surface here.
        assertEquals(ApplyFilterActionVerb, FILTER_ACTION_VERBS_BY_ID["apply"])
        assertEquals(RemoveFilterActionVerb, FILTER_ACTION_VERBS_BY_ID["remove"])
        assertEquals(ApplyLutFilterActionVerb, FILTER_ACTION_VERBS_BY_ID["apply_lut"])
    }

    @Test fun byIdMapReturnsNullForUnknownId() {
        // Pin: lookup MUST return null for unknown ids (not
        // crash, not return a fallback). The dispatcher uses
        // the null-return to surface a clear "unknown action"
        // error to the agent (FilterActionTool.kt:253-256).
        assertEquals(null, FILTER_ACTION_VERBS_BY_ID["unknown"])
        assertEquals(null, FILTER_ACTION_VERBS_BY_ID[""])
    }

    @Test fun byIdMapLookupIsCaseSensitive() {
        // Pin: drift to `lowercase()` keys would silently
        // accept `"APPLY"` / `"Apply"` (LLMs commonly emit
        // both) — the dispatcher should reject these as
        // unknown so the agent retries with the canonical
        // form, NOT silently dispatch with case-folded match.
        assertEquals(null, FILTER_ACTION_VERBS_BY_ID["APPLY"])
        assertEquals(null, FILTER_ACTION_VERBS_BY_ID["Apply"])
        assertEquals(null, FILTER_ACTION_VERBS_BY_ID["APPLY_LUT"])
    }

    // ── 4. Object instances are stable singletons ───────────

    @Test fun verbObjectsAreSameInstanceInRegistryAndByIdMap() {
        // Pin: per `internal object FooFilterActionVerb : ...`
        // each verb is a Kotlin singleton — the registry list
        // AND the by-id map MUST contain the SAME instance.
        // Drift to "list constructs new instances" would
        // silently change identity.
        assertTrue(
            FILTER_ACTION_VERBS_BY_ID["apply"] === ApplyFilterActionVerb,
            "by-id map's 'apply' entry MUST be the SAME singleton instance",
        )
        assertTrue(
            FILTER_ACTION_VERBS_BY_ID["remove"] === RemoveFilterActionVerb,
            "by-id map's 'remove' entry MUST be the SAME singleton instance",
        )
        assertTrue(
            FILTER_ACTION_VERBS_BY_ID["apply_lut"] === ApplyLutFilterActionVerb,
            "by-id map's 'apply_lut' entry MUST be the SAME singleton instance",
        )
    }

    @Test fun registryListContainsAllThreeSingletons() {
        // Pin: the list MUST contain the singleton instances
        // (NOT freshly-constructed copies). Drift to
        // `listOf(FooVerb(), ...)` would silently produce
        // distinct instances.
        assertTrue(ApplyFilterActionVerb in FILTER_ACTION_VERBS)
        assertTrue(RemoveFilterActionVerb in FILTER_ACTION_VERBS)
        assertTrue(ApplyLutFilterActionVerb in FILTER_ACTION_VERBS)
    }
}
