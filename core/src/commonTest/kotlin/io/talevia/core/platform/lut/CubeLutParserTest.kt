package io.talevia.core.platform.lut

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CubeLutParserTest {

    @Test
    fun `parses 2x2x2 identity-ish cube`() {
        val text = """
            TITLE "sample"
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()

        val lut = CubeLutParser.parse(text)

        assertEquals(2, lut.size)
        assertEquals(8 * 3, lut.entries.size)
        // (R=0, G=0, B=0) is black; (R=1, G=1, B=1) is white.
        assertEquals(Triple(0f, 0f, 0f), lut.at(0, 0, 0))
        assertEquals(Triple(1f, 1f, 1f), lut.at(1, 1, 1))
        // R changes fastest: index 1 is (R=1, G=0, B=0) per the file.
        assertEquals(Triple(1f, 0f, 0f), lut.at(1, 0, 0))
        // Index 2 is (R=0, G=1, B=0).
        assertEquals(Triple(0f, 1f, 0f), lut.at(0, 1, 0))
    }

    @Test
    fun `strips comments and blank lines`() {
        val text = """
            # leading comment
            LUT_3D_SIZE 2

            0.0 0.0 0.0 # inline
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()

        val lut = CubeLutParser.parse(text)
        assertEquals(2, lut.size)
    }

    @Test
    fun `accepts default DOMAIN directives`() {
        val text = """
            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()

        val lut = CubeLutParser.parse(text)
        assertEquals(2, lut.size)
    }

    @Test
    fun `rejects non-default DOMAIN_MIN`() {
        val text = """
            LUT_3D_SIZE 2
            DOMAIN_MIN -1.0 -1.0 -1.0
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()

        val ex = assertFailsWith<IllegalArgumentException> { CubeLutParser.parse(text) }
        assertTrue(ex.message!!.contains("DOMAIN_MIN"), "got: ${ex.message}")
    }

    @Test
    fun `rejects LUT_1D_SIZE`() {
        val text = """
            LUT_1D_SIZE 4
            0.0 0.0 0.0
        """.trimIndent()

        val ex = assertFailsWith<IllegalStateException> { CubeLutParser.parse(text) }
        assertTrue(ex.message!!.contains("LUT_1D_SIZE"), "got: ${ex.message}")
    }

    @Test
    fun `rejects missing size`() {
        val text = """
            0.0 0.0 0.0
            1.0 0.0 0.0
        """.trimIndent()

        val ex = assertFailsWith<IllegalArgumentException> { CubeLutParser.parse(text) }
        assertTrue(ex.message!!.contains("LUT_3D_SIZE"), "got: ${ex.message}")
    }

    @Test
    fun `rejects wrong row count`() {
        val text = """
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
        """.trimIndent()

        val ex = assertFailsWith<IllegalArgumentException> { CubeLutParser.parse(text) }
        assertTrue(ex.message!!.contains("expected 8 RGB rows"), "got: ${ex.message}")
    }

    @Test
    fun `to media3 cube packs ARGB ints in R-G-B axes`() {
        // 2x2x2 cube: (1,0,0) → red, (0,1,0) → green, (0,0,1) → blue
        val text = """
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()
        val lut = CubeLutParser.parse(text)
        val cube = lut.toMedia3Cube()

        // cube[R][G][B]
        assertEquals(0xFF000000.toInt(), cube[0][0][0])                 // black
        assertEquals(0xFFFFFFFF.toInt(), cube[1][1][1])                 // white
        assertEquals((0xFF shl 24) or (255 shl 16), cube[1][0][0])      // red
        assertEquals((0xFF shl 24) or (255 shl 8), cube[0][1][0])       // green
        assertEquals((0xFF shl 24) or 255, cube[0][0][1])               // blue
    }

    @Test
    fun `to core image rgba floats keeps R-fastest order with alpha 1`() {
        val text = """
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()
        val lut = CubeLutParser.parse(text)
        val data = lut.toCoreImageRgbaFloats()

        assertEquals(8 * 4, data.size)
        // Entry 0: (0,0,0, 1)
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 1f), data.sliceArray(0..3))
        // Entry 1 = (R=1, G=0, B=0): red with alpha 1
        assertContentEquals(floatArrayOf(1f, 0f, 0f, 1f), data.sliceArray(4..7))
        // Last entry = (R=1, G=1, B=1)
        assertContentEquals(floatArrayOf(1f, 1f, 1f, 1f), data.sliceArray(28..31))
    }

    @Test
    fun `clamps out-of-range values for media3 conversion`() {
        val text = """
            LUT_3D_SIZE 2
            -0.1 0.0 0.0
            1.5 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()
        val lut = CubeLutParser.parse(text)
        val cube = lut.toMedia3Cube()

        // Negative red → clamped to 0, alpha still 0xFF.
        assertEquals(0xFF000000.toInt(), cube[0][0][0])
        // Red > 1 → clamped to 255 → red channel saturated.
        assertEquals((0xFF shl 24) or (255 shl 16), cube[1][0][0])
    }
}
