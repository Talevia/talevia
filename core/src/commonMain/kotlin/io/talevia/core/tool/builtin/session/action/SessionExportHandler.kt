package io.talevia.core.tool.builtin.session.action

import io.talevia.core.JsonConfig
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SESSION_EXPORT_FORMAT_VERSION
import io.talevia.core.tool.builtin.session.SESSION_MARKDOWN_FORMAT_VERSION
import io.talevia.core.tool.builtin.session.SessionActionTool
import io.talevia.core.tool.builtin.session.SessionEnvelope
import io.talevia.core.tool.builtin.session.formatSessionAsMarkdown
import kotlinx.serialization.json.Json

/**
 * `session_action(action="export", format?, prettyPrint?)` dispatch
 * handler — symmetric pair of `action="import"`. Cycle 145 absorbed
 * the standalone `export_session` tool here, mirroring the
 * cycle 142–144 session-mutation fold series.
 *
 * Two render formats selected by [SessionActionTool.Input.format]:
 *  - `"json"` (default) — portable [SessionEnvelope] tagged
 *    `talevia-session-export-v1`. Round-trips exactly via
 *    `action="import"`. Includes session metadata + every message +
 *    every part (including compacted) so the archive is lossless.
 *  - `"markdown"` (alias `"md"`) — human-readable transcript with
 *    tool calls folded into GitHub-style callouts. Tagged
 *    `talevia-session-export-md-v1`. **NOT** for re-import — meant
 *    for bug reports / docs / offline reading. Unknown values fall
 *    back to JSON so a typo never silently strips the portable wire
 *    shape.
 *
 * `prettyPrint` only affects JSON; markdown ignores it (its
 * rendering doesn't have a "compact" variant).
 *
 * Permission: dispatcher's `permissionFrom` downgrades this action
 * to `session.read` (it's a pure read path — no SessionStore
 * mutations, no filesystem I/O; the agent chains `write_file` for
 * persistence, keeping the fs boundary in the fs-tool domain to
 * match `export_project` / `export_source_node`).
 *
 * Project + asset blobs deliberately excluded — sessions reference
 * a `projectId` and re-importing requires the target instance to
 * have the same project (or a fork). Pair with `export_project` if
 * project payload is also needed. Same rationale `ExportProjectTool`
 * documents for the reverse direction.
 */
internal suspend fun executeSessionExport(
    sessions: SessionStore,
    input: SessionActionTool.Input,
    ctx: ToolContext,
): ToolResult<SessionActionTool.Output> {
    val sid = ctx.resolveSessionId(input.sessionId)
    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover " +
                "valid session ids.",
        )

    val messages = sessions.listMessages(sid)
    val parts = sessions.listSessionParts(sid, includeCompacted = true)

    val format = parseExportFormat(input.format)
    val (formatLabel, formatVersionTag, serialized) = when (format) {
        ExportFormat.MARKDOWN -> Triple(
            "markdown",
            SESSION_MARKDOWN_FORMAT_VERSION,
            formatSessionAsMarkdown(session, messages, parts),
        )
        ExportFormat.JSON -> {
            val envelope = SessionEnvelope(
                formatVersion = SESSION_EXPORT_FORMAT_VERSION,
                session = session,
                messages = messages,
                parts = parts,
            )
            val baseJson = JsonConfig.default
            val jsonInstance = if (input.prettyPrint) Json(from = baseJson) { prettyPrint = true } else baseJson
            Triple(
                "json",
                SESSION_EXPORT_FORMAT_VERSION,
                jsonInstance.encodeToString(SessionEnvelope.serializer(), envelope),
            )
        }
    }

    return ToolResult(
        title = "export session ${session.id.value}",
        outputForLlm = "Exported session ${session.id.value} '${session.title}' on project " +
            "${session.projectId.value} as $formatVersionTag " +
            "(${messages.size} message(s), ${parts.size} part(s); ${serialized.length} bytes). " +
            "Pass data.exportedSessionEnvelope to write_file to persist.",
        data = SessionActionTool.Output(
            sessionId = session.id.value,
            action = "export",
            title = session.title,
            exportedSessionEnvelope = serialized,
            exportedSessionFormatVersion = formatVersionTag,
            exportedSessionMessageCount = messages.size,
            exportedSessionPartCount = parts.size,
            exportedSessionFormat = formatLabel,
            exportedSessionProjectId = session.projectId.value,
        ),
    )
}

internal enum class ExportFormat { JSON, MARKDOWN }

/**
 * Lenient parse of the agent-facing `format` string. Null / blank /
 * unknown / `"json"` → [ExportFormat.JSON]; `"markdown"` / `"md"`
 * (case-insensitive) → [ExportFormat.MARKDOWN]. Unknown defaults to
 * JSON so a typo never silently strips the portable wire shape.
 */
internal fun parseExportFormat(raw: String?): ExportFormat {
    val key = raw?.trim()?.lowercase() ?: return ExportFormat.JSON
    return when (key) {
        "", "json" -> ExportFormat.JSON
        "markdown", "md" -> ExportFormat.MARKDOWN
        else -> ExportFormat.JSON
    }
}
