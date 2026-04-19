package io.talevia.core.platform.lut

/**
 * Media3's `SingleColorLut.createFromCube(int[][][])` expects a 3D array
 * of packed ARGB ints indexed as `cube[R][G][B]`:
 *   - outermost: R dimension (0..size-1)
 *   - middle:    G dimension
 *   - innermost: B dimension
 * Each int is `(A << 24) | (R << 16) | (G << 8) | B`, with channels in
 * `[0, 255]`. Alpha is always 0xFF — LUT grades RGB only.
 *
 * This helper reshapes the R-fastest flat entries into the [R][G][B]
 * cube Media3 wants, clamping each float to `[0, 1]` before quantising.
 */
fun Lut3d.toMedia3Cube(): Array<Array<IntArray>> {
    val n = size
    val cube = Array(n) { Array(n) { IntArray(n) } }
    for (r in 0 until n) {
        for (g in 0 until n) {
            for (b in 0 until n) {
                val i = (r + g * n + b * n * n) * 3
                val rf = entries[i].coerceIn(0f, 1f)
                val gf = entries[i + 1].coerceIn(0f, 1f)
                val bf = entries[i + 2].coerceIn(0f, 1f)
                val ri = (rf * 255f + 0.5f).toInt()
                val gi = (gf * 255f + 0.5f).toInt()
                val bi = (bf * 255f + 0.5f).toInt()
                cube[r][g][b] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
    }
    return cube
}

/**
 * iOS `CIColorCube.inputCubeData` expects raw little-endian float32 RGBA
 * values, `size * size * size * 4` floats total, in R-fastest order
 * (exactly how the `.cube` file stores them). Alpha is hard-coded to
 * `1.0` — LUT doesn't touch it.
 *
 * Returning a flat `FloatArray` keeps the Swift side minimal: it can
 * wrap the memory in a `Data` via `withUnsafeBytes` without copying.
 */
fun Lut3d.toCoreImageRgbaFloats(): FloatArray {
    val n = size
    val out = FloatArray(n * n * n * 4)
    for (i in 0 until n * n * n) {
        val src = i * 3
        out[i * 4] = entries[src].coerceIn(0f, 1f)
        out[i * 4 + 1] = entries[src + 1].coerceIn(0f, 1f)
        out[i * 4 + 2] = entries[src + 2].coerceIn(0f, 1f)
        out[i * 4 + 3] = 1f
    }
    return out
}
