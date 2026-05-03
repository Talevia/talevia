package io.talevia.core.cost

import io.talevia.core.cost.AigcPricing.TOOL_GENERATE_IMAGE
import io.talevia.core.cost.AigcPricing.TOOL_GENERATE_MUSIC
import io.talevia.core.cost.AigcPricing.TOOL_GENERATE_VIDEO
import io.talevia.core.cost.AigcPricing.TOOL_SYNTHESIZE_SPEECH
import io.talevia.core.cost.AigcPricing.TOOL_UPSCALE_ASSET
import io.talevia.core.cost.AigcPricing.priceBasisFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [AigcPricing.priceBasisFor] —
 * `core/cost/AigcPricing.kt:163`. The 5-branch pure-function helper
 * that surfaces per-tool price-basis hints to the LLM via
 * `provider_query(select=aigc_cost_estimate)` + the `list_tools`
 * tool's helpText. Cycle 226 audit: existing
 * `ListToolsToolTest.priceBasisForwardsExactAigcPricingText` covers
 * only the `generate_image` branch; the 4 other documented branches
 * (`synthesize_speech` / `generate_video` / `generate_music` /
 * `upscale_asset`) + the `else → null` fallback have no direct pin.
 *
 * Same audit-pattern fallback as cycles 207-225.
 *
 * Three correctness contracts pinned:
 *
 *  1. **All 5 documented toolId branches return a non-null hint
 *     containing the cost-basis shape.** The strings are LLM-facing —
 *     drift to wrong currency, wrong unit ("$/sq" vs "$/sec"), or
 *     missing provider name silently misleads the model when it tries
 *     to reason about which tool is cheapest for a given task.
 *
 *  2. **Provider name embedded in each hint.** Per kdoc the basis
 *     "carries the shape ('$/sq vs $/rect', '$/sec', '$/1k chars')".
 *     The hint is also expected to mention the provider name (OpenAI /
 *     Replicate) so the LLM doesn't have to cross-reference a separate
 *     `list_providers` call to attribute pricing.
 *
 *  3. **Unknown toolId → null.** The `else` arm — a non-AIGC toolId
 *     (e.g. `clip_action`, `project_query`) MUST return `null` so the
 *     query layer can render "no pricing rule" without a false-priced
 *     row. Drift to "fall through to a default string" would inject
 *     fake pricing into list_tools output.
 *
 * Plus pins:
 *   - Case sensitivity: `"GENERATE_IMAGE"` (uppercase) does NOT match
 *     `"generate_image"` (the actual constant). The `when` is
 *     case-sensitive — LLM must use the canonical lowercase tool id.
 *   - All 5 branches return distinct strings (no copy-paste of the
 *     same hint across two tools).
 */
class PriceBasisForTest {

    @Test fun generateImageHintCitesOpenAiAndPerSquareRectShape() {
        val hint = priceBasisFor(TOOL_GENERATE_IMAGE)
        assertNotNull(hint, "generate_image must have a price-basis hint")
        assertTrue("OpenAI" in hint, "expected provider name 'OpenAI'; got: $hint")
        assertTrue(
            "/square" in hint || "/sq" in hint,
            "expected per-square pricing shape; got: $hint",
        )
        // Marquee shape pin: image pricing varies by aspect — the hint
        // must surface the rectangle premium (drift to "flat" would mislead).
        assertTrue(
            "non-square" in hint || "/rect" in hint || "rectangle" in hint,
            "expected non-square/rectangle premium mentioned; got: $hint",
        )
    }

    @Test fun synthesizeSpeechHintCitesOpenAiAndPer1kCharsShape() {
        val hint = priceBasisFor(TOOL_SYNTHESIZE_SPEECH)
        assertNotNull(hint, "synthesize_speech must have a price-basis hint")
        assertTrue("OpenAI" in hint, "expected 'OpenAI' provider; got: $hint")
        // Marquee unit pin: TTS pricing is per-character (per 1k chars),
        // distinct from image (per-image) and video (per-second). Drift
        // to "$/sec" would mislead the LLM into bad cost estimates for
        // long scripts.
        assertTrue(
            "/1k chars" in hint || "/1k char" in hint || "per 1k chars" in hint,
            "expected per-1k-chars unit; got: $hint",
        )
        // Pin: HD tier mentioned, otherwise the LLM can't pick between
        // tts-1 and tts-1-hd on cost.
        assertTrue("hd" in hint.lowercase(), "expected HD tier mentioned; got: $hint")
    }

    @Test fun generateVideoHintCitesOpenAiSoraAndPerSecondShape() {
        val hint = priceBasisFor(TOOL_GENERATE_VIDEO)
        assertNotNull(hint, "generate_video must have a price-basis hint")
        assertTrue("Sora" in hint || "sora" in hint, "expected Sora model name; got: $hint")
        // Marquee unit pin: video pricing is per-second — drift to per-
        // frame would scale wildly different across 24fps vs 60fps clips.
        assertTrue(
            "/sec" in hint || "per sec" in hint || "/second" in hint,
            "expected per-second unit; got: $hint",
        )
    }

    @Test fun generateMusicHintCitesReplicateMusicgenAndPerSecondShape() {
        val hint = priceBasisFor(TOOL_GENERATE_MUSIC)
        assertNotNull(hint, "generate_music must have a price-basis hint")
        assertTrue("Replicate" in hint, "expected 'Replicate' provider; got: $hint")
        // Pin: model slug name surfaces so a reprice on the meta side
        // is traceable to the exact slug.
        assertTrue("musicgen" in hint.lowercase(), "expected 'musicgen' model; got: $hint")
        assertTrue(
            "/sec" in hint || "per sec" in hint,
            "expected per-second unit; got: $hint",
        )
    }

    @Test fun upscaleAssetHintCitesReplicateRealEsrganAndFlatPerCallShape() {
        val hint = priceBasisFor(TOOL_UPSCALE_ASSET)
        assertNotNull(hint, "upscale_asset must have a price-basis hint")
        assertTrue("Replicate" in hint, "expected 'Replicate' provider; got: $hint")
        assertTrue("real-esrgan" in hint.lowercase(), "expected model slug; got: $hint")
        // Marquee shape pin: upscale is FLAT-per-call, NOT per-pixel /
        // per-megapixel — drift to a per-pixel basis would mislead the
        // LLM into thinking 4K vs 8K outputs cost wildly differently.
        assertTrue(
            "flat" in hint.lowercase() || "per call" in hint,
            "expected flat-per-call shape; got: $hint",
        )
    }

    // ── 3. Unknown toolId fallback ──────────────────────────

    @Test fun unknownToolIdReturnsNull() {
        // Marquee fallback pin: drift to "default to flat $0.01/call"
        // would inject fake pricing into list_tools output for every
        // non-AIGC tool. The contract is null = "no pricing rule".
        for (id in listOf(
            "clip_action",
            "project_query",
            "session_action",
            "filter_action",
            "ghost_tool",
            "",
            "_",
            "GENERATE_IMAGE", // case sensitivity — see next test
        )) {
            assertNull(
                priceBasisFor(id),
                "unknown toolId '$id' must return null (no pricing rule); got non-null",
            )
        }
    }

    @Test fun toolIdMatchIsCaseSensitive() {
        // Pin: the `when` expression compares strings exactly. Tool IDs
        // are canonical lowercase per the JSON-schema convention, so
        // uppercase / mixed-case must miss. Drift to ".lowercase()" on
        // input would silently accept arbitrary casing the LLM might
        // send.
        assertNull(priceBasisFor("Generate_Image"))
        assertNull(priceBasisFor("GENERATE_IMAGE"))
        assertNull(priceBasisFor("synthesize_Speech"))
        // Sanity: lowercase canonical does match.
        assertNotNull(priceBasisFor("generate_image"))
    }

    // ── Cross-branch shape pin ──────────────────────────────

    @Test fun all5BranchesReturnDistinctStrings() {
        // Pin against copy-paste regression: each tool's hint should
        // cite a different unit / provider, so the strings must be
        // distinct. Drift to "all 5 return the same generic message"
        // would render the per-tool selection useless.
        val hints = listOf(
            priceBasisFor(TOOL_GENERATE_IMAGE),
            priceBasisFor(TOOL_SYNTHESIZE_SPEECH),
            priceBasisFor(TOOL_GENERATE_VIDEO),
            priceBasisFor(TOOL_GENERATE_MUSIC),
            priceBasisFor(TOOL_UPSCALE_ASSET),
        )
        assertTrue(hints.all { it != null }, "all 5 hints non-null")
        assertEquals(
            5,
            hints.toSet().size,
            "all 5 toolIds must produce DISTINCT hint strings (no copy-paste)",
        )
    }

    @Test fun all5BranchesReachableFromCanonicalConstants() {
        // Sanity / reachability pin: the public TOOL_* constants are
        // the only canonical entry points. Drift to "rename a constant
        // but forget the when-arm" would produce silent null returns.
        // This test mirrors the dispatcher's TOOL_* → branch wiring at
        // line 47-51 of AigcPricing.kt and ensures every constant has
        // a live branch.
        val constants = listOf(
            TOOL_GENERATE_IMAGE,
            TOOL_SYNTHESIZE_SPEECH,
            TOOL_GENERATE_VIDEO,
            TOOL_GENERATE_MUSIC,
            TOOL_UPSCALE_ASSET,
        )
        for (id in constants) {
            assertNotNull(
                priceBasisFor(id),
                "TOOL_* constant '$id' must have a non-null price-basis hint (dispatcher reachability)",
            )
        }
    }
}
