package io.talevia.core.tool.builtin.session

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Flip `Session.archived` on. Archived sessions are hidden from
 * `session_query(select=sessions)` (the store's `selectAll` / `selectByProject` SQL
 * filter `archived = 0`), so this is effectively the "put away" verb.
 *
 * The session row and its messages are **not** deleted — recovery is
 * `unarchive_session` by id. The id is the handle the user has to hold
 * onto (or recover from outside the tool — CLI shell, external notes)
 * because there's no archived-inclusive SQL query yet (follow-up: a
 * `selectAllIncludingArchived` variant would close that loop; until
 * then archive is best used for sessions the user is explicitly done
 * with).
 *
 * Idempotent: archiving an already-archived session is a no-op that
 * returns `wasArchived=true` without mutating. Matches the
 * `set_lockfile_entry_pinned` / `rename_session` style.
 *
 * Permission: reuses `session.write`.
 */
class ArchiveSessionTool(
    private val sessions: SessionStore,
    private val clock: Clock = Clock.System,
) : Tool<ArchiveSessionTool.Input, ArchiveSessionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the tool's owning session
         * (`ToolContext.sessionId`). Pass an explicit id only to archive a
         * different session than the one currently dispatching.
         */
        val sessionId: String? = null,
    )

    @Serializable data class Output(
        val sessionId: String,
        val title: String,
        /** True when the session was already archived at call time (no-op). */
        val wasArchived: Boolean,
    )

    override val id: String = "archive_session"
    override val helpText: String =
        "Archive a session. Hides it from session_query(select=sessions) (row + messages are preserved). Idempotent. " +
            "Inverse: unarchive_session. Note: the store does not currently expose archived-inclusive " +
            "listing, so the session id is the recovery handle once archived."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to archive this session (context-resolved). Explicit id to archive a different session.",
                )
            }
        }
        put("required", JsonArray(emptyList()))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
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
                "exclude it; use unarchive_session to restore.",
            data = Output(
                sessionId = sid.value,
                title = session.title,
                wasArchived = wasArchived,
            ),
        )
    }
}

/**
 * Flip `Session.archived` off — inverse of [ArchiveSessionTool]. Matches
 * the pin/unpin idempotency contract: re-unarchiving an already-live
 * session is a no-op that returns `wasUnarchived=true`.
 */
class UnarchiveSessionTool(
    private val sessions: SessionStore,
    private val clock: Clock = Clock.System,
) : Tool<UnarchiveSessionTool.Input, UnarchiveSessionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the tool's owning session
         * (`ToolContext.sessionId`). On the owning session this is a no-op
         * (the session must be live to dispatch), but accepting the default
         * keeps the 5-argument surface uniform.
         */
        val sessionId: String? = null,
    )

    @Serializable data class Output(
        val sessionId: String,
        val title: String,
        /** True when the session was already live (un-archived) at call time (no-op). */
        val wasUnarchived: Boolean,
    )

    override val id: String = "unarchive_session"
    override val helpText: String =
        "Restore a previously-archived session. Idempotent. Use when the session id was kept " +
            "(archived sessions aren't returned by session_query(select=sessions) today). Inverse: archive_session."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to unarchive this session (no-op on a live session). Explicit id to restore a different archived session.",
                )
            }
        }
        put("required", JsonArray(emptyList()))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
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
                title = session.title,
                wasUnarchived = wasUnarchived,
            ),
        )
    }
}
