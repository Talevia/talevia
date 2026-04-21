package io.talevia.core.tool.builtin.session

import io.talevia.core.ProjectId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Session
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Enumerate agent sessions — the introspection lane that was missing from
 * the Core tool surface. Every other layer (CLI / desktop shell / server
 * UI) already calls `SessionStore.listSessions` to render a session
 * picker, but the *agent itself* couldn't answer "what sessions exist
 * in this project?" without direct DB access. That comes up in real
 * flows: the user asks "continue from the edit we did last night" or
 * "fork the session where we defined Mei", and the agent has no tool to
 * find the relevant session id.
 *
 * Optional project filter — when `projectId` is set, only sessions with
 * that `projectId` are returned. When omitted, every session across
 * every project is returned (most-recent first). Archived sessions are
 * excluded by default; set `includeArchived=true` to see them alongside
 * live sessions (the `archived` flag on each `Summary` lets callers
 * distinguish).
 *
 * Return a trimmed summary — id, title, projectId, parentId, timestamps,
 * archived state. The Agent's own running session is included like any
 * other; there's no special "current session" field because the agent
 * already has that id in hand via its ToolContext.
 *
 * Permission: `session.read` — new permission name (matches the
 * `project.read` / `source.read` naming pattern). Default rule is ALLOW
 * because a session listing is inherently the agent's context to know.
 *
 * Read-only; no mutation path.
 */
class ListSessionsTool(
    private val sessions: SessionStore,
) : Tool<ListSessionsTool.Input, ListSessionsTool.Output> {

    @Serializable data class Input(
        /** Optional project filter. Null returns every session across all projects. */
        val projectId: String? = null,
        /** Include archived sessions in the result? Default false. */
        val includeArchived: Boolean = false,
        /** Cap on returned sessions. Default 50, max 500. */
        val limit: Int? = null,
    )

    @Serializable data class Summary(
        val id: String,
        val projectId: String,
        val title: String,
        val parentId: String?,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val archived: Boolean,
    )

    @Serializable data class Output(
        val totalSessions: Int,
        val returnedSessions: Int,
        val sessions: List<Summary>,
    )

    override val id: String = "list_sessions"
    override val helpText: String =
        "List agent sessions, optionally filtered by projectId. Most recent first (by updatedAt). " +
            "Archived sessions are excluded by default — set includeArchived=true to see them " +
            "alongside live sessions (each Summary carries the archived flag). Use to find a " +
            "session to fork from, revert, or reference when the user says \"the session where we " +
            "did X\"."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional project filter. Null returns sessions across every project.",
                )
            }
            putJsonObject("includeArchived") {
                put("type", "boolean")
                put("description", "Include archived sessions in the result? Default false.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Cap on returned sessions (default 50, max 500).")
            }
        }
        put("required", JsonArray(emptyList()))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = input.projectId?.takeIf { it.isNotBlank() }?.let { ProjectId(it) }
        val all: List<Session> = if (input.includeArchived) {
            sessions.listSessionsIncludingArchived(pid)
        } else {
            sessions.listSessions(pid)
        }
        // Sort by updatedAt descending so most-recent-touched session leads; store
        // ordering is implementation-defined so we sort explicitly for stability.
        val sorted = all.sortedByDescending { it.updatedAt.toEpochMilliseconds() }
        val cap = (input.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val capped = sorted.take(cap)

        val summaries = capped.map { s ->
            Summary(
                id = s.id.value,
                projectId = s.projectId.value,
                title = s.title,
                parentId = s.parentId?.value,
                createdAtEpochMs = s.createdAt.toEpochMilliseconds(),
                updatedAtEpochMs = s.updatedAt.toEpochMilliseconds(),
                archived = s.archived,
            )
        }

        val out = Output(
            totalSessions = all.size,
            returnedSessions = summaries.size,
            sessions = summaries,
        )
        val scopeLabel = when {
            pid != null -> "project ${pid.value}"
            else -> "all projects"
        }
        val summary = if (summaries.isEmpty()) {
            "No sessions on $scopeLabel."
        } else {
            "${summaries.size} session(s) on $scopeLabel: " +
                summaries.take(5).joinToString("; ") { "${it.id} '${it.title}'" } +
                if (summaries.size > 5) "; …" else ""
        }
        return ToolResult(
            title = "list sessions (${summaries.size})",
            outputForLlm = summary,
            data = out,
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 500
    }
}
