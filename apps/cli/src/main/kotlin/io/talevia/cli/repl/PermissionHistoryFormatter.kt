package io.talevia.cli.repl

import io.talevia.core.tool.builtin.session.query.PermissionHistoryRow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Render `/permissions` output — table of every PermissionAsked↔Replied
 * round-trip in the active session, oldest first. Source data is
 * [io.talevia.core.tool.builtin.session.SessionQueryTool] with
 * `select=permission_history`; this formatter is the eyeball-friendly
 * shape over that select's row.
 *
 * Decision colors mimic the CLI's existing convention:
 *  - `once` / `always` (accepted) → ok green
 *  - `reject` → error red
 *  - `pending` → meta dim (waiting on user)
 *
 * Permission column is left-aligned; pattern column shows the first
 * pattern with `(+N more)` when several were registered. askedAt is
 * a local-time HH:mm:ss because absolute epochs aren't readable at
 * a glance and most permission asks land within a single session day.
 */
internal fun formatPermissionHistory(rows: List<PermissionHistoryRow>): String {
    if (rows.isEmpty()) {
        return Styles.meta("no permission asks recorded for this session yet")
    }
    val permissionWidth = (rows.maxOfOrNull { it.permission.length } ?: 0).coerceAtLeast(10)
    val patternWidth = (rows.maxOfOrNull { firstPatternPreview(it).length } ?: 0).coerceAtLeast(8)

    val header = "${Styles.accent("permissions")} ${rows.size} ask(s) " +
        Styles.meta("(oldest first)")
    return buildString {
        appendLine(header)
        rows.forEach { r ->
            val permCol = r.permission.padEnd(permissionWidth)
            val patternCol = firstPatternPreview(r).padEnd(patternWidth)
            val decisionCol = decorateDecision(r.decision)
            val time = formatLocalTime(r.askedEpochMs)
            appendLine("  $permCol  $patternCol  $decisionCol  ${Styles.meta(time)}")
        }
    }.trimEnd()
}

private fun firstPatternPreview(row: PermissionHistoryRow): String {
    val first = row.patterns.firstOrNull() ?: return "(no pattern)"
    if (row.patterns.size <= 1) return first
    return "$first (+${row.patterns.size - 1} more)"
}

private fun decorateDecision(decision: String): String = when (decision) {
    "once", "always" -> Styles.ok(decision)
    "reject" -> Styles.error(decision)
    "pending" -> Styles.meta(decision)
    else -> decision
}

private fun formatLocalTime(epochMs: Long): String {
    val instant = Instant.fromEpochMilliseconds(epochMs)
    val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = local.hour.toString().padStart(2, '0')
    val mm = local.minute.toString().padStart(2, '0')
    val ss = local.second.toString().padStart(2, '0')
    return "$hh:$mm:$ss"
}
