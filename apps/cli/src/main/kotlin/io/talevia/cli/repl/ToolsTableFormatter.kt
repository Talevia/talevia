package io.talevia.cli.repl

import io.talevia.core.tool.builtin.meta.ListToolsTool

/**
 * Render `/tools` output — the LLM tool catalog the operator can
 * inspect from the CLI. Complements `/help` (which lists slash
 * commands, not LLM tools).
 *
 * Pulls the rows from `ListToolsTool.execute` then prints id +
 * permission + a truncated helpText for each. Optional prefix filter
 * scopes the view ("show me every `generate_*`"); empty prefix
 * lists everything.
 *
 * Cost hint (`avgCostCents`) is included on rows where the runtime's
 * [io.talevia.core.metrics.MetricsRegistry] has recorded priced
 * AIGC calls — non-AIGC tools render with no cost column. Sorting:
 * id ascending so the catalog is reproducible across runs.
 *
 * Pure formatter — same shape as [formatProjectsTable] /
 * [formatForksTree]. The dispatcher passes a deterministic list;
 * tests substitute fixed `Summary` rows.
 */
internal fun formatToolsTable(
    summaries: List<ListToolsTool.Summary>,
    prefix: String? = null,
): String {
    val scoped = if (prefix.isNullOrBlank()) summaries else summaries.filter { it.id.startsWith(prefix) }
    if (scoped.isEmpty()) {
        val tail = if (prefix.isNullOrBlank()) "" else " matching `$prefix`"
        return Styles.meta(
            "no LLM tools registered$tail — every container's `DefaultBuiltinRegistrations` " +
                "should wire at least the project / session / fs surface, so an empty list " +
                "usually points at a container-init failure.",
        )
    }
    val sorted = scoped.sortedBy { it.id }
    val idWidth = sorted.maxOf { it.id.length }
    val permWidth = sorted.maxOf { it.permission.length }.coerceAtMost(PERMISSION_DISPLAY_CHARS)
    val scopeNote = if (prefix.isNullOrBlank()) "" else " filtered by prefix `$prefix`"
    val header = "${Styles.accent("tools")} ${sorted.size} of ${summaries.size} tool(s)$scopeNote"
    return buildString {
        appendLine(header)
        for (s in sorted) {
            val help = s.helpText.take(HELP_DISPLAY_CHARS).let {
                if (s.helpText.length > HELP_DISPLAY_CHARS) "$it…" else it
            }
            val cost = s.avgCostCents?.let { "  ${Styles.meta("~${it}¢/call")}" } ?: ""
            val perm = s.permission.take(PERMISSION_DISPLAY_CHARS).padEnd(permWidth)
            appendLine(
                "  ${Styles.accent(s.id.padEnd(idWidth))}  ${Styles.meta(perm)}  $help$cost",
            )
        }
    }.trimEnd()
}

private const val HELP_DISPLAY_CHARS = 80
private const val PERMISSION_DISPLAY_CHARS = 28
