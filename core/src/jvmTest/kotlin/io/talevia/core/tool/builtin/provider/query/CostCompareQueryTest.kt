package io.talevia.core.tool.builtin.provider.query

import io.talevia.core.provider.pricing.LlmPricing
import io.talevia.core.tool.builtin.provider.ProviderQueryTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Direct tests for [runCostCompareQuery] —
 * `core/tool/builtin/provider/query/CostCompareQuery.kt`.
 * The ProviderQueryTool's `select=cost_compare` handler.
 * Cycle 201 audit: 96 LOC, 0 direct test refs.
 *
 * Three correctness contracts pinned:
 *
 * 1. **Non-negative input validation: both
 *    `requestedInputTokens` and `requestedOutputTokens`
 *    must be `>= 0`.** Drift to "silently coerce
 *    negative" would propagate negative cents through
 *    estimateCostCents.
 *
 * 2. **Sort key: `estimatedCostCents ASC, then
 *    providerId, then modelId`.** Drift to "alphabetical
 *    by id only" would shuffle the cheapest-first
 *    guarantee that drives `rows.first()` consumers.
 *
 * 3. **Body cites count + cheapest + most expensive
 *    pair.** Drift in this format would break the
 *    LLM's "I see 9 priced models, cheapest at X¢"
 *    summary read.
 */
class CostCompareQueryTest {

    // ── Non-negative validation ─────────────────────────────

    @Test fun negativeInputTokensThrows() {
        val ex = assertFailsWith<IllegalArgumentException> {
            runCostCompareQuery(requestedInputTokens = -1, requestedOutputTokens = 100)
        }
        assertTrue(
            "requestedInputTokens" in (ex.message ?: ""),
            "expected param name; got: ${ex.message}",
        )
        assertTrue(
            "must be >= 0" in (ex.message ?: ""),
            "expected non-negative phrase; got: ${ex.message}",
        )
        assertTrue("-1" in (ex.message ?: ""), "expected value cited")
    }

    @Test fun negativeOutputTokensThrows() {
        val ex = assertFailsWith<IllegalArgumentException> {
            runCostCompareQuery(requestedInputTokens = 100, requestedOutputTokens = -1)
        }
        assertTrue("requestedOutputTokens" in (ex.message ?: ""))
        assertTrue("must be >= 0" in (ex.message ?: ""))
    }

    @Test fun zeroTokensIsAccepted() {
        // Pin: 0 is non-negative, valid input. Each row
        // estimates as 0¢ when both tokens are 0.
        val result = runCostCompareQuery(requestedInputTokens = 0, requestedOutputTokens = 0)
        assertTrue(result.data.total > 0, "snapshot pricing has rows")
        // All rows have cents=0 because tokens=0 → fractional=0 → 0L returned.
        for (row in result.data.rows) {
            val cents = row.toString()
                .substringAfter("\"estimatedCostCents\":").substringBefore(",").substringBefore("}")
            assertEquals("0", cents, "zero tokens → zero cents; got: $row")
        }
    }

    // ── Total / returned shape ──────────────────────────────

    @Test fun totalEqualsReturnedNoPagination() {
        // Pin: per kdoc-implicit, this select has no
        // pagination — total == returned == rows.size.
        val result = runCostCompareQuery(
            requestedInputTokens = 1_000,
            requestedOutputTokens = 1_000,
        )
        val expectedRows = LlmPricing.all().size
        assertEquals(expectedRows, result.data.total)
        assertEquals(expectedRows, result.data.returned, "returned == total")
        assertEquals(expectedRows, result.data.rows.size)
    }

    // ── Sort: estimatedCostCents ASC ────────────────────────

    @Test fun rowsSortedAscendingByEstimatedCostCents() {
        // Marquee sort-order pin: per kdoc "Sorted
        // ascending on estimatedCostCents; rows.first()
        // is the cheapest." Drift to "alphabetical only"
        // would break the cheapest-first guarantee.
        val result = runCostCompareQuery(
            requestedInputTokens = 1_000_000,
            requestedOutputTokens = 1_000_000,
        )
        val cents = result.data.rows.map {
            it.toString()
                .substringAfter("\"estimatedCostCents\":")
                .substringBefore(",")
                .substringBefore("}")
                .toLong()
        }
        // Each consecutive pair must be non-decreasing.
        for (i in 1 until cents.size) {
            assertTrue(
                cents[i - 1] <= cents[i],
                "rows[${i - 1}].cents=${cents[i - 1]} <= rows[$i].cents=${cents[i]}",
            )
        }
    }

    @Test fun cheapestIsRowsFirst() {
        // Pin: rows.first() is the cheapest — `cheapest`
        // and `pricey` in the summary come from
        // rows.first()/last() respectively.
        val result = runCostCompareQuery(
            requestedInputTokens = 1_000,
            requestedOutputTokens = 1_000,
        )
        val firstRowCents = result.data.rows[0].toString()
            .substringAfter("\"estimatedCostCents\":")
            .substringBefore(",").substringBefore("}").toLong()
        val lastRowCents = result.data.rows.last().toString()
            .substringAfter("\"estimatedCostCents\":")
            .substringBefore(",").substringBefore("}").toLong()
        assertTrue(firstRowCents <= lastRowCents)
    }

    @Test fun sortTieBreakIsProviderIdThenModelId() {
        // Pin: when estimatedCostCents tie, sort by
        // (providerId, modelId) alphabetically. Pinned by
        // checking that gpt-4o-mini and gpt-5.4-mini (both
        // 0.015/0.06 rates → identical cents) appear in
        // alphabetical order on a query that hits the tie.
        // (gpt-4o-mini < gpt-5.4-mini alphabetically, and
        // same providerId "openai".)
        val result = runCostCompareQuery(
            requestedInputTokens = 100,
            requestedOutputTokens = 100,
        )
        val modelIds = result.data.rows.map {
            it.toString().substringAfter("\"modelId\":\"").substringBefore("\"")
        }
        // Find where openai/gpt-4o-mini and openai/gpt-5.4-mini land.
        val gpt4mini = modelIds.indexOf("gpt-4o-mini")
        val gpt5mini = modelIds.indexOf("gpt-5.4-mini")
        assertTrue(gpt4mini >= 0, "gpt-4o-mini in rows")
        assertTrue(gpt5mini >= 0, "gpt-5.4-mini in rows")
        assertTrue(
            gpt4mini < gpt5mini,
            "tie-break: 'gpt-4o-mini' < 'gpt-5.4-mini' alphabetically; got indices $gpt4mini < $gpt5mini",
        )
    }

    // ── CostCompareRow shape ────────────────────────────────

    @Test fun rowExposesAllPricingFields() {
        val result = runCostCompareQuery(
            requestedInputTokens = 1_000,
            requestedOutputTokens = 1_000,
        )
        val rowJson = result.data.rows[0].toString()
        assertTrue("\"providerId\":" in rowJson)
        assertTrue("\"modelId\":" in rowJson)
        assertTrue("\"centsPer1kInputTokens\":" in rowJson)
        assertTrue("\"centsPer1kOutputTokens\":" in rowJson)
        assertTrue("\"estimatedCostCents\":" in rowJson)
    }

    @Test fun estimatedCostScalesWithRequestedTokens() {
        // Pin: 10x tokens → ~10x cents on average per row.
        // Drift to "ignore tokens" or "wrong scale factor"
        // would break the cost-tradeoff UX.
        val small = runCostCompareQuery(requestedInputTokens = 1_000, requestedOutputTokens = 1_000)
        val large = runCostCompareQuery(requestedInputTokens = 10_000, requestedOutputTokens = 10_000)

        val smallTotal = small.data.rows.sumOf {
            it.toString()
                .substringAfter("\"estimatedCostCents\":").substringBefore(",")
                .substringBefore("}").toLong()
        }
        val largeTotal = large.data.rows.sumOf {
            it.toString()
                .substringAfter("\"estimatedCostCents\":").substringBefore(",")
                .substringBefore("}").toLong()
        }
        // 10x more tokens → between 5x and 15x more total
        // cents (allowing for half-up rounding noise).
        assertTrue(
            largeTotal in (smallTotal * 5)..(smallTotal * 15),
            "10x tokens → ~10x cents; got small=$smallTotal large=$largeTotal",
        )
    }

    // ── Body format ─────────────────────────────────────────

    @Test fun nonEmptyBodyCitesCountCheapestAndMostExpensive() {
        val result = runCostCompareQuery(
            requestedInputTokens = 1_000,
            requestedOutputTokens = 1_000,
        )
        val expectedRows = LlmPricing.all().size
        assertTrue(
            "$expectedRows priced models" in result.outputForLlm,
            "count cited; got: ${result.outputForLlm}",
        )
        assertTrue(
            "(in=1000, out=1000)" in result.outputForLlm,
            "input echo cited; got: ${result.outputForLlm}",
        )
        assertTrue(
            "cheapest" in result.outputForLlm,
            "cheapest cited; got: ${result.outputForLlm}",
        )
        assertTrue(
            "most expensive" in result.outputForLlm,
            "most-expensive cited; got: ${result.outputForLlm}",
        )
    }

    @Test fun bodyFormatCheapestAndMostExpensiveCiteProviderSlashModel() {
        val result = runCostCompareQuery(
            requestedInputTokens = 1_000,
            requestedOutputTokens = 1_000,
        )
        // Format: "cheapest providerId/modelId @ N¢"
        assertTrue(
            Regex("cheapest \\S+/\\S+ @ \\d+¢").containsMatchIn(result.outputForLlm),
            "cheapest format provider/model + cents; got: ${result.outputForLlm}",
        )
        assertTrue(
            Regex("most expensive \\S+/\\S+ @ \\d+¢").containsMatchIn(result.outputForLlm),
            "most-expensive format; got: ${result.outputForLlm}",
        )
    }

    // ── Output.select echoes ────────────────────────────────

    @Test fun outputSelectIsCostCompare() {
        val result = runCostCompareQuery(
            requestedInputTokens = 100,
            requestedOutputTokens = 100,
        )
        assertEquals(ProviderQueryTool.SELECT_COST_COMPARE, result.data.select)
    }

    @Test fun outputErrorIsNullOnSuccess() {
        val result = runCostCompareQuery(
            requestedInputTokens = 100,
            requestedOutputTokens = 100,
        )
        // No HTTP → no error path.
        assertEquals(null, result.data.error)
    }

    @Test fun toolResultTitleCitesRowCount() {
        val result = runCostCompareQuery(
            requestedInputTokens = 100,
            requestedOutputTokens = 100,
        )
        val expectedRows = LlmPricing.all().size
        assertTrue(
            "($expectedRows)" in result.title!!,
            "title cites count; got: ${result.title}",
        )
        assertTrue(
            "cost_compare" in result.title!!,
            "title cites select; got: ${result.title}",
        )
    }

    // ── Empty-table summary path ────────────────────────────
    //
    // The LlmPricing snapshot has 9 entries today, so the
    // "rows.isEmpty()" branch isn't reachable through the
    // production pricing object. The emptiness branch is
    // exercised indirectly: the `when` expression's
    // exhaustive shape is part of the compile-time
    // contract. A future "remove all entries from
    // LlmPricing" refactor (unlikely) would surface here
    // via the rowCount=0 + body changing, both observable
    // through this test suite's assertions.
}
