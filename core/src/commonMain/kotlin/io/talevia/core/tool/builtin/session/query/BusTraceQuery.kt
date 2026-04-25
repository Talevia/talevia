package io.talevia.core.tool.builtin.session.query

import io.talevia.core.JsonConfig
import io.talevia.core.bus.BusEventTraceRecorder
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * Row shape for `select=bus_trace`. Aliases [BusEventTraceRecorder.Entry]
 * so the data class and its `@Serializable` shape live next to the
 * recorder while session_query consumers still have a top-level row
 * type they can decode against.
 */
typealias BusTraceRow = BusEventTraceRecorder.Entry

/**
 * `select=bus_trace` — per-session bus event trace.
 *
 * Backstory: when an agent turn fails the ground truth is in the bus
 * events that fired during the turn. Today CLI captures them in
 * `~/.talevia/cli.log`; Server / Desktop have no equivalent. This
 * select reads from [BusEventTraceRecorder]'s in-memory ring buffer
 * (cap 256 entries per session, process-scoped — no cross-restart
 * persistence, since trace is debug ephemera).
 *
 * Filters:
 *  - `sessionId` (required)
 *  - `sinceEpochMs` (optional) — drop entries older than this.
 *  - `kind` (optional) — exact match on the event class simple name
 *    (e.g. `PartDelta`, `MessageUpdated`, `AgentRetryScheduled`).
 *
 * When the recorder isn't wired (test rigs without the aggregator),
 * the query reports zero rows with a "not wired" note rather than
 * failing — same convention as the other optional aggregator wires.
 *
 * Sorted oldest-first (matches the recorder's ring-buffer order) so a
 * caller paging with `offset` walks the timeline in temporal order.
 */
internal fun runBusTraceQuery(
    recorder: BusEventTraceRecorder?,
    input: SessionQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<SessionQueryTool.Output> {
    val sessionIdStr = input.sessionId
        ?: error(
            "select='${SessionQueryTool.SELECT_BUS_TRACE}' requires sessionId. Call " +
                "session_query(select=sessions) to discover valid ids.",
        )

    val all = recorder?.snapshot(sessionIdStr).orEmpty()
    val filtered = all.asSequence()
        .filter { input.sinceEpochMs == null || it.epochMs >= input.sinceEpochMs }
        .filter { input.kind == null || it.kind == input.kind }
        .toList()
    val total = filtered.size
    val page = filtered.drop(offset).take(limit)

    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(BusTraceRow.serializer()),
        page,
    ) as JsonArray

    val summary = when {
        recorder == null ->
            "Bus event trace recorder not wired in this rig — query reports zero rows. " +
                "(Production containers always wire it.)"
        all.isEmpty() ->
            "No bus events captured for session $sessionIdStr since recorder attach."
        else -> {
            val kindNote = if (input.kind != null) " (kind=${input.kind})" else ""
            "Bus trace for $sessionIdStr$kindNote: $total event(s); returning ${page.size}."
        }
    }

    return ToolResult(
        title = "session_query bus_trace ($total event${if (total == 1) "" else "s"})",
        outputForLlm = summary,
        data = SessionQueryTool.Output(
            select = SessionQueryTool.SELECT_BUS_TRACE,
            total = total,
            returned = page.size,
            rows = jsonRows,
        ),
    )
}
