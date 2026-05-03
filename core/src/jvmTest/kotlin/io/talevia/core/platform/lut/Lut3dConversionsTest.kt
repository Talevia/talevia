package io.talevia.core.platform.lut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct tests for [Lut3d.toMedia3Cube] / [Lut3d.toCoreImageRgbaFloats] —
 * `core/platform/lut/Lut3dConversions.kt`. Cycle 165 audit:
 * 55 LOC, 0 direct test refs (the parser is exercised via
 * `CubeLutParserTest`; the conversions backing Android
 * Media3 + iOS CoreImage LUT pipelines were never pinned).
 *
 * Three correctness contracts pinned:
 *
 * 1. **Index conversion: R-fastest flat → `cube[R][G][B]`
 *    on Media3, R-fastest RGBA on CoreImage.** Per kdoc,
 *    Media3's `SingleColorLut.createFromCube` indexes
 *    `cube[R][G][B]`; iOS `CIColorCube` takes a flat
 *    R-fastest RGBA float buffer. Drift to wrong axis order
 *    (e.g. `cube[B][G][R]`) would silently apply LUTs with
 *    swapped channels — every grade would render "wrong
 *    color" without throwing.
 *
 * 2. **Quantization to `[0, 255]` with banker's-style
 *    rounding via `(x * 255 + 0.5).toInt()`.** Drift to
 *    truncation (`(x * 255).toInt()`) would silently shift
 *    every quantized output by up to 1 LSB — invisible per-
 *    pixel but compounds across millions of pixels into
 *    visible color casts. The float-to-int conversion in
 *    Kotlin rounds toward zero by default; the +0.5 idiom
 *    is the standard rounding fix.
 *
 * 3. **Out-of-range floats clamped to `[0, 1]`.** A `.cube`
 *    file with out-of-gamut entries (e.g. negative values
 *    in extended-range LUTs that haven't been baked to
 *    SDR) must NOT produce IntArray entries < 0 or > 255 —
 *    those would cascade as malformed ARGB ints and crash
 *    Media3's bitmap upload. Pinned with both -0.5 → 0 and
 *    1.5 → 255 cases.
 *
 * Plus structural pins: alpha is always opaque (0xFF on
 * Media3; 1.0 on CoreImage), output dimensions match `n^3`
 * triplets / `n^3 * 4` floats, identity-LUT round-trip
 * preserves diagonal entries.
 */
class Lut3dConversionsTest {

    // Helper: build a 2-cube identity LUT (8 entries × 3
    // floats = 24 floats, R-fastest order). Each (r,g,b)
    // grid point has output (r, g, b).
    private fun identityLut2(): Lut3d {
        val entries = floatArrayOf(
            // (r=0, g=0, b=0)
            0f, 0f, 0f,
            // (r=1, g=0, b=0)
            1f, 0f, 0f,
            // (r=0, g=1, b=0)
            0f, 1f, 0f,
            // (r=1, g=1, b=0)
            1f, 1f, 0f,
            // (r=0, g=0, b=1)
            0f, 0f, 1f,
            // (r=1, g=0, b=1)
            1f, 0f, 1f,
            // (r=0, g=1, b=1)
            0f, 1f, 1f,
            // (r=1, g=1, b=1)
            1f, 1f, 1f,
        )
        return Lut3d(size = 2, entries = entries)
    }

    // ── Media3 cube: dimension shape ───────────────────────────

    @Test fun media3CubeHasDimensionsNxNxN() {
        // Pin: outermost = R, middle = G, innermost = B
        // (each of length size). Drift to a flat or reshape
        // would break Media3's `createFromCube` decoder.
        val cube = identityLut2().toMedia3Cube()
        assertEquals(2, cube.size, "outermost (R) length")
        assertEquals(2, cube[0].size, "middle (G) length")
        assertEquals(2, cube[0][0].size, "innermost (B) length")
        assertEquals(2, cube[1][1].size, "all rows have full B length")
    }

    @Test fun media3CubeHandlesSize3() {
        // Pin: shape generalises to n=3 (27 RGB entries).
        val n = 3
        val entries = FloatArray(n * n * n * 3) { 0f }
        val cube = Lut3d(size = n, entries = entries).toMedia3Cube()
        assertEquals(n, cube.size)
        assertEquals(n, cube[0].size)
        assertEquals(n, cube[0][0].size)
        assertEquals(n, cube[2][2].size)
    }

    // ── Media3 cube: ARGB packing + alpha ─────────────────────

    @Test fun media3CubeAlphaIsAlwaysOpaque() {
        // Marquee opaque-alpha pin: kdoc says "Alpha is
        // always 0xFF — LUT grades RGB only." Drift to
        // 0x00 alpha would emit a fully-transparent LUT
        // that Media3 then renders as black.
        val cube = identityLut2().toMedia3Cube()
        for (r in 0..1) for (g in 0..1) for (b in 0..1) {
            val pixel = cube[r][g][b]
            val alpha = (pixel ushr 24) and 0xFF
            assertEquals(
                0xFF,
                alpha,
                "alpha must be 0xFF at cube[$r][$g][$b]; got: ${alpha.toString(16)}",
            )
        }
    }

    @Test fun media3CubeIdentityProducesExpectedArgbCorners() {
        // Marquee axis-order pin: cube[0][0][0] must come
        // from the LUT entry at (r=0, g=0, b=0) which is
        // (0,0,0) → ARGB(0xFF, 0, 0, 0) = 0xFF000000.
        // Drift to wrong axis would surface here as "black
        // came back as red."
        val cube = identityLut2().toMedia3Cube()
        // (0, 0, 0) → black
        assertEquals(
            0xFF000000.toInt(),
            cube[0][0][0],
            "cube[0][0][0] = ARGB(0xFF, 0, 0, 0)",
        )
        // (1, 1, 1) → white
        assertEquals(
            0xFFFFFFFF.toInt(),
            cube[1][1][1],
            "cube[1][1][1] = ARGB(0xFF, 0xFF, 0xFF, 0xFF)",
        )
        // (1, 0, 0) → red. ARGB packing: (FF << 24) | (FF << 16) | (00 << 8) | 00.
        assertEquals(
            0xFFFF0000.toInt(),
            cube[1][0][0],
            "cube[1][0][0] = pure red",
        )
        // (0, 1, 0) → green
        assertEquals(0xFF00FF00.toInt(), cube[0][1][0])
        // (0, 0, 1) → blue
        assertEquals(0xFF0000FF.toInt(), cube[0][0][1])
    }

    // ── Media3 cube: quantization ─────────────────────────────

    @Test fun media3CubeRoundsHalfToOneTwentyEight() {
        // Marquee rounding-recipe pin: `(0.5 * 255 + 0.5)
        // .toInt() = 128`. Drift to truncation
        // `(0.5 * 255).toInt() = 127` would shift every mid-
        // gray sample by 1 LSB. Verify with a single-entry
        // LUT (n=2 minimum, 24 floats; corner gets value 0.5
        // for all channels).
        val entries = FloatArray(2 * 2 * 2 * 3) { 0.5f }
        val cube = Lut3d(size = 2, entries = entries).toMedia3Cube()
        // Expected: 0xFF808080 (128/0x80 in each RGB channel).
        for (r in 0..1) for (g in 0..1) for (b in 0..1) {
            assertEquals(
                0xFF808080.toInt(),
                cube[r][g][b],
                "0.5 quantizes to 128 (not 127); cube[$r][$g][$b]",
            )
        }
    }

    @Test fun media3CubeRoundsBoundaryValues() {
        // Pin: 0.0 → 0, 1.0 → 255.
        val zeros = FloatArray(2 * 2 * 2 * 3) { 0f }
        val ones = FloatArray(2 * 2 * 2 * 3) { 1f }
        val cubeZero = Lut3d(2, zeros).toMedia3Cube()
        val cubeOne = Lut3d(2, ones).toMedia3Cube()
        assertEquals(0xFF000000.toInt(), cubeZero[0][0][0])
        assertEquals(0xFF000000.toInt(), cubeZero[1][1][1])
        assertEquals(0xFFFFFFFF.toInt(), cubeOne[0][0][0])
        assertEquals(0xFFFFFFFF.toInt(), cubeOne[1][1][1])
    }

    // ── Media3 cube: clamping ─────────────────────────────────

    @Test fun media3CubeClampsNegativesToZero() {
        // Marquee clamp-low pin: extended-range `.cube`
        // files with negative values must NOT produce
        // negative IntArray entries (which cascade as
        // malformed ARGB ints).
        val entries = FloatArray(2 * 2 * 2 * 3) { -0.5f }
        val cube = Lut3d(2, entries).toMedia3Cube()
        // -0.5 clamped to 0 → ARGB(0xFF, 0, 0, 0).
        assertEquals(0xFF000000.toInt(), cube[0][0][0])
    }

    @Test fun media3CubeClampsValuesAboveOneToTwoFiftyFive() {
        // Marquee clamp-high pin: HDR LUTs sometimes carry
        // entries > 1.0; must NOT produce IntArray entries
        // > 255 (which would overflow into the alpha byte).
        val entries = FloatArray(2 * 2 * 2 * 3) { 1.5f }
        val cube = Lut3d(2, entries).toMedia3Cube()
        // 1.5 clamped to 1.0 → 0xFF.
        assertEquals(0xFFFFFFFF.toInt(), cube[0][0][0])
    }

    @Test fun media3CubeMixedClampingOnlyAffectsOutOfRangeChannels() {
        // Pin: clamping is per-channel, not per-entry. A
        // mixed entry (-0.1, 0.5, 2.0) must produce
        // (0, 128, 255), not all-clamp-to-one-direction.
        val entries = floatArrayOf(
            // (0,0,0): mixed
            -0.1f, 0.5f, 2.0f,
            // pad rest to satisfy n=2 constraint
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
        )
        val cube = Lut3d(2, entries).toMedia3Cube()
        val pixel = cube[0][0][0]
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        assertEquals(0, r, "negative R clamped to 0")
        assertEquals(128, g, "0.5 G quantized to 128")
        assertEquals(255, b, "B > 1 clamped to 255")
    }

    // ── CoreImage: length + alpha ─────────────────────────────

    @Test fun coreImageOutputLengthIsSizeCubedTimesFour() {
        // Pin: output length = n * n * n * 4. Drift would
        // crash CIColorCube which validates input data
        // length against `cubeDimension^3 * 4 * 4 bytes`.
        val n2 = identityLut2().toCoreImageRgbaFloats()
        assertEquals(2 * 2 * 2 * 4, n2.size)
        val n3 = Lut3d(3, FloatArray(3 * 3 * 3 * 3) { 0f }).toCoreImageRgbaFloats()
        assertEquals(3 * 3 * 3 * 4, n3.size)
        val n5 = Lut3d(5, FloatArray(5 * 5 * 5 * 3) { 0f }).toCoreImageRgbaFloats()
        assertEquals(5 * 5 * 5 * 4, n5.size)
    }

    @Test fun coreImageAlphaIsAlwaysOne() {
        // Marquee opaque-alpha pin (CoreImage variant): per
        // kdoc "Alpha is hard-coded to `1.0` — LUT doesn't
        // touch it." Drift to 0.0 alpha would emit a fully-
        // transparent LUT; CIColorCube would render every
        // graded pixel transparent.
        val out = identityLut2().toCoreImageRgbaFloats()
        // Every 4th float starting at index 3 is the alpha.
        for (i in 3 until out.size step 4) {
            assertEquals(
                1f,
                out[i],
                "alpha at offset $i must be 1.0; got: ${out[i]}",
            )
        }
    }

    // ── CoreImage: order preservation ─────────────────────────

    @Test fun coreImagePreservesIdentityRgbValues() {
        // Marquee axis-order pin: R-fastest input order is
        // preserved on output (CIColorCube wants exactly
        // this layout). Drift to a reshape would silently
        // swap channels.
        val out = identityLut2().toCoreImageRgbaFloats()
        // Input entry 0 = (0, 0, 0) → out[0..3] = (0, 0, 0, 1).
        assertEquals(0f, out[0])
        assertEquals(0f, out[1])
        assertEquals(0f, out[2])
        assertEquals(1f, out[3])
        // Input entry 1 = (1, 0, 0) (red) → out[4..7] = (1, 0, 0, 1).
        assertEquals(1f, out[4])
        assertEquals(0f, out[5])
        assertEquals(0f, out[6])
        assertEquals(1f, out[7])
        // Last entry (1, 1, 1) → tail (1, 1, 1, 1).
        val tail = out.size - 4
        assertEquals(1f, out[tail])
        assertEquals(1f, out[tail + 1])
        assertEquals(1f, out[tail + 2])
        assertEquals(1f, out[tail + 3])
    }

    // ── CoreImage: clamping ───────────────────────────────────

    @Test fun coreImageClampsOutOfRangeFloats() {
        // Pin: CoreImage path also clamps to [0, 1]. Drift
        // would surface as out-of-gamut floats CIColorCube
        // either rejects or renders as undefined behavior.
        val entries = floatArrayOf(
            -0.5f, 0.5f, 1.5f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
            0f, 0f, 0f,
        )
        val out = Lut3d(2, entries).toCoreImageRgbaFloats()
        assertEquals(0f, out[0], "negative R clamped to 0")
        assertEquals(0.5f, out[1], "in-range G preserved")
        assertEquals(1f, out[2], "B > 1 clamped to 1")
        assertEquals(1f, out[3], "alpha hard-coded to 1.0")
    }

    // ── consistency between two outputs ───────────────────────

    @Test fun media3AndCoreImageAgreeOnIdentityLut() {
        // Pin: both conversions consume the same R-fastest
        // input. An identity LUT should produce same RGB
        // semantics on both sides — Media3 quantized to
        // [0, 255], CoreImage in [0, 1]. Drift between the
        // two would mean Android grades and iOS grades
        // diverge from the same `.cube` source.
        val lut = identityLut2()
        val media3 = lut.toMedia3Cube()
        val ci = lut.toCoreImageRgbaFloats()

        // Cross-check: cube[1][0][0] (red) must agree with
        // CoreImage entry 1 (which is at flat index
        // (1 + 0*2 + 0*4)*4 = 4). CoreImage stores R there.
        val media3R = (media3[1][0][0] ushr 16) and 0xFF
        assertEquals(255, media3R, "Media3 red quantized to 255")
        assertEquals(1f, ci[4], "CoreImage red at entry 1 stays 1.0")
        assertEquals(0f, ci[5], "CoreImage green at entry 1 = 0")
        assertEquals(0f, ci[6], "CoreImage blue at entry 1 = 0")
    }

    // ── n=3 mid-grid spot-check ───────────────────────────────

    @Test fun media3CubeReadsCorrectFlatIndexForInteriorPoints() {
        // Pin: index formula `(r + g*n + b*n*n) * 3` is
        // applied for each cube[r][g][b] read. With n=3,
        // an interior point like (1, 2, 0) reads from flat
        // index (1 + 2*3 + 0*9) * 3 = 21. Pin via a marker-
        // value LUT where each entry's R channel is set to
        // (i / 27.0f) for that entry's flat slot.
        val n = 3
        val entries = FloatArray(n * n * n * 3) { idx ->
            // Only set R channel (every 3rd float starting at 0).
            // The rest stay 0 so the alpha-channel pixel is
            // distinct.
            if (idx % 3 == 0) (idx / 3) / 27f else 0f
        }
        val cube = Lut3d(n, entries).toMedia3Cube()
        // (r=1, g=2, b=0): flat slot = 1 + 2*3 + 0*9 = 7.
        // R channel float = 7/27 ≈ 0.2592 → quantized
        // to (0.2592 * 255 + 0.5).toInt() = (66.10 + 0.5)
        // .toInt() = 66.
        val pixel = cube[1][2][0]
        val rChan = (pixel ushr 16) and 0xFF
        val expected = (7f / 27f * 255f + 0.5f).toInt()
        assertEquals(expected, rChan, "cube[1][2][0] R channel reads from flat slot 7")
        // Confirm spot is unique — slot 7 is NOT what
        // cube[2][1][0] (slot 5) reads.
        val pixel510 = cube[2][1][0]
        val rChan510 = (pixel510 ushr 16) and 0xFF
        val expected510 = (5f / 27f * 255f + 0.5f).toInt()
        assertEquals(expected510, rChan510, "cube[2][1][0] reads from slot 5")
        assertTrue(
            rChan != rChan510,
            "different cube positions read different slots — drift to wrong axis order would equate them",
        )
    }
}
