package io.talevia.cli.repl

/**
 * Render `/spendcap` output — the operator-facing view + writer for
 * `Session.spendCapCents`. Three flows the formatter handles:
 *
 *   - **Show** (no args): print the current cap (or "no cap") + the
 *     cumulative session spend pulled from `session_query(select=spend)`.
 *   - **Set** (numeric arg): print "cap was X¢ → now Y¢" with the
 *     previous + new values.
 *   - **Clear** (`clear` arg): print "cap was X¢ → now cleared".
 *
 * USD ↔ cents conversion lives in the dispatcher (the operator types
 * dollars; the field stores cents). The formatter receives raw
 * `Long?` cents on the in / out sides so cents truncation and the
 * "¢ vs $" rendering decision sits here in one place.
 *
 * Pure function — same shape as `formatProjectsTable` /
 * `formatToolsTable` / `formatForksTree`. Tests substitute fixed
 * inputs.
 */
internal fun formatSpendCapStatus(
    currentCapCents: Long?,
    currentSpentCents: Long,
): String {
    val capLine = if (currentCapCents == null) {
        "${Styles.accent("spend cap")} ${Styles.meta("no cap set")} — every paid AIGC call goes through unchecked"
    } else {
        val pct = if (currentCapCents > 0L) {
            (currentSpentCents.toDouble() / currentCapCents.toDouble() * 100.0).coerceAtLeast(0.0)
        } else {
            // capCents == 0 means "spend nothing" — every AIGC call
            // ASKs, so the percent ratio is undefined. Show the raw
            // numbers; the agent sees the 0¢ cap and stops.
            0.0
        }
        "${Styles.accent("spend cap")} ${formatCents(currentCapCents)} " +
            "${Styles.meta("(spent ${formatCents(currentSpentCents)} = ${formatPercent(pct)})")}"
    }
    return capLine
}

/**
 * After a `/spendcap <usd>` or `/spendcap clear` mutation, render the
 * before/after diff so the operator sees what flipped.
 */
internal fun formatSpendCapMutation(
    previousCapCents: Long?,
    newCapCents: Long?,
): String {
    val previous = previousCapCents?.let { formatCents(it) } ?: "no cap"
    val next = newCapCents?.let { formatCents(it) } ?: Styles.meta("cleared")
    val verb = when {
        previousCapCents == null && newCapCents != null -> "set"
        previousCapCents != null && newCapCents == null -> "cleared"
        previousCapCents == newCapCents -> "unchanged"
        else -> "updated"
    }
    return "${Styles.ok("✓")} spend cap $verb${Styles.meta(": $previous → ")}$next"
}

private fun formatCents(cents: Long): String {
    // Accent the dollar form and tail with raw cents so the operator
    // can copy-paste the numeric value back into a tool call.
    val dollars = cents / 100.0
    return "${Styles.accent("$" + "%.2f".format(dollars))} ${Styles.meta("(${cents}¢)")}"
}

private fun formatPercent(pct: Double): String =
    if (pct >= 100.0) {
        // Don't lie — over-cap is possible if a single call's cost
        // overshoots the remaining budget.
        "%.0f%%".format(pct)
    } else {
        "%.0f%%".format(pct)
    }
