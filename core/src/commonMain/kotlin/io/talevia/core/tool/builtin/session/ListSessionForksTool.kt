package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
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
 * Enumerate the immediate forks of a session — sessions whose
 * `Session.parentId` equals the given id. Exposes the `selectChildren`
 * SQL query in `Sessions.sq` that has existed but had no call site.
 *
 * Motivation: `fork_session` creates a branch; `list_sessions` shows
 * every session globally; neither answers "which forks did this
 * session spawn?" The agent can filter `list_sessions` client-side by
 * `parentId`, but that's O(all-sessions) for an O(k-children) question
 * and hits the default 50-row cap once the catalog grows.
 *
 * Read-only, `session.read`. Includes archived children — a fork that
 * was later archived is still part of the lineage; callers filter by
 * `Session.archived` on each `Summary` if needed.
 */
class ListSessionForksTool(
    private val sessions: SessionStore,
) : Tool<ListSessionForksTool.Input, ListSessionForksTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
    )

    @Serializable data class Summary(
        val id: String,
        val projectId: String,
        val title: String,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val archived: Boolean,
    )

    @Serializable data class Output(
        val parentSessionId: String,
        val forkCount: Int,
        val forks: List<Summary>,
    )

    override val id: String = "list_session_forks"
    override val helpText: String =
        "List immediate child sessions of a session id — oldest first. Use to walk the fork graph " +
            "(parent → child → grandchild) one hop at a time. Includes archived children so the " +
            "lineage is complete; filter on Summary.archived client-side if needed."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Parent session id whose children to list.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("sessionId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val parentId = SessionId(input.sessionId)
        // Verify the parent exists so a typo or stale id fails loud instead of
        // silently returning an empty list ("no forks" vs "you pointed at the
        // wrong id" are both empty lists; the existence check distinguishes).
        val parent = sessions.getSession(parentId)
            ?: error(
                "Session ${input.sessionId} not found. Call list_sessions to discover valid session ids.",
            )

        val children = sessions.listChildSessions(parentId)
        val summaries = children.map { s ->
            Summary(
                id = s.id.value,
                projectId = s.projectId.value,
                title = s.title,
                createdAtEpochMs = s.createdAt.toEpochMilliseconds(),
                updatedAtEpochMs = s.updatedAt.toEpochMilliseconds(),
                archived = s.archived,
            )
        }

        val out = Output(
            parentSessionId = parent.id.value,
            forkCount = summaries.size,
            forks = summaries,
        )
        val summary = if (summaries.isEmpty()) {
            "Session ${parent.id.value} '${parent.title}' has no forks."
        } else {
            "${summaries.size} fork(s) of ${parent.id.value} '${parent.title}', oldest first: " +
                summaries.take(5).joinToString("; ") { "${it.id} '${it.title}'" } +
                if (summaries.size > 5) "; …" else ""
        }
        return ToolResult(
            title = "list forks of ${parent.id.value} (${summaries.size})",
            outputForLlm = summary,
            data = out,
        )
    }
}
