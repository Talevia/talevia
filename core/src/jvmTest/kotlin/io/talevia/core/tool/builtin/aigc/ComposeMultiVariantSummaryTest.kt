package io.talevia.core.tool.builtin.aigc

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct tests for [composeMultiVariantSummary] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/aigc/AigcGenerateToolDispatchers.kt:270`.
 * Cycle 258 audit: 0 test refs.
 *
 * Same audit-pattern fallback as cycles 207-257.
 *
 * `composeMultiVariantSummary(firstLine, variants, kind)` is the
 * helper that builds the LLM-visible summary line for multi-
 * variant AIGC dispatches (when an agent emits `variantCount >
 * 1`). It compresses N variants into a single line that mentions
 * the head variant via `firstLine` and appends a comma-separated
 * tail of the remaining `vIDX=assetId` pairs.
 *
 * Drift signals:
 *   - Drift to "always append tail" (drop the size ≤ 1 branch)
 *     would silently produce `"firstLine + 0 more $kind variants:"`
 *     for single-variant calls — confusing the LLM with empty
 *     extra-info.
 *   - Drift to drop the `kind` interpolation (hardcode "image")
 *     would mismatch video / music / speech variant calls.
 *   - Drift in the `, ` separator would change the LLM's parse
 *     mental model.
 *   - Drift to include the FIRST variant in the tail (forget
 *     `.drop(1)`) would silently double-mention v0 to the LLM.
 *
 * Pins three correctness contracts:
 *
 *  1. **Single-variant short-circuit**: `variants.size <= 1`
 *     returns `firstLine` verbatim (no `+ 0 more variants:`
 *     suffix). Both empty list AND single-element list
 *     short-circuit. Drift to "always append" surfaces here.
 *
 *  2. **Multi-variant tail format**: 2+ variants produce
 *     `"$firstLine + ${size-1} more $kind variants: $tail"`
 *     where the tail is `v1=asset1, v2=asset2, ...` (skipping
 *     v0 which `firstLine` already covers). Marquee `.drop(1)`
 *     pin — drift to include v0 would double-mention.
 *
 *  3. **`kind` parameter is injected verbatim** into the
 *     summary text. Drift to hardcode "image" would break
 *     non-image variant kinds (video / music / speech).
 *
 * Plus structural pins:
 *   - `, ` (comma-space) separator (drift to `;` / newline
 *     would change LLM tokenization).
 *   - `vIDX` prefix on each variant (drift to plain index or
 *     parens would change parse).
 *   - `=` (no spaces) between variant index and asset id
 *     (drift would surface here).
 */
class ComposeMultiVariantSummaryTest {

    private fun variant(idx: Int, assetId: String) =
        AigcGenerateTool.VariantSummary(variantIndex = idx, assetId = assetId)

    // ── 1. Single-variant short-circuit ─────────────────────

    @Test fun emptyVariantsListReturnsFirstLineVerbatim() {
        // Pin: empty list short-circuits via `size <= 1`.
        // Drift to "always append" would produce a stray "+ -1
        // more" or empty suffix.
        assertEquals(
            "single-variant line",
            composeMultiVariantSummary(
                firstLine = "single-variant line",
                variants = emptyList(),
                kind = "image",
            ),
        )
    }

    @Test fun singleVariantReturnsFirstLineWithoutSuffix() {
        // Marquee single-variant pin: 1 variant means firstLine
        // already says it all — drift to "+ 0 more variants:"
        // would silently add confusing empty suffix.
        val result = composeMultiVariantSummary(
            firstLine = "Generated 1 image: v0=asset-0",
            variants = listOf(variant(0, "asset-0")),
            kind = "image",
        )
        assertEquals("Generated 1 image: v0=asset-0", result)
    }

    // ── 2. Multi-variant tail format ────────────────────────

    @Test fun twoVariantsAppendOneMoreEntry() {
        // Marquee multi-variant pin: 2 variants → "+ 1 more
        // image variants: v1=asset-1". The `+ N more $kind
        // variants:` template + `.drop(1)` tail pinned together.
        val result = composeMultiVariantSummary(
            firstLine = "Generated 2 images: v0=asset-0",
            variants = listOf(variant(0, "asset-0"), variant(1, "asset-1")),
            kind = "image",
        )
        assertEquals(
            "Generated 2 images: v0=asset-0 + 1 more image variants: v1=asset-1",
            result,
        )
    }

    @Test fun threeVariantsCommaJoinTailAfterFirst() {
        // Pin: variants[1..] joined by ", " — drift to `;` / newline
        // / space-only would change LLM parse.
        val result = composeMultiVariantSummary(
            firstLine = "Generated 3 images: v0=asset-0",
            variants = listOf(
                variant(0, "asset-0"),
                variant(1, "asset-1"),
                variant(2, "asset-2"),
            ),
            kind = "image",
        )
        assertEquals(
            "Generated 3 images: v0=asset-0 + 2 more image variants: v1=asset-1, v2=asset-2",
            result,
        )
    }

    @Test fun manyVariantsAllAppearInTail() {
        // Pin: scaling — 5 variants produces "+ 4 more" with all
        // 4 tail entries. Drift to "truncate after N" would
        // silently lose later variants from the LLM context.
        val variants = (0..4).map { variant(it, "asset-$it") }
        val result = composeMultiVariantSummary(
            firstLine = "Generated 5 images: v0=asset-0",
            variants = variants,
            kind = "image",
        )
        assertEquals(
            "Generated 5 images: v0=asset-0 + 4 more image variants: v1=asset-1, v2=asset-2, v3=asset-3, v4=asset-4",
            result,
        )
    }

    @Test fun firstVariantIsNotIncludedInTail() {
        // Marquee `.drop(1)` pin: drift to forget the drop would
        // double-mention v0 (once in firstLine, once in tail).
        // This test surfaces that exact drift via assertion on
        // the tail substring.
        val result = composeMultiVariantSummary(
            firstLine = "Generated 2 images: v0=protected-asset",
            variants = listOf(variant(0, "protected-asset"), variant(1, "second-asset")),
            kind = "image",
        )
        // Tail must mention v1 but NOT v0 again.
        val tailStart = result.indexOf("more image variants:")
        assertEquals(true, tailStart > 0, "must have multi-variant suffix; got: $result")
        val tail = result.substring(tailStart)
        assertEquals(
            true,
            "v1=second-asset" in tail,
            "tail MUST cite v1=second-asset; got tail: $tail",
        )
        assertEquals(
            false,
            "protected-asset" in tail,
            "tail MUST NOT include v0's asset id (drift to forget .drop(1)); got tail: $tail",
        )
    }

    // ── 3. kind interpolation ───────────────────────────────

    @Test fun kindInterpolatedForVideo() {
        val result = composeMultiVariantSummary(
            firstLine = "Generated 2 videos: v0=clip-0",
            variants = listOf(variant(0, "clip-0"), variant(1, "clip-1")),
            kind = "video",
        )
        assertEquals(
            "Generated 2 videos: v0=clip-0 + 1 more video variants: v1=clip-1",
            result,
        )
        assertEquals(
            true,
            "more video variants" in result,
            "kind 'video' MUST appear in summary; drift to hardcode 'image' surfaces here",
        )
    }

    @Test fun kindInterpolatedForMusic() {
        val result = composeMultiVariantSummary(
            firstLine = "Generated 3 music tracks: v0=track-0",
            variants = listOf(variant(0, "track-0"), variant(1, "track-1"), variant(2, "track-2")),
            kind = "music",
        )
        assertEquals(
            true,
            "more music variants" in result,
            "kind 'music' MUST appear in summary",
        )
        // Sanity: tail entries appear.
        assertEquals(
            true,
            "v1=track-1, v2=track-2" in result,
            "tail entries MUST be comma-joined; got: $result",
        )
    }

    @Test fun kindInterpolatedForSpeech() {
        val result = composeMultiVariantSummary(
            firstLine = "Generated 2 speeches: v0=audio-0",
            variants = listOf(variant(0, "audio-0"), variant(1, "audio-1")),
            kind = "speech",
        )
        assertEquals(
            true,
            "more speech variants" in result,
            "kind 'speech' MUST appear in summary",
        )
    }

    // ── 4. Variant-index format integrity ──────────────────

    @Test fun variantIndexAndAssetUseEqualsNoSpaces() {
        // Pin: per source, the joiner is "v${variantIndex}=${assetId}"
        // — drift to "v $idx = $asset" or "v$idx -> $asset"
        // would change the LLM's parse.
        val result = composeMultiVariantSummary(
            firstLine = "head",
            variants = listOf(variant(0, "a"), variant(1, "b"), variant(2, "c")),
            kind = "image",
        )
        // Tail substring is "v1=b, v2=c" — pin exact.
        assertEquals(
            true,
            "v1=b, v2=c" in result,
            "variant entries MUST use 'v\$idx=\$asset' (no spaces around '='); got: $result",
        )
    }

    @Test fun nonContiguousVariantIndicesEchoVerbatim() {
        // Pin: VariantSummary.variantIndex is echoed as-is —
        // drift to "renumber from 0" or "auto-increment in
        // join" would silently mis-label variants when the
        // batch dispatcher sends a non-contiguous set (e.g.
        // partial cache hits where some indices are pulled
        // from the lockfile). variantIndex is the SOURCE-OF-
        // TRUTH that the LLM reads.
        val variants = listOf(
            variant(0, "asset-0"),
            variant(2, "asset-2"), // index 2, NOT 1
            variant(7, "asset-7"), // index 7
        )
        val result = composeMultiVariantSummary(
            firstLine = "head",
            variants = variants,
            kind = "image",
        )
        assertEquals(
            true,
            "v2=asset-2, v7=asset-7" in result,
            "tail MUST echo variantIndex verbatim (NOT renumber); got: $result",
        )
    }
}
