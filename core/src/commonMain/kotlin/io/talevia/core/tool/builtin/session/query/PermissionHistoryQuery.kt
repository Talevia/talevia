package io.talevia.core.tool.builtin.session.query

import io.talevia.core.permission.PermissionHistoryRecorder
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * One permission round-trip captured by
 * [PermissionHistoryRecorder]. `decision` is `"once"` / `"always"` /
 * `"reject"` for replied entries, `"pending"` for asks still awaiting
 * a reply (the operator hasn't answered yet).
 *
 * The `accepted` boolean is the same one [BusEvent.PermissionReplied]
 * carries — true for `Once` / `Always`, false for `Reject`. Surfacing
 * both `decision` and `accepted` lets the agent answer the two common
 * questions ("did the user say no?" and "did the user say always?")
 * with one read.
 */
@Serializable
data class PermissionHistoryRow(
    val requestId: String,
    val permission: String,
    val patterns: List<String>,
    val decision: String,
    val accepted: Boolean?,
    val remembered: Boolean?,
    val askedEpochMs: Long,
    val repliedEpochMs: Long?,
)

/**
 * `select=permission_history` — every permission round-trip the
 * recorder has captured for one session, oldest-first. The agent
 * reads this to remember "I already asked the user about
 * `network.fetch:*` and they said no — don't ask again" within the
 * same process lifetime.
 *
 * Data source is the in-memory [PermissionHistoryRecorder] wired into
 * each container's composition root from the same bus
 * [io.talevia.core.permission.DefaultPermissionService] publishes
 * on. When the recorder isn't wired (test rigs without permission
 * UX), the query reports zero rows rather than failing — same shape
 * as `select=run_failure` / `select=fallback_history` when their
 * trackers aren't wired.
 *
 * Process-scoped: cross-restart history would require persisting
 * permissions to the SessionStore, which is intentionally out of
 * scope (see [PermissionHistoryRecorder] kdoc).
 */
internal fun runPermissionHistoryQuery(
    recorder: PermissionHistoryRecorder?,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_PERMISSION_HISTORY}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )

    val rowsAll = (recorder?.snapshot(sessionIdStr) ?: emptyList()).map { entry ->
        val decision = when {
            entry.accepted == null -> "pending"
            entry.remembered == true -> "always"
            entry.accepted -> "once"
            else -> "reject"
        }
        PermissionHistoryRow(
            requestId = entry.requestId,
            permission = entry.permission,
            patterns = entry.patterns,
            decision = decision,
            accepted = entry.accepted,
            remembered = entry.remembered,
            askedEpochMs = entry.askedEpochMs,
            repliedEpochMs = entry.repliedEpochMs,
        )
    }

    val total = rowsAll.size
    val rows = rowsAll.drop(offset).take(limit)

    val jsonRows = encodeRows(ListSerializer(PermissionHistoryRow.serializer()), rows)

    val rejectedCount = rowsAll.count { it.decision == "reject" }
    val pendingCount = rowsAll.count { it.decision == "pending" }
    val narrative = when {
        recorder == null ->
            "Permission history recorder not wired in this rig — `permission_history` reports zero rows. " +
                "(Production containers always wire it; this only fires in non-permission-UX test rigs.)"
        rowsAll.isEmpty() ->
            "Session $sessionIdStr has no permission asks recorded since this process started."
        else -> {
            val tail = rowsAll.last()
            val tailDesc = "${tail.permission} → ${tail.decision}"
            "${rows.size} of $total permission round-trip(s) for session $sessionIdStr (oldest first). " +
                "Most recent: $tailDesc. $rejectedCount rejected, $pendingCount pending."
        }
    }

    return ToolResult(
        title = "session_query permission_history $sessionIdStr (${rows.size}/$total)",
        outputForLlm = narrative,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_PERMISSION_HISTORY,
            total = total,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
