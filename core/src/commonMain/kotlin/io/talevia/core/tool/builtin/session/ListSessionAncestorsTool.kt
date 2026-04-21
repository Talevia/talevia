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
 * Walk the parent chain of a session — the up-tree complement of
 * [ListSessionForksTool]. Given a sessionId, return its lineage starting at
 * the immediate parent and ending at the root (a session whose
 * `parentId` is null).
 *
 * Why one call covers the full chain (vs. `list_session_forks`'s
 * one-hop stance):
 *  - Fork fanout can be broad (one session → many children → many
 *    grandchildren); returning everything transitively would balloon
 *    output unpredictably, so child walking is one-hop per call.
 *  - Ancestor chains are at most *one line* — O(depth), not
 *    O(breadth × depth). A long narrative project might stack 10–20
 *    forks; even a pathological case is bounded by total session
 *    count. One call returning the whole chain is the natural shape.
 *
 * Cycle-safe via visited-set: a corrupt `parentId` graph (shouldn't
 * happen — we only set it through `fork_session`, and store `fork`
 * mints a fresh id — but belt-and-braces) won't put us in an infinite
 * loop.
 *
 * Read-only, `session.read`. Includes archived ancestors so the chain
 * reads top-to-bottom without gaps when an intermediate session has
 * been archived.
 */
class ListSessionAncestorsTool(
    private val sessions: SessionStore,
) : Tool<ListSessionAncestorsTool.Input, ListSessionAncestorsTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
    )

    @Serializable data class Summary(
        val id: String,
        val projectId: String,
        val title: String,
        val parentId: String?,
        val createdAtEpochMs: Long,
        val archived: Boolean,
    )

    @Serializable data class Output(
        val sessionId: String,
        val depth: Int,
        /** Ancestors ordered child-to-root: first entry is the immediate
         *  parent, last entry is the root of the fork lineage. Empty
         *  when the session has no parent. */
        val ancestors: List<Summary>,
    )

    override val id: String = "list_session_ancestors"
    override val helpText: String =
        "Walk a session's parentId chain up to the root — complement of list_session_forks. " +
            "Returns immediate-parent first, root last. Empty list means the session is itself a " +
            "root (parentId is null). Use to answer \"where did this branch come from?\" or to " +
            "find the root session of a deeply-forked lineage in one call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Session whose parent chain to walk.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("sessionId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val startId = SessionId(input.sessionId)
        val start = sessions.getSession(startId)
            ?: error(
                "Session ${input.sessionId} not found. Call list_sessions to discover valid session ids.",
            )

        val chain = mutableListOf<Summary>()
        val visited = mutableSetOf<SessionId>()
        visited += start.id

        var cursor = start.parentId
        while (cursor != null) {
            if (!visited.add(cursor)) {
                // Cycle detected — stop here. Should never happen given fork's
                // "fresh uuid" contract, but we don't want an infinite loop if
                // the DB ever carries a bad row.
                break
            }
            val ancestor = sessions.getSession(cursor) ?: break
            chain += Summary(
                id = ancestor.id.value,
                projectId = ancestor.projectId.value,
                title = ancestor.title,
                parentId = ancestor.parentId?.value,
                createdAtEpochMs = ancestor.createdAt.toEpochMilliseconds(),
                archived = ancestor.archived,
            )
            cursor = ancestor.parentId
        }

        val summary = if (chain.isEmpty()) {
            "Session ${start.id.value} '${start.title}' is a root (no parent)."
        } else {
            "${chain.size} ancestor(s) of ${start.id.value} '${start.title}', " +
                "parent-first → root: " +
                chain.take(5).joinToString("; ") { "${it.id} '${it.title}'" } +
                if (chain.size > 5) "; …" else ""
        }
        return ToolResult(
            title = "list ancestors of ${start.id.value} (${chain.size})",
            outputForLlm = summary,
            data = Output(
                sessionId = start.id.value,
                depth = chain.size,
                ancestors = chain,
            ),
        )
    }
}
