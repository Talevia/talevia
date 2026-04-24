package io.talevia.cli.repl

import io.talevia.core.tool.builtin.session.query.SessionSpendSummaryRow

/**
 * Render a [SessionSpendSummaryRow] as the `/spend` command's terminal
 * output. Extracted from [SlashCommandDispatcher] so the formatting can
 * be unit-tested without standing up a full `CliContainer` fixture.
 *
 * Shape:
 * ```
 * AIGC spend 7 call(s) · $0.4230 (+ 2 unpriced)
 *   openai   3 call(s)  ·  $0.2100
 *   replicate 4 call(s) ·  $0.2130 (+2 unpriced)
 * ```
 *
 * - Zero-total row → "no AIGC calls yet" sentinel. Adds "(project not
 *   bound to this session)" when `projectResolved=false`.
 * - `estimatedUsdCents == null` across all entries → `usd=?` instead of
 *   `$`. Mixed-known buckets render the known partial and flag
 *   `(+N unpriced)`.
 * - Breakdown is one line per provider, columns aligned by padding the
 *   providerId label — cheap to read when the registry has 2-4 providers.
 */
internal fun formatSpendSummary(row: SessionSpendSummaryRow): String {
    if (row.totalCalls == 0) {
        val projectHint = if (row.projectResolved) "" else " (project not bound to this session)"
        return Styles.meta("no AIGC calls yet in this session$projectHint")
    }
    val totalUsd = row.estimatedUsdCents?.let { "%.4f".format(it / 100.0) }
    val unknownSuffix = if (row.unknownCostCalls > 0) {
        Styles.meta(" (+ ${row.unknownCostCalls} unpriced)")
    } else {
        ""
    }
    val header = "${Styles.accent("AIGC spend")} ${row.totalCalls} call(s)" +
        (if (totalUsd != null) " · ${Styles.accent("\$$totalUsd")}" else " · usd=?") +
        unknownSuffix
    if (row.perProviderBreakdown.isEmpty()) return header
    return buildString {
        appendLine(header)
        row.perProviderBreakdown.forEach { p ->
            val usd = p.usdCents?.let { "%.4f".format(it / 100.0) } ?: "?"
            val unknown = if (p.unknownCalls > 0) Styles.meta(" (+${p.unknownCalls} unpriced)") else ""
            appendLine(
                "  ${Styles.toolId(p.providerId)}  ${p.calls} call(s)  " +
                    Styles.meta("·") + " \$$usd$unknown",
            )
        }
    }.trimEnd()
}
