package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.JsonConfig
import io.talevia.core.cost.AigcPricing
import io.talevia.core.platform.GenerationProvenance
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Row for `select=aigc_cost_estimate` — the agent's plan-time cost
 * answer. Cost-comparison lane sibling of [CostCompareRow] (which scales
 * LLM token rates) for non-LLM AIGC dispatches (image / video / TTS /
 * music / upscale).
 *
 * `cents` is the same three-state value [AigcPricing.estimateCents]
 * already returns: a non-null number = list-price estimate, `null` =
 * "no pricing rule matched, agent must consult provider docs". The
 * priceBasis text matches what `list_tools` shows on Summary so the
 * agent gets a one-line audit of the rule that fired.
 */
@Serializable
data class AigcCostEstimateRow(
    val toolId: String,
    val providerId: String,
    val modelId: String,
    /** Estimated dispatch cost in cents — `null` when no pricing rule matched. */
    val cents: Long?,
    /** Single-line description of the pricing shape (`null` for non-priced tools). */
    val priceBasis: String?,
    /** Echo of the inputs we priced against — useful for diff-style debugging. */
    val pricedInputs: JsonObject,
)

/**
 * `select=aigc_cost_estimate` — plan-time cost estimate for a single
 * AIGC dispatch.
 *
 * Bridges [AigcPricing.estimateCents] (which today only fires
 * post-dispatch from inside the AIGC tools) to the LLM's planning
 * surface. The agent calls this with `(toolId, providerId, modelId,
 * inputs)` and gets back the cents number it would otherwise have to
 * compute by hand from the `priceBasis` string on `list_tools`.
 *
 * Why one row even though the input is a single tuple: the unified
 * `provider_query` output shape is `{select, total, returned, rows}`;
 * keeping the shape stable means the consumer doesn't special-case
 * on `select` to find the data. `rows.first().cents` is what callers
 * read.
 */
internal fun runAigcCostEstimateQuery(
    toolId: String,
    providerId: String,
    modelId: String,
    inputs: JsonObject,
): ToolResult<ProviderQueryTool.Output> {
    val provenance = GenerationProvenance(
        providerId = providerId,
        modelId = modelId,
        modelVersion = null,
        seed = 0L,
        parameters = JsonObject(emptyMap()),
        createdAtEpochMs = 0L,
    )
    val cents = AigcPricing.estimateCents(toolId, provenance, inputs)
    val basis = AigcPricing.priceBasisFor(toolId)

    val row = AigcCostEstimateRow(
        toolId = toolId,
        providerId = providerId,
        modelId = modelId,
        cents = cents,
        priceBasis = basis,
        pricedInputs = inputs,
    )

    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(AigcCostEstimateRow.serializer()),
        listOf(row),
    ) as JsonArray

    val summary = if (cents != null) {
        "Estimate: ${cents}¢ for $toolId on $providerId/$modelId" +
            (basis?.let { " (basis: $it)" } ?: ".")
    } else {
        // The three-state contract: null = "we don't know", not "free".
        "No pricing rule matched for $toolId on $providerId/$modelId. " +
            (basis?.let { "Tool basis: $it. " } ?: "") +
            "Consult the provider's published rates."
    }

    return ToolResult(
        title = "provider_query aigc_cost_estimate ($toolId)",
        outputForLlm = summary,
        data = ProviderQueryTool.Output(
            select = ProviderQueryTool.SELECT_AIGC_COST_ESTIMATE,
            total = 1,
            returned = 1,
            rows = jsonRows,
        ),
    )
}
