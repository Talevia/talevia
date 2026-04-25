package io.talevia.cli.repl

import io.talevia.core.tool.builtin.session.query.BusTraceRow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Render `/trace` output — table of recent bus events for the active
 * session. Source is `session_query(select=bus_trace)`; this is the
 * eyeball-friendly shape over its row.
 *
 * Format: HH:mm:ss + kind + truncated summary, oldest first (matches
 * the recorder's natural insertion order). When a `kind=…` filter is
 * applied, the header reflects it so an empty result is unambiguous
 * ("no PartDelta events" vs "no events at all").
 */
internal fun formatBusTrace(
    rows: List<BusTraceRow>,
    kindFilter: String?,
): String {
    val filterTail = kindFilter?.let { " ${Styles.meta("(kind=$it)")}" } ?: ""
    if (rows.isEmpty()) {
        return Styles.meta("no bus events recorded for this session yet$filterTail")
    }
    val kindWidth = (rows.maxOfOrNull { it.kind.length } ?: 0).coerceAtLeast(10)
    val header = "${Styles.accent("trace")} ${rows.size} event(s)$filterTail"
    return buildString {
        appendLine(header)
        rows.forEach { r ->
            val time = formatLocalTime(r.epochMs)
            val kindCol = r.kind.padEnd(kindWidth)
            val summary = r.summary.replace("\n", " ").take(SUMMARY_DISPLAY_CHARS)
            appendLine("  ${Styles.meta(time)}  ${Styles.accent(kindCol)}  $summary")
        }
    }.trimEnd()
}

private const val SUMMARY_DISPLAY_CHARS = 100

private fun formatLocalTime(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    val ss = local.second.toString().padStart(2, '0')
    return "$hh:$mm:$ss"
}
