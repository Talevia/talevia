package io.talevia.cli.repl

import io.talevia.core.tool.builtin.session.query.SessionSpendByProviderRow
import io.talevia.core.tool.builtin.session.query.SessionSpendSummaryRow
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-function coverage for [formatSpendSummary]. ANSI escapes are
 * disabled via [Styles.setEnabled] so assertions read the plain-text
 * payload without regex-stripping.
 */
class SpendSummaryFormatterTest {

    @BeforeTest fun disableAnsi() {
        Styles.setEnabled(false)
    }

    @Test fun zeroCallsEmitsNoActivitySentinel() {
        val row = SessionSpendSummaryRow(
            sessionId = "s",
            projectId = "p",
            totalCalls = 0,
            projectResolved = true,
        )
        val out = formatSpendSummary(row)
        assertEquals("no AIGC calls yet in this session", out)
    }

    @Test fun zeroCallsWithUnresolvedProjectFlagsBindingGap() {
        val row = SessionSpendSummaryRow(
            sessionId = "s",
            projectId = "p",
            totalCalls = 0,
            projectResolved = false,
        )
        val out = formatSpendSummary(row)
        assertTrue(
            "(project not bound to this session)" in out,
            "unresolved projectResolved must surface binding hint: <$out>",
        )
    }

    @Test fun knownCostRendersDollarAmount() {
        val row = SessionSpendSummaryRow(
            sessionId = "s",
            projectId = "p",
            totalCalls = 3,
            estimatedUsdCents = 42.3,
            perProviderBreakdown = listOf(
                SessionSpendByProviderRow(providerId = "openai", calls = 3, usdCents = 42.3),
            ),
        )
        val out = formatSpendSummary(row)
        // $42.3 cents = $0.4230 dollars
        assertTrue("\$0.4230" in out, "header must show dollar total: <$out>")
        assertTrue("AIGC spend 3 call(s)" in out, "header count: <$out>")
        assertTrue("openai" in out, "breakdown includes providerId: <$out>")
    }

    @Test fun mixedKnownAndUnknownCostsShowBothFigures() {
        val row = SessionSpendSummaryRow(
            sessionId = "s",
            projectId = "p",
            totalCalls = 5,
            estimatedUsdCents = 100.0,
            unknownCostCalls = 2,
            perProviderBreakdown = listOf(
                SessionSpendByProviderRow(
                    providerId = "replicate",
                    calls = 5,
                    usdCents = 100.0,
                    unknownCalls = 2,
                ),
            ),
        )
        val out = formatSpendSummary(row)
        assertTrue("\$1.0000" in out, "header: <$out>")
        assertTrue("(+ 2 unpriced)" in out, "header must name unpriced subtotal: <$out>")
        assertTrue("(+2 unpriced)" in out, "per-provider row must name unpriced subtotal: <$out>")
    }

    @Test fun allUnknownCostsShowsUsdQuestionMark() {
        val row = SessionSpendSummaryRow(
            sessionId = "s",
            projectId = "p",
            totalCalls = 2,
            estimatedUsdCents = null,
            unknownCostCalls = 2,
            perProviderBreakdown = listOf(
                SessionSpendByProviderRow(
                    providerId = "mystery",
                    calls = 2,
                    usdCents = null,
                    unknownCalls = 2,
                ),
            ),
        )
        val out = formatSpendSummary(row)
        assertTrue("usd=?" in out, "null total USD must render as usd=?: <$out>")
        assertTrue("\$?" in out, "per-provider null USD must render as \$?: <$out>")
        assertFalse("\$0.0000" in out, "null must NOT render as \$0.0000: <$out>")
    }

    @Test fun multipleProvidersRenderOneLineEach() {
        val row = SessionSpendSummaryRow(
            sessionId = "s",
            projectId = "p",
            totalCalls = 5,
            estimatedUsdCents = 80.0,
            perProviderBreakdown = listOf(
                SessionSpendByProviderRow("openai", 2, usdCents = 30.0),
                SessionSpendByProviderRow("replicate", 3, usdCents = 50.0),
            ),
        )
        val out = formatSpendSummary(row)
        val lines = out.lineSequence().filter { it.isNotBlank() }.toList()
        // Header + two provider rows.
        assertEquals(3, lines.size, "expected 3 lines (header + 2 providers); got $out")
        assertTrue("openai" in lines[1] && "\$0.3000" in lines[1])
        assertTrue("replicate" in lines[2] && "\$0.5000" in lines[2])
    }
}
