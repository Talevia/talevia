package io.talevia.core.tool.builtin.video

import io.talevia.core.TrackId
import io.talevia.core.domain.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Direct tests for [upsertTrackPreservingOrder] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/video/TrackOrder.kt`.
 * Cycle 257 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-256.
 *
 * `upsertTrackPreservingOrder(tracks, replacement)` is the
 * tiny pure helper every track-mutating tool consumes when
 * upserting a Track in the timeline. The kdoc is one sentence
 * but load-bearing: "Replace an existing track in-place so
 * UI-visible track ordering stays stable. If the track does
 * not exist yet, append it to the end of the list."
 *
 * Drift signals:
 *   - Drift to "always append" (drop the index-replacement
 *     branch) would silently shuffle UI track order on every
 *     mutate (the user sees their video track jump to the
 *     bottom every time it's edited).
 *   - Drift to "prepend new tracks" instead of append would
 *     change first-paint ordering for fresh-track adds.
 *   - Drift to "skip when id matches" (no replacement) would
 *     silently drop edits that the agent emitted.
 *
 * Pins three correctness contracts:
 *
 *  1. **Replace-in-place when id exists**: the replacement
 *     occupies the SAME index as the original. Order before
 *     and after MUST match by id at every position.
 *
 *  2. **Append when id is new**: a track with an unknown id
 *     lands at the END of the list (NOT the front, NOT mid).
 *
 *  3. **Empty list + new track** returns `[replacement]`
 *     (single-element list).
 *
 * Plus structural pins:
 *   - Sibling track variants (Video / Audio / Subtitle /
 *     Effect) are preserved untouched in their original
 *     positions when one of them is replaced.
 *   - Replacement preserves the input list size when the id
 *     exists (size unchanged); grows by 1 when the id is new.
 *   - Cross-kind replacement (e.g. Video → Audio at the same
 *     id) replaces structurally — drift to "type-tag mismatch
 *     skips" would silently leak old type-tags.
 */
class TrackOrderTest {

    private fun video(id: String) = Track.Video(id = TrackId(id))
    private fun audio(id: String) = Track.Audio(id = TrackId(id))
    private fun subtitle(id: String) = Track.Subtitle(id = TrackId(id))

    // ── 1. Empty list ───────────────────────────────────────

    @Test fun emptyListReturnsSingleElementList() {
        // Pin: starting from empty + adding a track lands a
        // single-element list. Drift to "return empty" would
        // silently drop the first track an agent emits.
        val v = video("v1")
        val out = upsertTrackPreservingOrder(emptyList(), v)
        assertEquals(listOf(v), out)
    }

    // ── 2. Append when id is new ────────────────────────────

    @Test fun newIdAppendsToEndOfList() {
        // Marquee append-when-new pin: a new track lands at the
        // END (NOT front, NOT mid). Drift to prepend would shift
        // every existing track's index by 1.
        val v1 = video("v1")
        val a1 = audio("a1")
        val v2 = video("v2")
        val out = upsertTrackPreservingOrder(listOf(v1, a1), v2)
        assertEquals(listOf(v1, a1, v2), out)
    }

    @Test fun newIdAppendsAfterMultipleExisting() {
        val tracks = listOf(video("v1"), audio("a1"), subtitle("s1"))
        val newTrack = video("v2")
        val out = upsertTrackPreservingOrder(tracks, newTrack)
        assertEquals(tracks + newTrack, out)
        assertEquals(4, out.size)
    }

    // ── 3. Replace-in-place when id exists ──────────────────

    @Test fun existingIdReplacesAtSameIndex() {
        // Marquee replace-in-place pin: the replacement
        // occupies the SAME index. Drift to "remove + append"
        // would shuffle UI ordering on every mutate.
        val v1 = video("v1")
        val a1 = audio("a1")
        val s1 = subtitle("s1")
        val tracks = listOf(v1, a1, s1)
        val v1Replacement = video("v1") // same id, but a different instance
        val out = upsertTrackPreservingOrder(tracks, v1Replacement)
        // Same size + same id sequence + replacement at index 0.
        assertEquals(3, out.size, "size unchanged when replacing")
        assertEquals(
            listOf("v1", "a1", "s1"),
            out.map { it.id.value },
            "id-by-position ordering MUST match the input list",
        )
        assertSame(
            v1Replacement,
            out[0],
            "the replacement instance MUST occupy the matching index",
        )
        assertSame(a1, out[1], "sibling at index 1 MUST be untouched")
        assertSame(s1, out[2], "sibling at index 2 MUST be untouched")
    }

    @Test fun existingIdReplacesAtMidIndex() {
        // Pin: mid-list replacement preserves head + tail. Drift
        // to "splice + reorder" would surface here.
        val v1 = video("v1")
        val a1 = audio("a1")
        val s1 = subtitle("s1")
        val a1Replacement = audio("a1")
        val out = upsertTrackPreservingOrder(listOf(v1, a1, s1), a1Replacement)
        assertSame(v1, out[0], "head untouched")
        assertSame(a1Replacement, out[1], "mid replaced in place")
        assertSame(s1, out[2], "tail untouched")
    }

    @Test fun existingIdReplacesAtTailIndex() {
        val v1 = video("v1")
        val a1 = audio("a1")
        val s1 = subtitle("s1")
        val s1Replacement = subtitle("s1")
        val out = upsertTrackPreservingOrder(listOf(v1, a1, s1), s1Replacement)
        assertSame(v1, out[0])
        assertSame(a1, out[1])
        assertSame(s1Replacement, out[2], "tail replaced in place")
        assertEquals(3, out.size, "size unchanged on tail replace")
    }

    // ── 4. Cross-kind replacement (sealed-class invariant) ──

    @Test fun crossKindReplacementSwapsTypeTagInPlace() {
        // Pin: TrackId is the identity — drift to "skip when
        // sealed-subtype differs" would silently leak old
        // type-tags when an agent reshapes a Video track to an
        // Audio track at the same id.
        val v1 = video("track-1")
        val a1 = audio("track-1") // same id, different subtype
        val out = upsertTrackPreservingOrder(listOf(v1), a1)
        assertEquals(1, out.size, "still 1 entry — same id, replaced not appended")
        assertSame(a1, out[0], "the new (Audio) variant occupies the slot")
    }

    // ── 5. Multi-replace independence ───────────────────────

    @Test fun replacingOneTrackDoesNotMutateOtherInstances() {
        // Pin: function returns a NEW list; original list
        // members untouched. Drift to in-place mutation of the
        // input list would surface here.
        val v1 = video("v1")
        val a1 = audio("a1")
        val original = listOf(v1, a1)
        val v1Replacement = video("v1")
        val out = upsertTrackPreservingOrder(original, v1Replacement)
        // Output is different from input ref-wise but original
        // list still contains the original `v1`.
        assertEquals(2, out.size)
        assertSame(v1Replacement, out[0])
        // The original list is unchanged — drift to mutate input
        // would surface here.
        assertSame(v1, original[0], "input list MUST NOT be mutated")
        assertSame(a1, original[1])
    }

    @Test fun firstMatchingIdWins() {
        // Pin: per `indexOfFirst`, the first matching index is
        // replaced. Talevia's contract is that track ids are
        // unique, but test the function-level behavior even on
        // a malformed input — drift to "replace all matches"
        // would silently propagate one replacement to multiple
        // slots.
        val v1a = video("dup")
        val v1b = video("dup") // same id (malformed input)
        val a1 = audio("a1")
        val replacement = video("dup")
        val out = upsertTrackPreservingOrder(listOf(v1a, a1, v1b), replacement)
        assertEquals(3, out.size)
        assertSame(replacement, out[0], "first match (index 0) replaced")
        assertSame(a1, out[1])
        assertSame(v1b, out[2], "second match (index 2) MUST NOT be replaced")
    }
}
