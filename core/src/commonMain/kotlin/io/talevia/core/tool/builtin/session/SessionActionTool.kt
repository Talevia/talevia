package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Session lifecycle verbs — consolidated form that replaces
 * `ArchiveSessionTool` + `UnarchiveSessionTool` + `RenameSessionTool`
 * + `DeleteSessionTool`
 * (`debt-consolidate-session-lifecycle-verbs`, 2026-04-23).
 *
 * Four action branches, all preserving the original tool's behaviour
 * verbatim:
 *
 * - `action="archive"` — flip `Session.archived` on. Idempotent:
 *   `wasArchived=true` on an already-archived session is a no-op.
 *   `sessionId` optional (defaults to `ToolContext.sessionId`).
 *   Permission `session.write`.
 * - `action="unarchive"` — flip `Session.archived` off. Idempotent.
 *   `sessionId` optional. Permission `session.write`.
 * - `action="rename"` — update `Session.title`. `sessionId` optional.
 *   `newTitle` required + must be non-blank. Identical-title is a
 *   no-op. Permission `session.write`.
 * - `action="delete"` — permanently delete the session + all its
 *   messages/parts (ON DELETE CASCADE). `sessionId` REQUIRED
 *   (deleting the owning session by context would be self-destructive
 *   mid-dispatch). Permission `session.destructive` — enforced
 *   via [PermissionSpec.permissionFrom] (cycle 21 extension) so the
 *   other three actions stay at the `session.write` tier without a
 *   permission regression.
 *
 * ## Output
 *
 * Shared headers (`sessionId`, `action`, `title`). Action-specific
 * extras carried in optional fields populated only on their branch:
 * `wasAlreadyInTargetState` (archive/unarchive idempotency marker),
 * `previousTitle` + `newTitle` (rename), `archived` (delete snapshot).
 * Unused fields default to empty/false/null — matches the
 * cycle-19 / 21 / 22 consolidation output shape.
 */
class SessionActionTool(
    private val sessions: SessionStore,
    private val clock: Clock = Clock.System,
) : Tool<SessionActionTool.Input, SessionActionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional for archive / unarchive / rename (defaults to
         * `ToolContext.sessionId`). REQUIRED for delete — deleting the
         * owning session by context would be self-destructive while
         * the dispatch is running.
         */
        val sessionId: String? = null,
        /** `"archive"`, `"unarchive"`, `"rename"`, or `"delete"`. */
        val action: String,
        /** Required for `action="rename"`. Must be non-blank. Ignored on other actions. */
        val newTitle: String? = null,
    )

    @Serializable data class Output(
        val sessionId: String,
        val action: String,
        val title: String,
        /** `archive` / `unarchive`: true when the session was already in the target state. */
        val wasAlreadyInTargetState: Boolean = false,
        /** `rename` only: the title as seen before this call. */
        val previousTitle: String? = null,
        /** `rename` only: the title set by this call (echoed for convenience). */
        val newTitle: String? = null,
        /** `delete` only: `Session.archived` as seen before the session was removed. */
        val archived: Boolean = false,
    )

    override val id: String = "session_action"
    override val helpText: String =
        "Session lifecycle verbs in one tool: `action=\"archive\"` hides a session from " +
            "session_query(select=sessions) (preserves row + messages; idempotent). " +
            "`action=\"unarchive\"` restores a hidden session (idempotent). `action=\"rename\"` + " +
            "non-blank `newTitle` updates the session title. `action=\"delete\"` + required " +
            "`sessionId` IRREVERSIBLY deletes the session and every message/part on it — " +
            "`archive` is the reversible alternative. On archive/unarchive/rename, `sessionId` " +
            "defaults to the owning session; on delete it must be explicit. Permission: " +
            "`session.write` for archive/unarchive/rename; `session.destructive` for delete."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()

    /**
     * Base tier `session.write`. [permissionFrom] upgrades to
     * `session.destructive` only when the raw input JSON contains
     * `"action":"delete"` — any other action (or malformed input) stays
     * at `session.write`. Malformed-input defaulting to the LOWER tier
     * is safe here because every action except delete is already a
     * non-destructive mutation; a malformed input will fail validation
     * in [execute] before touching the store. The permission layer
     * just needs to not block the action from reaching that validation.
     */
    override val permission: PermissionSpec = PermissionSpec(
        permission = "session.write",
        permissionFrom = { inputJson ->
            if (isDeleteAction(inputJson)) "session.destructive" else "session.write"
        },
    )

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put(
                    "description",
                    "Required for action=delete. Optional for archive/unarchive/rename (defaults to the owning session).",
                )
            }
            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "`archive`, `unarchive`, `rename`, or `delete`. delete is irreversible.",
                )
                put(
                    "enum",
                    JsonArray(
                        listOf(
                            JsonPrimitive("archive"),
                            JsonPrimitive("unarchive"),
                            JsonPrimitive("rename"),
                            JsonPrimitive("delete"),
                        ),
                    ),
                )
            }
            putJsonObject("newTitle") {
                put("type", "string")
                put("description", "Required for action=rename. Must be non-blank.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        return when (input.action) {
            "archive" -> executeArchive(input, ctx)
            "unarchive" -> executeUnarchive(input, ctx)
            "rename" -> executeRename(input, ctx)
            "delete" -> executeDelete(input)
            else -> error(
                "unknown action '${input.action}'; accepted: archive, unarchive, rename, delete",
            )
        }
    }

    private suspend fun executeArchive(input: Input, ctx: ToolContext): ToolResult<Output> {
        val sid = ctx.resolveSessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
            )

        val wasArchived = session.archived
        if (!wasArchived) {
            sessions.updateSession(session.copy(archived = true, updatedAt = clock.now()))
        }

        val verb = if (wasArchived) "was already archived" else "archived"
        return ToolResult(
            title = "archive session ${sid.value}",
            outputForLlm = "Session ${sid.value} '${session.title}' ($verb). session_query(select=sessions) will now " +
                "exclude it; use session_action(action=unarchive) to restore.",
            data = Output(
                sessionId = sid.value,
                action = "archive",
                title = session.title,
                wasAlreadyInTargetState = wasArchived,
            ),
        )
    }

    private suspend fun executeUnarchive(input: Input, ctx: ToolContext): ToolResult<Output> {
        val sid = ctx.resolveSessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${sid.value} not found. The archived-session recovery handle is its " +
                    "id — make sure the caller has the right string.",
            )

        val wasUnarchived = !session.archived
        if (session.archived) {
            sessions.updateSession(session.copy(archived = false, updatedAt = clock.now()))
        }

        val verb = if (wasUnarchived) "was already live" else "unarchived"
        return ToolResult(
            title = "unarchive session ${sid.value}",
            outputForLlm = "Session ${sid.value} '${session.title}' ($verb). Now visible in session_query(select=sessions) again.",
            data = Output(
                sessionId = sid.value,
                action = "unarchive",
                title = session.title,
                wasAlreadyInTargetState = wasUnarchived,
            ),
        )
    }

    private suspend fun executeRename(input: Input, ctx: ToolContext): ToolResult<Output> {
        val newTitle = input.newTitle
            ?: error("action=rename requires `newTitle`")
        require(newTitle.isNotBlank()) { "newTitle must not be blank" }

        val sid = ctx.resolveSessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
            )

        val previousTitle = session.title
        if (previousTitle == newTitle) {
            return ToolResult(
                title = "rename session (no-op)",
                outputForLlm = "Session ${sid.value} already titled '$previousTitle' — nothing to do.",
                data = Output(
                    sessionId = sid.value,
                    action = "rename",
                    title = previousTitle,
                    previousTitle = previousTitle,
                    newTitle = newTitle,
                ),
            )
        }

        sessions.updateSession(
            session.copy(
                title = newTitle,
                updatedAt = clock.now(),
            ),
        )

        return ToolResult(
            title = "rename session ${sid.value}",
            outputForLlm = "Renamed session ${sid.value}: '$previousTitle' → '$newTitle'.",
            data = Output(
                sessionId = sid.value,
                action = "rename",
                title = newTitle,
                previousTitle = previousTitle,
                newTitle = newTitle,
            ),
        )
    }

    private suspend fun executeDelete(input: Input): ToolResult<Output> {
        val rawSessionId = input.sessionId
            ?: error(
                "action=delete requires explicit `sessionId` (the owning session can't self-delete mid-dispatch).",
            )
        val sid = SessionId(rawSessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session $rawSessionId not found. Call session_query(select=sessions) to discover valid session ids.",
            )

        val snapshot = Output(
            sessionId = session.id.value,
            action = "delete",
            title = session.title,
            archived = session.archived,
        )

        sessions.deleteSession(sid)

        val archivedNote = if (session.archived) " (was archived)" else ""
        return ToolResult(
            title = "delete session ${session.id.value}",
            outputForLlm = "Deleted session ${session.id.value} '${session.title}'$archivedNote. " +
                "Every message + part on it is gone. This cannot be undone.",
            data = snapshot,
        )
    }

    private companion object {
        /**
         * Regex-check for `"action":"delete"` in the raw input JSON, case-insensitive.
         * Runs before kotlinx.serialization decode for `permissionFrom`'s tier gate.
         * Any failed match defaults to the LOWER tier — safe here because a
         * malformed input can't reach the destructive branch (execute() fails first).
         */
        private val DELETE_ACTION_REGEX = Regex(
            pattern = """"action"\s*:\s*"delete"""",
            option = RegexOption.IGNORE_CASE,
        )

        fun isDeleteAction(inputJson: String): Boolean = DELETE_ACTION_REGEX.containsMatchIn(inputJson)
    }
}
