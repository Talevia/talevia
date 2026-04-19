package io.talevia.core.platform.lut

/**
 * Parsed 3D lookup table ready to hand to a native color-grading primitive
 * (Media3 `SingleColorLut.createFromCube`, iOS `CIColorCube`, FFmpeg
 * `lut3d`, …). Engine-agnostic on purpose — both Android and iOS engines
 * share the same parser output.
 *
 * [entries] holds `size * size * size` RGB triplets as consecutive floats
 * in `[0.0, 1.0]`. Index order matches the Adobe `.cube` spec: R changes
 * fastest, then G, then B. So entry `(r, g, b)` lives at base index
 * `(r + g * size + b * size * size) * 3`.
 *
 * Consumers pick the indexing convention their native primitive expects
 * (Media3 wants `cube[R][G][B]`; iOS `CIColorCube` wants a flat RGBA
 * buffer in the same R-fastest order) — see [Lut3d.toMedia3Cube] /
 * [Lut3d.toCoreImageRgbaFloats] for the canonical conversions.
 */
data class Lut3d(
    val size: Int,
    val entries: FloatArray,
) {
    init {
        require(size >= 2) { "LUT size must be >= 2, got $size" }
        require(entries.size == size * size * size * 3) {
            "LUT entries length ${entries.size} does not match size^3 * 3 = ${size * size * size * 3}"
        }
    }

    /** Read the RGB triplet at `(r, g, b)`, each dimension `0 until size`. */
    fun at(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val i = (r + g * size + b * size * size) * 3
        return Triple(entries[i], entries[i + 1], entries[i + 2])
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Lut3d) return false
        return size == other.size && entries.contentEquals(other.entries)
    }

    override fun hashCode(): Int = 31 * size + entries.contentHashCode()
}
