package io.talevia.core.platform.lut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [Lut3d] —
 * `core/platform/lut/Lut3d.kt`. Cycle 234 audit: data class is
 * exercised via construction in `Lut3dConversionsTest` but its own
 * **init validation** (`require(size >= 2)`, entries-length match)
 * and **R-fastest indexing convention** are not directly pinned.
 * Drift in either invariant would silently produce wrong color-grade
 * output across all engines that consume the `Lut3d` (FFmpeg,
 * Media3, iOS CoreImage).
 *
 * Same audit-pattern fallback as cycles 207-233.
 *
 * Three correctness contracts pinned:
 *
 *  1. **`size >= 2` precondition** — a 1-vertex LUT is geometrically
 *     ill-defined (no interior to interpolate). The `require` fails
 *     loud at construction with "LUT size must be >= 2, got X".
 *     Drift to "no size check" would let single-vertex LUTs through
 *     to engines that crash on the boundary degenerate.
 *
 *  2. **entries length = size^3 * 3** — RGB triplets per
 *     (r, g, b) cell. Drift to a wrong length would silently mis-
 *     align the indexing math at every `at(r, g, b)` call. Pin
 *     covers under-allocated and over-allocated buffers separately
 *     since both reach the same require but with different "got X"
 *     messages.
 *
 *  3. **R-fastest indexing per Adobe `.cube` spec** — `at(r, g, b)`
 *     returns the triplet at index `(r + g*size + b*size*size) * 3`.
 *     Marquee spec-conformance pin: drift to "B-fastest" (some LUT
 *     consumers default that direction) would invert color
 *     channels across the whole grade. Pinned via fingerprint
 *     entries that vary along each axis independently.
 *
 * Plus pins:
 *   - `equals()` uses `contentEquals` for the FloatArray field —
 *     drift to default array-equality (identity) would make two
 *     LUTs with identical numeric content compare unequal,
 *     breaking lockfile dedup / cache lookup. Marquee
 *     contentEquals pin.
 *   - `hashCode()` matches equals semantics (identical content →
 *     identical hash).
 *   - Minimum size (2) and a larger size (4, 5) both construct
 *     and index correctly.
 */
class Lut3dTest {

    // ── 1. size precondition ────────────────────────────────

    @Test fun sizeOneRejected() {
        // Marquee precondition pin: a 1-vertex LUT is geometrically
        // ill-defined.
        val ex = assertFailsWith<IllegalArgumentException> {
            Lut3d(size = 1, entries = FloatArray(3) { 0f })
        }
        assertTrue(
            "LUT size must be >= 2" in (ex.message ?: ""),
            "expected size-violation message; got: ${ex.message}",
        )
        assertTrue(
            "got 1" in (ex.message ?: ""),
            "expected actual size cited; got: ${ex.message}",
        )
    }

    @Test fun sizeZeroRejected() {
        assertFailsWith<IllegalArgumentException> {
            Lut3d(size = 0, entries = FloatArray(0))
        }
    }

    @Test fun sizeNegativeRejected() {
        assertFailsWith<IllegalArgumentException> {
            Lut3d(size = -1, entries = FloatArray(0))
        }
    }

    @Test fun sizeTwoIsTheMinimumAccepted() {
        // Pin: size = 2 must construct successfully (the "smallest
        // legitimate cube" — 2³ = 8 vertices = 24 floats).
        val lut = Lut3d(size = 2, entries = FloatArray(2 * 2 * 2 * 3) { 0f })
        assertEquals(2, lut.size)
        assertEquals(24, lut.entries.size)
    }

    // ── 2. entries length match ─────────────────────────────

    @Test fun entriesLengthUnderAllocatedRejected() {
        // Marquee length pin (under-alloc): drift to "no length
        // check" would let mis-aligned indexing slip through and
        // crash at the FIRST out-of-bounds read inside at().
        val ex = assertFailsWith<IllegalArgumentException> {
            Lut3d(size = 3, entries = FloatArray(20)) // expected 81
        }
        val msg = ex.message ?: ""
        assertTrue(
            "LUT entries length 20 does not match" in msg,
            "expected actual length cited; got: $msg",
        )
        assertTrue(
            "size^3 * 3 = 81" in msg,
            "expected expected-length cited; got: $msg",
        )
    }

    @Test fun entriesLengthOverAllocatedRejected() {
        // Same require, but the "got X" reports the actual length
        // (here 100, expected 81). Pin separately so a future
        // refactor that only checks `<` instead of `!=` would fail
        // here.
        val ex = assertFailsWith<IllegalArgumentException> {
            Lut3d(size = 3, entries = FloatArray(100)) // expected 81
        }
        assertTrue(
            "LUT entries length 100 does not match" in (ex.message ?: ""),
            "over-allocated entries also rejected; got: ${ex.message}",
        )
    }

    @Test fun entriesLengthExactMatchAccepted() {
        // size=4 → 64 cells → 192 floats.
        val lut = Lut3d(size = 4, entries = FloatArray(4 * 4 * 4 * 3) { 0f })
        assertEquals(4, lut.size)
        assertEquals(192, lut.entries.size)
    }

    // ── 3. R-fastest indexing per Adobe .cube spec ─────────

    @Test fun atIndexingUsesRFastestThenGThenB() {
        // Marquee spec-conformance pin: per Lut3d kdoc, "R changes
        // fastest, then G, then B. So entry (r, g, b) lives at base
        // index (r + g * size + b * size * size) * 3."
        //
        // Construct a 3×3×3 LUT where entry[r, g, b] = (r/10, g/10,
        // b/10) so we can verify the indexing pulls out the right
        // triplet for each query coord. Drift to "B-fastest"
        // (the index swap (b + g*size + r*size*size)) would surface
        // here as the (r=2, g=0, b=0) test pulling (0.0, 0.0, 0.2)
        // instead of (0.2, 0.0, 0.0).
        val n = 3
        val entries = FloatArray(n * n * n * 3)
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val base = (r + g * n + b * n * n) * 3
                    entries[base] = r / 10f
                    entries[base + 1] = g / 10f
                    entries[base + 2] = b / 10f
                }
            }
        }
        val lut = Lut3d(size = n, entries = entries)

        // Scan along R axis only (g=0, b=0): each step changes R.
        assertEquals(Triple(0.0f, 0.0f, 0.0f), lut.at(0, 0, 0), "origin")
        assertEquals(Triple(0.1f, 0.0f, 0.0f), lut.at(1, 0, 0), "R+1")
        assertEquals(Triple(0.2f, 0.0f, 0.0f), lut.at(2, 0, 0), "R+2 (catches B-fastest drift)")

        // Scan along G axis only (r=0, b=0): each step changes G.
        assertEquals(Triple(0.0f, 0.1f, 0.0f), lut.at(0, 1, 0))
        assertEquals(Triple(0.0f, 0.2f, 0.0f), lut.at(0, 2, 0))

        // Scan along B axis only (r=0, g=0): each step changes B.
        assertEquals(Triple(0.0f, 0.0f, 0.1f), lut.at(0, 0, 1))
        assertEquals(Triple(0.0f, 0.0f, 0.2f), lut.at(0, 0, 2))

        // Mixed coords.
        assertEquals(Triple(0.1f, 0.2f, 0.0f), lut.at(1, 2, 0))
        assertEquals(Triple(0.2f, 0.1f, 0.2f), lut.at(2, 1, 2))
    }

    // ── 4. equals + hashCode use contentEquals ─────────────

    @Test fun equalsCompareEntriesByContentNotByReference() {
        // Marquee FloatArray-equality pin: Kotlin's default
        // `data class equals` uses identity comparison for arrays.
        // The Lut3d override calls `contentEquals` so two instances
        // with the same numeric content compare equal — load-
        // bearing for lockfile dedup / cache lookup.
        val entriesA = FloatArray(2 * 2 * 2 * 3) { it / 100f }
        val entriesB = FloatArray(2 * 2 * 2 * 3) { it / 100f }
        // Different array instances, identical content.
        assertNotEquals(entriesA, entriesB, "test setup: arrays are different references")
        val lutA = Lut3d(2, entriesA)
        val lutB = Lut3d(2, entriesB)
        assertEquals(lutA, lutB, "Lut3d equals uses contentEquals (NOT identity)")
        assertEquals(
            lutA.hashCode(),
            lutB.hashCode(),
            "hashCode must match equals — same content → same hash",
        )
    }

    @Test fun equalsDifferentSizeReturnsFalse() {
        val lutSmall = Lut3d(2, FloatArray(2 * 2 * 2 * 3) { 0f })
        val lutLarge = Lut3d(3, FloatArray(3 * 3 * 3 * 3) { 0f })
        assertNotEquals<Lut3d>(lutSmall, lutLarge)
    }

    @Test fun equalsDifferentContentReturnsFalse() {
        val lutA = Lut3d(2, FloatArray(2 * 2 * 2 * 3) { 0.5f })
        val lutB = Lut3d(2, FloatArray(2 * 2 * 2 * 3) { 0.6f })
        assertNotEquals(lutA, lutB)
    }

    @Test fun equalsAgainstNonLut3dReturnsFalse() {
        // Pin: `other !is Lut3d` arm. Drift to omit the type check
        // would crash on smart-cast fail or compare against
        // unrelated objects.
        val lut = Lut3d(2, FloatArray(2 * 2 * 2 * 3) { 0f })
        assertNotEquals<Any>(lut, "not a lut")
        assertNotEquals<Any?>(lut, null)
    }

    @Test fun equalsReflexive() {
        val lut = Lut3d(2, FloatArray(2 * 2 * 2 * 3) { 0.5f })
        assertEquals(lut, lut, "equals must be reflexive (this === other shortcut)")
        assertEquals(lut.hashCode(), lut.hashCode(), "hashCode is stable across calls on the same instance")
    }

    // ── Boundary index pin ─────────────────────────────────

    @Test fun atIndicesAtSizeBoundary() {
        // Pin: at(size-1, size-1, size-1) reads the last triplet.
        // Drift to off-by-one in index math would surface here as
        // either AIOOBE or wrong content.
        val n = 4
        val entries = FloatArray(n * n * n * 3)
        // Set only the LAST triplet to (1.0, 1.0, 1.0); rest zero.
        val lastBase = ((n - 1) + (n - 1) * n + (n - 1) * n * n) * 3
        entries[lastBase] = 1f
        entries[lastBase + 1] = 1f
        entries[lastBase + 2] = 1f
        val lut = Lut3d(n, entries)
        assertEquals(Triple(1f, 1f, 1f), lut.at(n - 1, n - 1, n - 1))
        // Sibling cells stay zero.
        assertEquals(Triple(0f, 0f, 0f), lut.at(0, 0, 0))
    }
}
