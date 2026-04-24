package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.JsonConfig
import io.talevia.core.provider.pricing.LlmPricing
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * One row per priced (providerId, modelId) pair, with the cent estimate
 * scaled to the caller's requested token budget. Sorted ascending on
 * [estimatedCostCents] so `rows.first()` is the cheapest option, matching
 * the VISION §5.2 "generate_image vs dall-e-3" style tradeoff the agent
 * needs to navigate.
 */
@Serializable
data class CostCompareRow(
    val providerId: String,
    val modelId: String,
    val centsPer1kInputTokens: Double,
    val centsPer1kOutputTokens: Double,
    /** Rolled-up cents for the requested `(inputTokens, outputTokens)` pair. */
    val estimatedCostCents: Long,
)

/**
 * `select=cost_compare` — return every priced (provider, model) pair in
 * [LlmPricing] with per-1k token rates + a cents estimate for the
 * requested `(requestedInputTokens, requestedOutputTokens)` budget.
 * Sorted ascending on estimatedCostCents; rows.first() is the cheapest.
 *
 * No HTTP — this is pure local table lookup. The table is a best-effort
 * 2026-04 snapshot (see [LlmPricing]'s docstring for the drift caveat).
 * Callers that need invoice-accurate numbers should cross-check with
 * the provider's console, but for planning-time "cheaper or pricier?"
 * questions the snapshot is stable enough.
 */
internal fun runCostCompareQuery(
    requestedInputTokens: Int,
    requestedOutputTokens: Int,
): ToolResult<ProviderQueryTool.Output> {
    require(requestedInputTokens >= 0) {
        "requestedInputTokens must be >= 0 (was $requestedInputTokens)"
    }
    require(requestedOutputTokens >= 0) {
        "requestedOutputTokens must be >= 0 (was $requestedOutputTokens)"
    }

    val rows = LlmPricing.all()
        .map { entry ->
            CostCompareRow(
                providerId = entry.providerId,
                modelId = entry.modelId,
                centsPer1kInputTokens = entry.centsPer1kInputTokens,
                centsPer1kOutputTokens = entry.centsPer1kOutputTokens,
                estimatedCostCents = entry.estimateCostCents(
                    inputTokens = requestedInputTokens,
                    outputTokens = requestedOutputTokens,
                ),
            )
        }
        .sortedWith(
            compareBy<CostCompareRow> { it.estimatedCostCents }
                .thenBy { it.providerId }
                .thenBy { it.modelId },
        )

    val summary = when {
        rows.isEmpty() ->
            "LLM pricing table is empty — no models to compare."
        else -> {
            val cheapest = rows.first()
            val pricey = rows.last()
            "${rows.size} priced models for (in=$requestedInputTokens, out=$requestedOutputTokens) — " +
                "cheapest ${cheapest.providerId}/${cheapest.modelId} @ ${cheapest.estimatedCostCents}¢, " +
                "most expensive ${pricey.providerId}/${pricey.modelId} @ ${pricey.estimatedCostCents}¢."
        }
    }

    val encoded = JsonConfig.default.encodeToJsonElement(
        ListSerializer(CostCompareRow.serializer()),
        rows,
    ) as JsonArray
    return ToolResult(
        title = "provider_query cost_compare (${rows.size})",
        outputForLlm = summary,
        data = ProviderQueryTool.Output(
            select = ProviderQueryTool.SELECT_COST_COMPARE,
            total = rows.size,
            returned = rows.size,
            rows = encoded,
        ),
    )
}
