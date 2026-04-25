package io.talevia.cli.repl

import io.talevia.core.tool.builtin.session.query.CompactionRow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Render `/summary` output — the latest compaction's summary text for
 * the active session. Source is `session_query(select=compactions)`;
 * this is the eyeball-friendly shape for one row.
 *
 * Format:
 *
 * ```
 * compaction · HH:mm:ss · fromMsg → toMsg
 *
 * <summary body>
 * ```
 *
 * When the session has never been compacted (most common state for
 * fresh sessions) we print a short hint instead so an operator who
 * runs `/summary` early doesn't see a blank screen and wonder if the
 * command failed silently.
 */
internal fun formatCompactionSummary(rows: List<CompactionRow>): String {
    if (rows.isEmpty()) {
        return Styles.meta("no compactions on this session yet — /summary surfaces the latest compactor pass once one fires (auto at the per-model token threshold or via /compact)")
    }
    val row = rows.first()
    val time = formatLocalTime(row.compactedAtEpochMs)
    val fromTail = row.fromMessageId.takeLast(8)
    val toTail = row.toMessageId.takeLast(8)
    val header = "${Styles.accent("compaction")} ${Styles.meta(time)} · " +
        "${Styles.meta(fromTail)} → ${Styles.meta(toTail)}"
    return buildString {
        appendLine(header)
        appendLine()
        append(row.summaryText.trimEnd())
    }
}

private fun formatLocalTime(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    val ss = local.second.toString().padStart(2, '0')
    return "$hh:$mm:$ss"
}
