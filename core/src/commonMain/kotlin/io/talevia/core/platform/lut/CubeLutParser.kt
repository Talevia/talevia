package io.talevia.core.platform.lut

/**
 * Parse an Adobe `.cube` 3D LUT file (v1.0 spec) into an engine-agnostic
 * [Lut3d]. Kept platform-neutral on purpose — both the Android
 * `Media3VideoEngine` and iOS `AVFoundationVideoEngine` import a `.cube`
 * asset via `MediaPathResolver`, read the bytes as UTF-8, and call this
 * parser before handing the result to their native color-grading
 * primitive.
 *
 * Supported directives:
 *  - `LUT_3D_SIZE N` — required (1D LUTs are rejected with a clear
 *    error; they're rare in grading workflows and add a separate code
 *    path for minimal benefit in this cut).
 *  - `DOMAIN_MIN a b c` / `DOMAIN_MAX a b c` — ignored unless they
 *    describe a non-default domain (anything other than `[0, 1]` per
 *    channel). Non-default domains aren't mapped through in v1 — the
 *    parser errors so nobody silently renders against a wrong input
 *    range. Add remapping here when a real project needs it.
 *  - `TITLE "..."` — ignored, parsed only to skip the line cleanly.
 *  - Comments (`#`) and blank lines — ignored.
 *
 * Data rows are exactly three whitespace-separated floats. After
 * `LUT_3D_SIZE N` the parser expects `N * N * N` rows in R-fastest order
 * (matching the Adobe spec).
 */
object CubeLutParser {
    fun parse(text: String): Lut3d {
        var size = -1
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val data = ArrayList<Float>()

        text.lineSequence().forEachIndexed { lineIdx, rawLine ->
            val stripped = rawLine.substringBefore('#').trim()
            if (stripped.isEmpty()) return@forEachIndexed
            val tokens = stripped.split(whitespace)
            when (tokens[0].uppercase()) {
                "TITLE" -> Unit // display-only; skip
                "LUT_3D_SIZE" -> {
                    require(tokens.size >= 2) { "line ${lineIdx + 1}: LUT_3D_SIZE missing value" }
                    size = tokens[1].toIntOrNull()
                        ?: error("line ${lineIdx + 1}: LUT_3D_SIZE value '${tokens[1]}' is not an integer")
                }
                "LUT_1D_SIZE" ->
                    error("line ${lineIdx + 1}: LUT_1D_SIZE not supported; pass a 3D `.cube` instead")
                "DOMAIN_MIN" -> domainMin = parseTriple(tokens, lineIdx, "DOMAIN_MIN")
                "DOMAIN_MAX" -> domainMax = parseTriple(tokens, lineIdx, "DOMAIN_MAX")
                else -> {
                    // Numeric row (data point). Defer validation until we see
                    // all rows so we can report the expected count.
                    require(tokens.size == 3) {
                        "line ${lineIdx + 1}: expected 3 floats, got ${tokens.size}: '$stripped'"
                    }
                    val r = tokens[0].toFloatOrNull()
                        ?: error("line ${lineIdx + 1}: '${tokens[0]}' is not a float")
                    val g = tokens[1].toFloatOrNull()
                        ?: error("line ${lineIdx + 1}: '${tokens[1]}' is not a float")
                    val b = tokens[2].toFloatOrNull()
                        ?: error("line ${lineIdx + 1}: '${tokens[2]}' is not a float")
                    data.add(r); data.add(g); data.add(b)
                }
            }
        }

        require(size >= 2) { "missing LUT_3D_SIZE directive (or size < 2)" }
        val expected = size * size * size
        require(data.size == expected * 3) {
            "expected ${expected} RGB rows for size=$size, got ${data.size / 3}"
        }
        require(domainMin.contentEquals(floatArrayOf(0f, 0f, 0f))) {
            "non-default DOMAIN_MIN ${domainMin.toList()} not supported in v1 parser"
        }
        require(domainMax.contentEquals(floatArrayOf(1f, 1f, 1f))) {
            "non-default DOMAIN_MAX ${domainMax.toList()} not supported in v1 parser"
        }

        return Lut3d(size = size, entries = data.toFloatArray())
    }

    private fun parseTriple(tokens: List<String>, lineIdx: Int, label: String): FloatArray {
        require(tokens.size >= 4) { "line ${lineIdx + 1}: $label expected 3 floats" }
        return floatArrayOf(
            tokens[1].toFloat(),
            tokens[2].toFloat(),
            tokens[3].toFloat(),
        )
    }

    private val whitespace = Regex("\\s+")
}
