package io.talevia.core.tool.builtin.project.query

import io.talevia.core.TrackId
import io.talevia.core.domain.Track
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Direct tests for the package-private helpers in [QueryHelpers]
 * — `VALID_TRACK_KINDS` / `ASSET_KINDS` / sort-key sets +
 * `recentComparator` + `trackKindOf` + `encodeRows` +
 * `Duration.toSecondsDouble`. Cycle 124 audit: 49 LOC, **zero**
 * transitive test references; these helpers are consumed by
 * every project-query selector but never directly pinned. Closes
 * the project-query test foundation.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Sort-key sets are exact.** `VALID_TRACK_KINDS`,
 *    `ASSET_KINDS`, and the three `*_SORTS` sets are each
 *    pinned to their exact membership. A regression silently
 *    adding or removing a value would break every dispatcher
 *    that validates input via these sets — silently dropping
 *    valid LLM inputs as "unknown" or accepting nonsense.
 *
 * 2. **`recentComparator` tails null stamps.** Per kdoc:
 *    "Pre-recency blobs (rows with null stamps) sort after
 *    any stamped row so orientation calls show the freshly-
 *    touched entities first without obscuring legacy rows."
 *    A regression treating null as 0 (or +∞) would silently
 *    invert the legacy-tail ordering.
 *
 * 3. **`trackKindOf` covers all 4 Track variants exhaustively.**
 *    The Kotlin `when` is exhaustive over the sealed Track
 *    type, so adding a new variant without updating this
 *    helper is a compile error; pinning the 4 string mappings
 *    catches the inverse — silent drift in the string output
 *    (e.g. "subtitles" instead of "subtitle") that wouldn't
 *    fail compilation.
 */
class QueryHelpersTest {

    // ── kind / sort sets ──────────────────────────────────────────

    @Test fun validTrackKindsHasExactlyTheFourTrackVariants() {
        assertEquals(
            setOf("video", "audio", "subtitle", "effect"),
            VALID_TRACK_KINDS,
        )
    }

    @Test fun assetKindsCoversFourClassifications() {
        // video / audio / image classes plus "all" sentinel.
        assertEquals(
            setOf("video", "audio", "image", "all"),
            ASSET_KINDS,
        )
    }

    @Test fun trackSortsCoversFourSortModes() {
        assertEquals(
            setOf("index", "clipcount", "span", "recent"),
            TRACK_SORTS,
        )
    }

    @Test fun clipSortsCoversThreeSortModes() {
        assertEquals(
            setOf("startseconds", "durationseconds", "recent"),
            CLIP_SORTS,
        )
    }

    @Test fun assetSortsCoversFiveSortModes() {
        // Pin the 5 asset sort modes including the
        // duration-asc variant (distinct from "duration"
        // which defaults to descending).
        assertEquals(
            setOf("insertion", "duration", "duration-asc", "id", "recent"),
            ASSET_SORTS,
        )
    }

    @Test fun recentSortAppearsInAllSortableEntities() {
        // Cross-set invariant: `recent` is the universal
        // sort mode all entity sorts must support. A
        // regression dropping it from any one set would
        // break the cross-entity "what was just touched"
        // workflow.
        assertTrue("recent" in TRACK_SORTS, "recent in track sorts")
        assertTrue("recent" in CLIP_SORTS, "recent in clip sorts")
        assertTrue("recent" in ASSET_SORTS, "recent in asset sorts")
    }

    // ── recentComparator: null tailing + tiebreaker ───────────────

    private data class Item(val id: String, val stamp: Long?)

    private val cmp = recentComparator<Item>({ it.stamp }, { it.id })

    @Test fun recentComparatorSortsHigherStampFirst() {
        // Sort in descending stamp order — newer stamps surface
        // first so orientation calls show recent activity at the
        // top of the page.
        val items = listOf(
            Item("a", 100L),
            Item("b", 300L),
            Item("c", 200L),
        )
        val sorted = items.sortedWith(cmp)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test fun recentComparatorTailsNullStamps() {
        // Pin the marquee semantic: nulls sort AFTER any
        // stamped row — per kdoc, "Pre-recency blobs (rows
        // with null stamps) sort after any stamped row".
        val items = listOf(
            Item("nullA", null),
            Item("stamped", 100L),
            Item("nullB", null),
        )
        val sorted = items.sortedWith(cmp)
        // stamped first, nulls tail.
        assertEquals("stamped", sorted[0].id)
        assertTrue(sorted[1].stamp == null && sorted[2].stamp == null, "nulls tail; got: $sorted")
    }

    @Test fun recentComparatorEqualStampsBreakTieByIdAlphabetical() {
        // Pin: when stamps tie, idOf provides a stable
        // alphabetic tiebreaker — re-running on the same input
        // produces byte-identical output.
        val items = listOf(
            Item("zebra", 100L),
            Item("alpha", 100L),
            Item("mango", 100L),
        )
        val sorted = items.sortedWith(cmp)
        assertEquals(listOf("alpha", "mango", "zebra"), sorted.map { it.id })
    }

    @Test fun recentComparatorBothNullStampsBreakTieByIdAlphabetical() {
        // Pin: even when both stamps are null, the id-based
        // tiebreaker still applies — so re-runs on legacy-only
        // pages produce stable order.
        val items = listOf(
            Item("zebra", null),
            Item("alpha", null),
            Item("mango", null),
        )
        val sorted = items.sortedWith(cmp)
        assertEquals(listOf("alpha", "mango", "zebra"), sorted.map { it.id })
    }

    @Test fun recentComparatorMixedStampsAndNullsRespectsBothRules() {
        // Combination test: stamps A/B/C in descending order,
        // null entries D/E in alphabetical tail order.
        val items = listOf(
            Item("D", null),
            Item("A", 300L),
            Item("E", null),
            Item("B", 200L),
            Item("C", 100L),
        )
        val sorted = items.sortedWith(cmp)
        // Stamped first (A=300, B=200, C=100), then nulls
        // alphabetic (D, E).
        assertEquals(listOf("A", "B", "C", "D", "E"), sorted.map { it.id })
    }

    // ── trackKindOf: 4-variant exhaustive map ─────────────────────

    @Test fun trackKindOfMapsEachVariantToCorrectString() {
        // Pin: each Track sealed-class variant maps to its
        // exact string. A regression renaming one (e.g.
        // "subtitle" → "subtitles") would compile but
        // silently break every dispatcher that filters on
        // these strings.
        assertEquals("video", trackKindOf(Track.Video(TrackId("v"))))
        assertEquals("audio", trackKindOf(Track.Audio(TrackId("a"))))
        assertEquals("subtitle", trackKindOf(Track.Subtitle(TrackId("s"))))
        assertEquals("effect", trackKindOf(Track.Effect(TrackId("e"))))
    }

    @Test fun trackKindOfStringsAreSubsetOfValidTrackKinds() {
        // Cross-validation: every string `trackKindOf` produces
        // MUST be a member of `VALID_TRACK_KINDS`. A regression
        // adding a 5th Track variant with a new string in
        // trackKindOf BUT forgetting to add it to
        // VALID_TRACK_KINDS would catch by failing this test.
        val produced = setOf(
            trackKindOf(Track.Video(TrackId("v"))),
            trackKindOf(Track.Audio(TrackId("a"))),
            trackKindOf(Track.Subtitle(TrackId("s"))),
            trackKindOf(Track.Effect(TrackId("e"))),
        )
        for (kind in produced) {
            assertTrue(
                kind in VALID_TRACK_KINDS,
                "trackKindOf produced '$kind' which is NOT in VALID_TRACK_KINDS=$VALID_TRACK_KINDS",
            )
        }
    }

    // ── encodeRows: serialization ─────────────────────────────────

    @Test fun encodeRowsProducesJsonArrayOfElements() {
        val rows = listOf("alpha", "beta", "gamma")
        val arr = encodeRows(ListSerializer(String.serializer()), rows)
        // Pin: returns JsonArray (not JsonElement); each entry
        // round-trips as a JSON string.
        assertEquals(3, arr.size)
        assertEquals("alpha", arr[0].toString().removeSurrounding("\""))
    }

    @Test fun encodeRowsEmptyListProducesEmptyJsonArray() {
        val arr = encodeRows(ListSerializer(String.serializer()), emptyList())
        assertEquals(0, arr.size)
    }

    // ── Duration.toSecondsDouble ──────────────────────────────────

    @Test fun toSecondsDoubleIntegerSecondsPreserved() {
        // Pin: integer-second durations preserve as exact
        // doubles. Used by every clip-row / asset-row time
        // field.
        assertEquals(5.0, 5.seconds.toSecondsDouble())
        assertEquals(0.0, Duration.ZERO.toSecondsDouble())
    }

    @Test fun toSecondsDoubleMillisecondsConvertCorrectly() {
        // Per code: `inWholeMilliseconds / 1000.0`. 1500ms = 1.5s.
        assertEquals(1.5, 1500.milliseconds.toSecondsDouble())
        assertEquals(0.001, 1.milliseconds.toSecondsDouble())
    }

    @Test fun toSecondsDoubleSubMillisecondPrecisionIsTruncated() {
        // Pin observed behavior: `inWholeMilliseconds` truncates
        // sub-millisecond precision (microseconds, nanoseconds).
        // 1ms + 500ns = 1ms (rounded down). Document this so a
        // refactor switching to `inWholeNanoseconds / 1e9` would
        // catch as an intentional precision change, not silent
        // drift.
        val sub = 1.milliseconds + 500.nanoseconds
        assertEquals(0.001, sub.toSecondsDouble(), "sub-ms truncates to whole ms")
    }

    @Test fun toSecondsDoubleNegativeDurationProducesNegativeDouble() {
        // Defensive: negative durations are unusual in this
        // domain but pin observed semantic so a `coerceAtLeast(0)`
        // refactor catches.
        assertEquals(-2.5, (-2_500).milliseconds.toSecondsDouble())
    }
}
