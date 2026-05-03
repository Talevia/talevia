package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests for [runAigcCostEstimateQuery] —
 * `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/provider/query/AigcCostEstimateQuery.kt`.
 * Cycle 247 audit: only INDIRECT coverage exists
 * (`ProviderQueryAigcCostEstimateTest` pins `cents` calculation
 * via ProviderQueryTool dispatch but does NOT pin the user-visible
 * `outputForLlm` / `title` formatting, basis-decoration
 * parentheticals, or pricedInputs round-trip).
 *
 * Same audit-pattern fallback as cycles 207-246. Sister to cycles
 * 245 / 246's [runEngineReadinessQuery] / [runRateLimitHistoryQuery]
 * pins — this query has a different shape (always 1 row, NOT
 * aggregated; 2 branches keyed on `cents != null`) but the
 * principle is the same: the `outputForLlm` field is what the
 * LLM reads at planning time, drift in either branch silently
 * shifts what the agent decides about cost.
 *
 * `runAigcCostEstimateQuery(toolId, providerId, modelId, inputs)`
 * delegates to [AigcPricing.estimateCents] + [AigcPricing.priceBasisFor]
 * and folds the two outputs into a single ToolResult. Two branches:
 *
 *   - `cents != null` → "Estimate: X¢ for $toolId on $providerId/$modelId"
 *     + optional " (basis: $basis)" decoration if basis non-null.
 *     The trailing punctuation is "." when basis is null,
 *     parenthetical when basis is present.
 *   - `cents == null` → "No pricing rule matched for $toolId on
 *     $providerId/$modelId. " + optional "Tool basis: $basis. "
 *     prefix + "Consult the provider's published rates."
 *     The recovery hint at the end is canonical.
 *
 * Pins three correctness contracts:
 *
 *  1. **Cents-not-null branch summary format.** Drift to drop the
 *     `¢` glyph, the `for`/`on` connector words, or the
 *     `provider/model` slash separator silently changes the LLM's
 *     parse. Plus the basis-parenthetical decoration: `(basis: ...)`
 *     or trailing `.` — drift between the two surfaces here.
 *
 *  2. **Cents-null branch surfaces the `Consult the provider's
 *     published rates.` recovery hint.** This is the agent's
 *     diagnosis pointer when no rule matched. Drift to drop or
 *     reword would silently lose the recovery action. Plus the
 *     `Tool basis:` decoration when basis is non-null (priced
 *     tool but unknown provider/model) vs absent when basis is
 *     null (truly unknown tool).
 *
 *  3. **Title format `provider_query aigc_cost_estimate ($toolId)`**
 *     and **always 1-row + total=1 + returned=1**. The Output
 *     shape is stable regardless of branch — drift to "no rows
 *     when cents is null" would collapse the three-state cents
 *     contract (null = unknown, not free).
 *
 * Plus a `pricedInputs` round-trip pin: the input JsonObject
 * echoes verbatim into the row so callers can diff against what
 * was priced. Drift to "drop pricedInputs on null cents" would
 * lose the diff hint.
 */
class AigcCostEstimateQueryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun rowsFrom(out: ProviderQueryTool.Output): List<AigcCostEstimateRow> =
        json.decodeFromJsonElement(
            ListSerializer(AigcCostEstimateRow.serializer()),
            out.rows,
        )

    private fun imageInputs(width: Int = 1024, height: Int = 1024): JsonObject =
        buildJsonObject {
            put("width", JsonPrimitive(width))
            put("height", JsonPrimitive(height))
        }

    // ── 1. Cents-not-null branch ────────────────────────────

    @Test fun centsNonNullSummaryUsesEstimateFormat() {
        // Marquee branch-A summary pin: "Estimate: X¢ for $toolId
        // on $providerId/$modelId (basis: ...)". Drift to drop
        // the ¢ glyph or change connector words ("for"/"on")
        // would silently shift LLM parse. dall-e-3 square is the
        // canonical 4¢ cell from the pricing table.
        val result = runAigcCostEstimateQuery(
            toolId = "generate_image",
            providerId = "openai",
            modelId = "dall-e-3",
            inputs = imageInputs(),
        )
        assertTrue(
            "Estimate: 4¢ for generate_image on openai/dall-e-3" in result.outputForLlm,
            "summary MUST use 'Estimate: X¢ for $\$toolId on $\$providerId/$\$modelId' format; got: ${result.outputForLlm}",
        )
    }

    @Test fun centsNonNullSummaryDecoratesWithBasisInParens() {
        // Marquee basis-decoration pin: when both cents AND basis
        // are non-null, summary closes with "(basis: ...)". Drift
        // to "basis: ..." (no parens) or "; basis ..." would
        // silently change LLM parse.
        val result = runAigcCostEstimateQuery(
            toolId = "generate_image",
            providerId = "openai",
            modelId = "dall-e-3",
            inputs = imageInputs(),
        )
        assertTrue(
            "(basis:" in result.outputForLlm,
            "cents-not-null + basis MUST decorate with '(basis: ...)' parenthetical; got: ${result.outputForLlm}",
        )
        // The basis text references the OpenAI/dall-e-3 row in
        // the pricing-basis table.
        assertTrue(
            "OpenAI" in result.outputForLlm && "dall-e-3" in result.outputForLlm,
            "basis decoration MUST cite the OpenAI / dall-e-3 row from the pricing-basis table; got: ${result.outputForLlm}",
        )
        // Negative-evidence: cents-not-null branch MUST NOT carry
        // the cents-null branch's "No pricing rule" phrase.
        assertTrue(
            "No pricing rule matched" !in result.outputForLlm,
            "cents-not-null branch MUST NOT carry the cents-null phrase",
        )
    }

    @Test fun centsNonNullScalesByDurationForVideo() {
        // Pin: video pricing scales by `durationSeconds` — drift
        // in the summary would lose the per-second breakdown.
        // sora @ 8 seconds = 240¢ per the pricing table.
        val result = runAigcCostEstimateQuery(
            toolId = "generate_video",
            providerId = "openai",
            modelId = "sora",
            inputs = buildJsonObject { put("durationSeconds", JsonPrimitive(8)) },
        )
        assertTrue(
            "Estimate: 240¢ for generate_video on openai/sora" in result.outputForLlm,
            "video summary MUST cite 240¢ for 8s × 30¢/s; got: ${result.outputForLlm}",
        )
        val row = rowsFrom(result.data).single()
        assertEquals(240L, row.cents)
    }

    // ── 2. Cents-null branch ────────────────────────────────

    @Test fun centsNullKnownToolUnknownProviderShowsToolBasisAndRecoveryHint() {
        // Marquee branch-B pin: cents=null because the provider
        // doesn't match the rule. Tool basis IS known (the tool
        // is recognised). Summary surfaces both:
        //   1. "No pricing rule matched for $toolId on $provider/$model."
        //   2. "Tool basis: $basis. " (decoration when basis is non-null)
        //   3. "Consult the provider's published rates." (canonical hint)
        val result = runAigcCostEstimateQuery(
            toolId = "generate_image",
            providerId = "unknown-provider",
            modelId = "x",
            inputs = imageInputs(),
        )
        assertTrue(
            "No pricing rule matched for generate_image on unknown-provider/x" in result.outputForLlm,
            "cents-null MUST surface 'No pricing rule matched for $\$toolId on $\$provider/$\$model'; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Tool basis:" in result.outputForLlm,
            "cents-null + non-null basis MUST decorate with 'Tool basis: ...'; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Consult the provider's published rates" in result.outputForLlm,
            "cents-null MUST end with the canonical recovery hint; got: ${result.outputForLlm}",
        )
        // Row reflects the three-state: cents is null, basis is non-null.
        val row = rowsFrom(result.data).single()
        assertNull(row.cents, "unknown provider MUST keep cents=null (NOT collapse to 0)")
        assertNotNull(row.priceBasis, "known tool MUST carry priceBasis even when cents is null")
    }

    @Test fun centsNullUnknownToolHasNoToolBasisDecoration() {
        // Pin: when both cents AND basis are null (truly unknown
        // tool), the summary MUST NOT carry "Tool basis:". Drift
        // to "Tool basis: null." would leak internals to the LLM.
        val result = runAigcCostEstimateQuery(
            toolId = "totally_unknown_tool",
            providerId = "x",
            modelId = "y",
            inputs = JsonObject(emptyMap()),
        )
        assertTrue(
            "No pricing rule matched" in result.outputForLlm,
            "unknown tool MUST surface 'No pricing rule matched'; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Consult the provider's published rates" in result.outputForLlm,
            "unknown tool MUST still surface recovery hint; got: ${result.outputForLlm}",
        )
        assertTrue(
            "Tool basis:" !in result.outputForLlm,
            "unknown tool (basis=null) MUST NOT carry 'Tool basis:'; got: ${result.outputForLlm}",
        )
        // Row reflects double-null.
        val row = rowsFrom(result.data).single()
        assertNull(row.cents)
        assertNull(row.priceBasis)
    }

    // ── 3. Title format + 1-row Output shape ────────────────

    @Test fun titleIncludesToolIdInParens() {
        // Pin: title format `provider_query aigc_cost_estimate
        // ($toolId)`. Drift in the parenthesisation or word
        // order would shift logging / tracing.
        val result = runAigcCostEstimateQuery(
            toolId = "generate_music",
            providerId = "x",
            modelId = "y",
            inputs = JsonObject(emptyMap()),
        )
        assertEquals(
            "provider_query aigc_cost_estimate (generate_music)",
            result.title,
            "title MUST be 'provider_query aigc_cost_estimate (\$toolId)'",
        )
    }

    @Test fun outputAlwaysHasOneRowRegardlessOfBranch() {
        // Marquee always-1-row pin: the Output's `total/returned`
        // is 1 in BOTH branches. Drift to "no rows when cents is
        // null" would collapse the three-state contract.
        val a = runAigcCostEstimateQuery(
            toolId = "generate_image",
            providerId = "openai",
            modelId = "dall-e-3",
            inputs = imageInputs(),
        )
        val b = runAigcCostEstimateQuery(
            toolId = "totally_unknown_tool",
            providerId = "x",
            modelId = "y",
            inputs = JsonObject(emptyMap()),
        )
        for ((label, result) in listOf("cents-not-null" to a, "cents-null" to b)) {
            assertEquals(1, result.data.total, "$label MUST report total=1")
            assertEquals(1, result.data.returned, "$label MUST report returned=1")
            assertEquals(1, result.data.rows.size, "$label MUST emit exactly 1 row")
        }
    }

    @Test fun selectFieldIsCanonicalAigcCostEstimateConstant() {
        val result = runAigcCostEstimateQuery(
            toolId = "anything",
            providerId = "anyone",
            modelId = "anymodel",
            inputs = JsonObject(emptyMap()),
        )
        assertEquals(
            ProviderQueryTool.SELECT_AIGC_COST_ESTIMATE,
            result.data.select,
            "select MUST be the canonical SELECT_AIGC_COST_ESTIMATE constant",
        )
    }

    // ── 4. pricedInputs round-trip ──────────────────────────

    @Test fun pricedInputsEchoesInputsVerbatim() {
        // Marquee diff-debugging pin: pricedInputs in the row
        // carries the EXACT JsonObject we priced against. Drift
        // to "filter to known fields" or "lower-case keys" would
        // silently change the diff signal callers rely on.
        val inputs = buildJsonObject {
            put("width", JsonPrimitive(1024))
            put("height", JsonPrimitive(1024))
            put("style", JsonPrimitive("vivid"))
            put("ignoredField", JsonPrimitive("noise"))
        }
        val result = runAigcCostEstimateQuery(
            toolId = "generate_image",
            providerId = "openai",
            modelId = "dall-e-3",
            inputs = inputs,
        )
        val row = rowsFrom(result.data).single()
        assertEquals(
            inputs,
            row.pricedInputs,
            "pricedInputs MUST round-trip the input JsonObject verbatim (including unrecognised fields)",
        )
    }

    @Test fun pricedInputsEchoesEvenOnNullCentsBranch() {
        // Sister pin: even when cents is null (no rule matched),
        // pricedInputs still round-trips. Drift to "drop
        // pricedInputs on null cents" would lose the diff hint
        // exactly when the operator most needs it (debugging
        // why the rule didn't match).
        val inputs = buildJsonObject {
            put("toolSpecific", JsonPrimitive("value"))
        }
        val result = runAigcCostEstimateQuery(
            toolId = "totally_unknown_tool",
            providerId = "x",
            modelId = "y",
            inputs = inputs,
        )
        val row = rowsFrom(result.data).single()
        assertEquals(inputs, row.pricedInputs)
    }

    @Test fun rowFieldsEchoToolProviderAndModelLiterally() {
        // Pin: row.toolId / row.providerId / row.modelId echo
        // the inputs verbatim. Drift to lowercase / canonicalise
        // would silently mismatch downstream consumers grouping
        // by these fields.
        val result = runAigcCostEstimateQuery(
            toolId = "GENERATE_IMAGE", // mixed case
            providerId = "OpenAI",
            modelId = "DALL-E-3",
            inputs = imageInputs(),
        )
        val row = rowsFrom(result.data).single()
        assertEquals("GENERATE_IMAGE", row.toolId)
        assertEquals("OpenAI", row.providerId)
        assertEquals("DALL-E-3", row.modelId)
    }
}
