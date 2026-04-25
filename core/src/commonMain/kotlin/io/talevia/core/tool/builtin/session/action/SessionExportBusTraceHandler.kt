package io.talevia.core.tool.builtin.session.action

import io.talevia.core.JsonConfig
import io.talevia.core.bus.BusEventTraceRecorder
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionActionTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString

/**
 * `session_action(action="export_bus_trace", format="jsonl"|"json", limit?)`.
 *
 * Flushes the per-session ring-buffer of `BusEvent` snapshots
 * captured by [BusEventTraceRecorder] to a string the caller can
 * write to disk (`write_file`) or stream into another process for
 * offline triage. Two formats:
 *
 *  - `"jsonl"` (default) — one [BusEventTraceRecorder.Entry] JSON
 *    object per line. Stream-friendly, grep-friendly.
 *  - `"json"` — single JSON array. Easier for one-shot tools that
 *    decode an entire file in one go.
 *
 * Optional `limit` caps the export to the most-recent N entries
 * (full ring-buffer when null). Must be ≥ 1 if set; ≤ 0 fails loud
 * because "export 0 entries" is almost always a typo for "no limit".
 *
 * The recorder is process-scoped — entries vanish on restart. This
 * handler is the only path to persist a snapshot before that
 * happens; pair with `write_file` if the caller wants the dump on
 * disk rather than in the response payload.
 *
 * Returns a [SessionActionTool.Output] with the body in
 * `exportedBusTrace`, the count in `exportedTraceEntryCount`, and
 * the format echo in `exportedTraceFormat`.
 */
internal fun executeSessionExportBusTrace(
    busTrace: BusEventTraceRecorder?,
    input: SessionActionTool.Input,
    ctx: ToolContext,
): ToolResult<SessionActionTool.Output> {
    val recorder = busTrace
        ?: error(
            "action=export_bus_trace requires the container to wire a BusEventTraceRecorder " +
                "(see DefaultBuiltinRegistrations). The CLI / Server / Desktop containers do; " +
                "test rigs that omit it cannot use this action.",
        )
    val sid = ctx.resolveSessionId(input.sessionId)
    val format = input.format?.trim()?.lowercase() ?: "jsonl"
    require(format == "jsonl" || format == "json") {
        "action=export_bus_trace `format` must be \"jsonl\" or \"json\" (got `$format`)."
    }
    val limit = input.limit
    require(limit == null || limit >= 1) {
        "action=export_bus_trace `limit` must be ≥ 1 if set (got $limit). Omit the field for no cap."
    }

    val all = recorder.snapshot(sid.value)
    val scoped = if (limit != null && all.size > limit) all.takeLast(limit) else all
    val body = when (format) {
        "jsonl" -> scoped.joinToString(separator = "\n") { entry ->
            JsonConfig.default.encodeToString(BusEventTraceRecorder.Entry.serializer(), entry)
        }
        "json" -> JsonConfig.default.encodeToString(
            ListSerializer(BusEventTraceRecorder.Entry.serializer()),
            scoped,
        )
        else -> error("unreachable — format validated above: $format")
    }

    val session = recorder.snapshot(sid.value).firstOrNull()?.sessionId
    // Title prefers the actual session row's title when reachable;
    // fall back to the session id when the recorder has no entries
    // for it yet (caller may have just attached).
    val outputForLlm = if (scoped.isEmpty()) {
        "No bus events captured for session ${sid.value} yet (recorder has empty ring buffer for this session)."
    } else {
        "Exported ${scoped.size} of ${all.size} bus event(s) for session ${sid.value} as $format. " +
            "Pipe the body to write_file to persist; stream-decode jsonl for offline triage."
    }
    return ToolResult(
        title = "export_bus_trace ${sid.value} ($format, ${scoped.size} entries)",
        outputForLlm = outputForLlm,
        data = SessionActionTool.Output(
            sessionId = sid.value,
            action = "export_bus_trace",
            // Title is informational; recorder doesn't carry it. Caller
            // can re-resolve from session_query if needed.
            title = session.orEmpty(),
            exportedBusTrace = body,
            exportedTraceEntryCount = scoped.size,
            exportedTraceFormat = format,
        ),
    )
}
