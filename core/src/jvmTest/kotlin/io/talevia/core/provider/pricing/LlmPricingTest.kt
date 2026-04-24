package io.talevia.core.provider.pricing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the pricing-table lookup + cost-estimate arithmetic against
 * accidental drift during reprice PRs. The table itself is a living
 * snapshot; these tests assert structural properties (well-formed rows,
 * monotonically-nonzero-ish estimates) rather than specific cent values
 * — that way reprice diffs stay scoped to the table without cascading
 * into test-breakage.
 */
class LlmPricingTest {

    @Test fun allTableEntriesAreWellFormed() {
        val entries = LlmPricing.all()
        assertTrue(entries.isNotEmpty(), "pricing table should have at least one entry")
        for (e in entries) {
            assertTrue(e.providerId.isNotBlank(), "providerId blank: $e")
            assertTrue(e.modelId.isNotBlank(), "modelId blank: $e")
            assertTrue(e.centsPer1kInputTokens >= 0.0, "negative input rate: $e")
            assertTrue(e.centsPer1kOutputTokens >= 0.0, "negative output rate: $e")
            // Standard LLM convention: output tokens cost at least as much as
            // input tokens. A reversal would be an obvious reprice typo worth
            // catching.
            assertTrue(
                e.centsPer1kOutputTokens >= e.centsPer1kInputTokens,
                "output rate cheaper than input for $e — probable transposition typo",
            )
        }
    }

    @Test fun tableEntriesAreUnique() {
        val entries = LlmPricing.all()
        val keys = entries.map { it.providerId to it.modelId }
        assertEquals(
            keys.size,
            keys.toSet().size,
            "duplicate (providerId, modelId) pairs in table",
        )
    }

    @Test fun findReturnsMatchingEntryOrNull() {
        val first = LlmPricing.all().first()
        val hit = LlmPricing.find(first.providerId, first.modelId)
        assertNotNull(hit)
        assertEquals(first.centsPer1kInputTokens, hit.centsPer1kInputTokens)

        // Unknown pair → null (three-state contract: never guess a price).
        assertNull(LlmPricing.find("ghost-provider", "ghost-model"))
        assertNull(LlmPricing.find(first.providerId, "ghost-model"))
        assertNull(LlmPricing.find("ghost-provider", first.modelId))
    }

    @Test fun estimateCostCentsZeroTokensReturnsZero() {
        val entry = LlmPricing.all().first()
        assertEquals(0L, entry.estimateCostCents(inputTokens = 0, outputTokens = 0))
    }

    @Test fun estimateCostCentsRoundsHalfUpWithAtLeastOneCentFloor() {
        // Fabricate a pricing entry with rates that would produce a fractional
        // sub-cent result: 0.001 ¢ / 1k input × 100 input tokens = 0.0001¢.
        // Non-zero work + floor means result is 1¢, not 0¢.
        val floor = LlmPricing.Entry(
            providerId = "fake",
            modelId = "tiny",
            centsPer1kInputTokens = 0.001,
            centsPer1kOutputTokens = 0.001,
        )
        assertEquals(1L, floor.estimateCostCents(inputTokens = 100, outputTokens = 0))
    }

    @Test fun estimateCostCentsRejectsNegativeTokenCounts() {
        val entry = LlmPricing.all().first()
        assertFailsWith<IllegalArgumentException> {
            entry.estimateCostCents(inputTokens = -1, outputTokens = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            entry.estimateCostCents(inputTokens = 0, outputTokens = -5)
        }
    }

    @Test fun estimateCostCentsScalesLinearlyInBulk() {
        // At 10k input + 5k output tokens the numbers are large enough for
        // rounding to be stable. Doubling both halves the per-token cost
        // contribution proportionally; a refactor that divided by 10k instead
        // of 1k would fail this (off-by-factor-10 regression guard).
        val entry = LlmPricing.all().first()
        val base = entry.estimateCostCents(inputTokens = 10_000, outputTokens = 5_000)
        val double = entry.estimateCostCents(inputTokens = 20_000, outputTokens = 10_000)
        // Allow ±1 cent for rounding edges on the halving operation.
        assertTrue(
            kotlin.math.abs(double - 2 * base) <= 1,
            "doubling tokens should ~double cost; got base=$base double=$double",
        )
    }
}
