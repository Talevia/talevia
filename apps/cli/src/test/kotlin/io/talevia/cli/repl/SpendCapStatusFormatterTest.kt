package io.talevia.cli.repl

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for [formatSpendCapStatus] and [formatSpendCapMutation] —
 * the `/spendcap` slash command's pure-function renderers. Tests
 * substitute deterministic cents values.
 */
class SpendCapStatusFormatterTest {

    private fun stripAnsi(s: String): String =
        s.replace(Regex("\\u001B\\[[0-9;?]*[A-Za-z]"), "")

    @Test fun statusWithNoCapHintsThatAigcIsUnchecked() {
        Styles.setEnabled(false)
        val out = stripAnsi(formatSpendCapStatus(currentCapCents = null, currentSpentCents = 250L))
        assertTrue("no cap set" in out, "missing the no-cap hint; got: $out")
        assertTrue("unchecked" in out, "operator should know AIGC bypasses budget gate when unset")
    }

    @Test fun statusWithCapShowsDollarFormAndCentsTail() {
        Styles.setEnabled(false)
        val out = stripAnsi(formatSpendCapStatus(currentCapCents = 500L, currentSpentCents = 125L))
        // Dollar form is the operator-friendly readout.
        assertTrue("$5.00" in out, "missing dollar form; got: $out")
        // Cents tail lets the operator copy-paste the cents back into a tool call.
        assertTrue("500¢" in out, "missing cents tail; got: $out")
        assertTrue("$1.25" in out, "spent dollar form missing")
        assertTrue("125¢" in out, "spent cents tail missing")
        assertTrue("25%" in out, "percent should be 125/500 = 25%; got: $out")
    }

    @Test fun statusOver100PercentRendersHonestly() {
        // If a single AIGC call overshoots remaining budget, spent
        // can exceed cap. Don't clamp the percent — the operator
        // needs to see the over-cap state.
        Styles.setEnabled(false)
        val out = stripAnsi(formatSpendCapStatus(currentCapCents = 100L, currentSpentCents = 175L))
        assertTrue("175%" in out, "over-cap percent must be honest; got: $out")
    }

    @Test fun zeroCapShowsZeroPercent() {
        // capCents == 0 means "every paid AIGC call ASKs". The percent
        // ratio is undefined (div-by-zero); render 0% rather than NaN
        // / Infinity to keep the operator's view sane.
        Styles.setEnabled(false)
        val out = stripAnsi(formatSpendCapStatus(currentCapCents = 0L, currentSpentCents = 50L))
        assertTrue("$0.00" in out, "zero cap should render dollar form 0.00")
        assertTrue("(0¢)" in out, "zero cap should render cents tail 0¢")
        // 50¢ spent, ratio undefined → 0% (safe sentinel; the cents
        // tail tells the truth about actual spend).
        assertTrue("0%" in out)
    }

    @Test fun mutationFromNullToValueIsSet() {
        Styles.setEnabled(false)
        val out = stripAnsi(formatSpendCapMutation(previousCapCents = null, newCapCents = 500L))
        assertTrue("set" in out, "verb mismatch; got: $out")
        assertTrue("no cap" in out, "previous side should say `no cap`")
        assertTrue("$5.00" in out, "new side should show dollar form")
    }

    @Test fun mutationFromValueToNullIsCleared() {
        Styles.setEnabled(false)
        val out = stripAnsi(formatSpendCapMutation(previousCapCents = 500L, newCapCents = null))
        assertTrue("cleared" in out, "verb mismatch; got: $out")
        assertTrue("$5.00" in out, "previous side should show dollar form")
    }

    @Test fun mutationSameValueIsUnchanged() {
        // Idempotent setter — no-op semantics matter so the operator
        // doesn't worry that re-issuing the command silently changed
        // state.
        Styles.setEnabled(false)
        val out = stripAnsi(formatSpendCapMutation(previousCapCents = 500L, newCapCents = 500L))
        assertTrue("unchanged" in out, "verb mismatch; got: $out")
        assertFalse("set" in out.substringAfter("✓"), "must not say `set`")
    }

    @Test fun mutationFromValueToOtherValueIsUpdated() {
        Styles.setEnabled(false)
        val out = stripAnsi(formatSpendCapMutation(previousCapCents = 250L, newCapCents = 500L))
        assertTrue("updated" in out, "verb mismatch; got: $out")
        assertTrue("$2.50" in out, "previous dollar form")
        assertTrue("$5.00" in out, "new dollar form")
    }
}
