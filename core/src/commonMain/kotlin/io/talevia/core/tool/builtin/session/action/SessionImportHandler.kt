package io.talevia.core.tool.builtin.session.action

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.ExportSessionTool
import io.talevia.core.tool.builtin.session.SessionActionTool
import io.talevia.core.tool.builtin.session.SessionEnvelope

/**
 * `session_action(action="import")` handler — symmetric pair of
 * `export_session(format=json)`. Materialises a previously-exported
 * envelope into the target [SessionStore]:
 *
 * 1. Validates [projects] is wired (the registering AppContainer
 *    must pass `projects=` to the SessionActionTool constructor; not
 *    every test rig does).
 * 2. Validates `envelope` is non-blank.
 * 3. Decodes via [JsonConfig.default] into [SessionEnvelope]. Decode
 *    errors fail loud with the kotlinx.serialization message intact.
 * 4. Refuses payloads with a wrong `formatVersion` — silent
 *    tolerance risks corrupting the target session store when
 *    `Message` / `Part` schemas evolve, exactly the contract
 *    `ExportSessionTool.FORMAT_VERSION` signs.
 * 5. Requires the envelope's target `projectId` to already exist
 *    locally — pair with `import_project_from_json` if the project
 *    isn't there yet (this tool intentionally doesn't create
 *    projects; that's a separate audit surface).
 * 6. Refuses to overwrite an existing session id — operator decides
 *    whether to delete the existing session first or rename it,
 *    rather than this tool silently merging or replacing.
 * 7. Materialises the session row, then every `Message`, then every
 *    `Part`. Order: messages before parts so `appendMessage`'s
 *    parent-id checks pass; parts last so the part rows reference
 *    already-persisted message ids.
 *
 * The envelope's session id, message ids, part ids, and
 * `createdAt` timestamps are preserved verbatim — round-tripping
 * an export must yield a session that's bit-equal to the source
 * (modulo store-side `updatedAt` which follows the receiving
 * machine's clock as new mutations land).
 */
internal suspend fun executeSessionImport(
    sessions: SessionStore,
    projects: ProjectStore?,
    input: SessionActionTool.Input,
): ToolResult<SessionActionTool.Output> {
    val rawEnvelope = input.envelope
        ?: error(
            "action=import requires `envelope`. Pass the string returned by " +
                "export_session(format=json).data.envelope.",
        )
    require(rawEnvelope.isNotBlank()) { "action=import: envelope must not be blank" }

    val resolvedProjects = projects
        ?: error(
            "action=import requires the SessionActionTool to be constructed with a ProjectStore — " +
                "this AppContainer didn't wire one. Register the tool with `projects=` so the " +
                "import path can verify the envelope's target project exists.",
        )

    val envelope = runCatching {
        JsonConfig.default.decodeFromString(SessionEnvelope.serializer(), rawEnvelope)
    }.getOrElse { e ->
        error(
            "action=import: envelope failed to decode as a session envelope. " +
                "Cause: ${e.message ?: e::class.simpleName}",
        )
    }

    if (envelope.formatVersion != ExportSessionTool.FORMAT_VERSION) {
        error(
            "action=import: envelope formatVersion '${envelope.formatVersion}' does not match " +
                "this tool's '${ExportSessionTool.FORMAT_VERSION}'. The envelope was produced by a " +
                "different schema generation; refusing to import to avoid corrupting the target " +
                "session store. Re-export from a Talevia of the same schema generation.",
        )
    }

    val targetProjectId = envelope.session.projectId
    if (resolvedProjects.get(targetProjectId) == null) {
        error(
            "action=import: envelope's target project '${targetProjectId.value}' is not registered " +
                "on this machine. Import the project first (open_project / import_project_from_json) " +
                "so the imported session has somewhere to bind to.",
        )
    }

    val targetSessionId = envelope.session.id
    if (sessions.getSession(targetSessionId) != null) {
        error(
            "action=import: session id '${targetSessionId.value}' already exists in the target " +
                "store. Refusing to overwrite. Delete the existing session first " +
                "(session_action(action=delete, sessionId=${targetSessionId.value})) or hand-edit " +
                "the envelope's session.id before retrying.",
        )
    }

    sessions.createSession(envelope.session)
    for (message in envelope.messages) {
        sessions.appendMessage(message)
    }
    for (part in envelope.parts) {
        sessions.upsertPart(part)
    }

    return ToolResult(
        title = "import session ${targetSessionId.value}",
        outputForLlm = "Imported session ${targetSessionId.value} '${envelope.session.title}' " +
            "into project ${targetProjectId.value}: ${envelope.messages.size} message(s), " +
            "${envelope.parts.size} part(s). formatVersion=${envelope.formatVersion}.",
        data = SessionActionTool.Output(
            sessionId = targetSessionId.value,
            action = "import",
            title = envelope.session.title,
            importedFormatVersion = envelope.formatVersion,
            importedMessageCount = envelope.messages.size,
            importedPartCount = envelope.parts.size,
        ),
    )
}

/** Convenience helper used by the AppContainer-style `projectId` lookup. */
@Suppress("unused")
private fun ProjectId.lookupKey(): String = this.value
