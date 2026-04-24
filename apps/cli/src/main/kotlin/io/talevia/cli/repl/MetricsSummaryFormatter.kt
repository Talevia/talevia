package io.talevia.cli.repl

import io.talevia.core.metrics.HistogramStats

/**
 * Render an in-process `MetricsRegistry` snapshot as human-readable
 * output for the `/metrics` slash command.
 *
 * Counters are grouped by the first dotted segment (`agent`, `aigc`,
 * `provider`, `permission`, `session`, etc.) so a 50-counter dump stays
 * scannable. Histograms (wall-time timers like `tool.<id>.ms` and
 * `agent.run.ms`) land in their own section with P50 / P95 / P99.
 *
 * Extracted from `SlashCommandDispatcher` so the rendering can be unit-
 * tested without standing up the full `CliContainer`.
 */
internal fun formatMetricsSummary(
    counters: Map<String, Long>,
    histograms: Map<String, HistogramStats>,
): String {
    if (counters.isEmpty() && histograms.isEmpty()) {
        return Styles.meta("no metrics recorded yet (counters + histograms both empty)")
    }

    return buildString {
        if (counters.isNotEmpty()) {
            val groups: Map<String, List<Pair<String, Long>>> = counters.entries
                .map { it.key to it.value }
                .groupBy { (k, _) -> k.substringBefore('.', missingDelimiterValue = k) }
                .toSortedMap()
            appendLine(Styles.meta("counters (${counters.size} total):"))
            val nameWidth = counters.keys.maxOf { it.length }
            groups.forEach { (group, entries) ->
                appendLine("  ${Styles.accent(group)}")
                entries.sortedBy { it.first }.forEach { (name, value) ->
                    appendLine("    ${name.padEnd(nameWidth)}  $value")
                }
            }
        }
        if (histograms.isNotEmpty()) {
            if (counters.isNotEmpty()) appendLine()
            appendLine(Styles.meta("histograms (${histograms.size} total, P50/P95/P99 in ms):"))
            val nameWidth = histograms.keys.maxOf { it.length }
            histograms.toSortedMap().forEach { (name, stats) ->
                appendLine(
                    "  ${name.padEnd(nameWidth)}  n=${stats.count}  " +
                        "p50=${stats.p50}  p95=${stats.p95}  p99=${stats.p99}",
                )
            }
        }
    }.trimEnd()
}
