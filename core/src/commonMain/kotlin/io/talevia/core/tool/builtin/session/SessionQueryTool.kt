package io.talevia.core.tool.builtin.session

import io.talevia.core.JsonConfig
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.query.runAncestorsQuery
import io.talevia.core.tool.builtin.session.query.runForksQuery
import io.talevia.core.tool.builtin.session.query.runMessagesQuery
import io.talevia.core.tool.builtin.session.query.runPartsQuery
import io.talevia.core.tool.builtin.session.query.runSessionsQuery
import io.talevia.core.tool.builtin.session.query.runToolCallsQuery
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
 * Unified read-only query primitive over the session store — session-lane
 * counterpart of [io.talevia.core.tool.builtin.project.ProjectQueryTool].
 * One tool spec in the LLM's context instead of one tool per dimension
 * (sessions / messages / parts / forks / ancestors / tool_calls) —
 * modelled on codebase-grep's `(select, filter, sort, limit)` shape.
 *
 * [Input.select] discriminates what to return; each select advertises its
 * own filter fields (see [helpText]). Filter fields that don't apply to
 * the chosen select fail loud — silent empty lists would hide typos.
 *
 * Replaces six list tools landed in loop-2 / loop-3:
 *  - `list_sessions` → `select=sessions` (filter: projectId, includeArchived)
 *  - `list_messages` → `select=messages` (filter: role)
 *  - `list_parts` → `select=parts` (filter: kind, includeCompacted)
 *  - `list_session_forks` → `select=forks`
 *  - `list_session_ancestors` → `select=ancestors`
 *  - `list_tool_calls` → `select=tool_calls` (filter: toolId, includeCompacted)
 *
 * `describe_session` and `describe_message` stay as separate tools — they
 * are single-entity deep inspections, not projections.
 *
 * Output is uniform: `{select, total, returned, rows}` where `rows` is a
 * [JsonArray] whose shape depends on `select`. Consumers inspect the
 * echoed `select` and decode with the matching row serializer
 * (`SessionQueryTool.SessionRow.serializer()` etc.); wire encoding is
 * the canonical [JsonConfig.default].
 */
class SessionQueryTool(
    private val sessions: SessionStore,
) : Tool<SessionQueryTool.Input, SessionQueryTool.Output> {

    @Serializable data class Input(
        /** One of [SELECT_SESSIONS] / [SELECT_MESSAGES] / [SELECT_PARTS] /
         *  [SELECT_FORKS] / [SELECT_ANCESTORS] / [SELECT_TOOL_CALLS] (case-insensitive). */
        val select: String,
        /** Required for messages/parts/forks/ancestors/tool_calls. Rejected for sessions. */
        val sessionId: String? = null,
        /** Optional project filter. `select=sessions` only. */
        val projectId: String? = null,
        /** Include archived sessions? `select=sessions` only. Default false. */
        val includeArchived: Boolean? = null,
        /** `"user"` | `"assistant"`. `select=messages` only. */
        val role: String? = null,
        /** Part kind discriminator (see [helpText]). `select=parts` only. */
        val kind: String? = null,
        /** Include compacted rows? `select=parts` and `select=tool_calls` only. Default true. */
        val includeCompacted: Boolean? = null,
        /** Filter tool parts by toolId (e.g. `"generate_image"`). `select=tool_calls` only. */
        val toolId: String? = null,
        /** Post-filter cap. Default 100, clamped to `[1, 1000]`. */
        val limit: Int? = null,
        /** Skip the first N rows after filter+sort. Default 0. */
        val offset: Int? = null,
    )

    @Serializable data class Output(
        /** Echo of the (normalised) select used to produce [rows]. */
        val select: String,
        /** Count of matches after filters, before offset/limit. */
        val total: Int,
        /** Count of rows in [rows]. Lower than [total] when offset/limit hide rows. */
        val returned: Int,
        /** Select-specific row objects, serialised via [JsonConfig.default]. */
        val rows: JsonArray,
    )

    // -----------------------------------------------------------------
    // Row data classes — public because tests + UI decode via the paired
    // serializers (`SessionQueryTool.SessionRow.serializer()` etc.).

    @Serializable data class SessionRow(
        val id: String,
        val projectId: String,
        val title: String,
        val parentId: String? = null,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val archived: Boolean,
    )

    @Serializable data class MessageRow(
        val id: String,
        val role: String,
        val createdAtEpochMs: Long,
        val modelProviderId: String,
        val modelId: String,
        val agent: String? = null,
        val parentId: String? = null,
        val tokensInput: Long? = null,
        val tokensOutput: Long? = null,
        val finish: String? = null,
        val error: String? = null,
    )

    @Serializable data class PartRow(
        val partId: String,
        val kind: String,
        val messageId: String,
        val createdAtEpochMs: Long,
        val compactedAtEpochMs: Long? = null,
        val preview: String,
    )

    @Serializable data class ForkRow(
        val id: String,
        val projectId: String,
        val title: String,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val archived: Boolean,
    )

    @Serializable data class AncestorRow(
        val id: String,
        val projectId: String,
        val title: String,
        val parentId: String? = null,
        val createdAtEpochMs: Long,
        val archived: Boolean,
    )

    @Serializable data class ToolCallRow(
        val partId: String,
        val messageId: String,
        val toolId: String,
        val callId: String,
        /** `"pending"` | `"running"` | `"completed"` | `"error"`. */
        val state: String,
        val title: String? = null,
        val createdAtEpochMs: Long,
        val compactedAtEpochMs: Long? = null,
    )

    override val id: String = "session_query"
    override val helpText: String =
        "Unified read-only query over sessions + messages + parts + forks (replaces list_sessions / " +
            "list_messages / list_parts / list_session_forks / list_session_ancestors / " +
            "list_tool_calls). Pick one `select`:\n" +
            "  • sessions — filter: projectId, includeArchived. Most-recent first by updatedAt. " +
            "Defaults limit 100.\n" +
            "  • messages — filter: role (user|assistant). requires sessionId. Most-recent first.\n" +
            "  • parts — filter: kind (text, reasoning, tool, media, timeline-snapshot, " +
            "render-progress, step-start, step-finish, compaction, todos), includeCompacted. " +
            "requires sessionId. Most-recent first.\n" +
            "  • forks — immediate child sessions (one hop). requires sessionId. Oldest first.\n" +
            "  • ancestors — parent chain up to root (child→root). requires sessionId. Depth-bounded.\n" +
            "  • tool_calls — Part.Tool only. filter: toolId, includeCompacted. requires sessionId. " +
            "Most-recent first.\n" +
            "Common: limit (default 100, clamped 1..1000), offset (default 0). Setting a filter " +
            "that doesn't apply to the chosen select fails loud so typos surface instead of silently " +
            "returning an empty list. Describe-style single-entity drilldown stays in describe_session " +
            "and describe_message."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("select") {
                put("type", "string")
                put(
                    "description",
                    "What to query: sessions | messages | parts | forks | ancestors | tool_calls " +
                        "(case-insensitive).",
                )
            }
            putJsonObject("sessionId") {
                put("type", "string")
                put(
                    "description",
                    "Session id. Required for messages/parts/forks/ancestors/tool_calls. " +
                        "Rejected for select=sessions.",
                )
            }
            putJsonObject("projectId") {
                put("type", "string")
                put("description", "Project filter. select=sessions only.")
            }
            putJsonObject("includeArchived") {
                put("type", "boolean")
                put("description", "Include archived sessions. select=sessions only. Default false.")
            }
            putJsonObject("role") {
                put("type", "string")
                put(
                    "description",
                    "Message role filter: user | assistant. select=messages only.",
                )
            }
            putJsonObject("kind") {
                put("type", "string")
                put(
                    "description",
                    "Part kind: text | reasoning | tool | media | timeline-snapshot | " +
                        "render-progress | step-start | step-finish | compaction | todos. " +
                        "select=parts only.",
                )
            }
            putJsonObject("includeCompacted") {
                put("type", "boolean")
                put(
                    "description",
                    "Include compacted rows. select=parts and select=tool_calls only. Default true.",
                )
            }
            putJsonObject("toolId") {
                put("type", "string")
                put("description", "Filter tool parts by toolId. select=tool_calls only.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put(
                    "description",
                    "Max rows (default 100, clamped to [1, 1000]). Applied after filter+sort+offset.",
                )
            }
            putJsonObject("offset") {
                put("type", "integer")
                put("description", "Skip N rows after filter+sort (default 0).")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("select"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = input.select.trim().lowercase()
        if (select !in ALL_SELECTS) {
            error("select must be one of ${ALL_SELECTS.joinToString(", ")} (got '${input.select}')")
        }
        rejectIncompatibleFilters(select, input)

        val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)
        val offset = (input.offset ?: 0).coerceAtLeast(0)

        return when (select) {
            SELECT_SESSIONS -> runSessionsQuery(sessions, input, limit, offset)
            SELECT_MESSAGES -> runMessagesQuery(sessions, input, limit, offset)
            SELECT_PARTS -> runPartsQuery(sessions, input, limit, offset)
            SELECT_FORKS -> runForksQuery(sessions, input, limit, offset)
            SELECT_ANCESTORS -> runAncestorsQuery(sessions, input, limit, offset)
            SELECT_TOOL_CALLS -> runToolCallsQuery(sessions, input, limit, offset)
            else -> error("unreachable — select validated above: '$select'")
        }
    }

    private fun rejectIncompatibleFilters(select: String, input: Input) {
        // Each filter field belongs to a specific select. Setting it on the wrong
        // select usually means the LLM typed the wrong field name — fail loud.
        val misapplied = buildList {
            if (select != SELECT_SESSIONS) {
                // sessions-only filters
                if (input.projectId != null) add("projectId (select=sessions only)")
                if (input.includeArchived != null) add("includeArchived (select=sessions only)")
            }
            if (select != SELECT_MESSAGES && input.role != null) {
                add("role (select=messages only)")
            }
            if (select != SELECT_PARTS && input.kind != null) {
                add("kind (select=parts only)")
            }
            if (select != SELECT_PARTS && select != SELECT_TOOL_CALLS && input.includeCompacted != null) {
                add("includeCompacted (select=parts or tool_calls only)")
            }
            if (select != SELECT_TOOL_CALLS && input.toolId != null) {
                add("toolId (select=tool_calls only)")
            }
            // sessionId is required for everything except sessions; rejected for sessions.
            if (select == SELECT_SESSIONS && input.sessionId != null) {
                add("sessionId (rejected for select=sessions — use projectId)")
            }
        }
        if (misapplied.isNotEmpty()) {
            error(
                "The following filter fields do not apply to select='$select': " +
                    misapplied.joinToString(", "),
            )
        }
    }

    companion object {
        const val SELECT_SESSIONS = "sessions"
        const val SELECT_MESSAGES = "messages"
        const val SELECT_PARTS = "parts"
        const val SELECT_FORKS = "forks"
        const val SELECT_ANCESTORS = "ancestors"
        const val SELECT_TOOL_CALLS = "tool_calls"
        private val ALL_SELECTS = setOf(
            SELECT_SESSIONS, SELECT_MESSAGES, SELECT_PARTS,
            SELECT_FORKS, SELECT_ANCESTORS, SELECT_TOOL_CALLS,
        )

        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 1000
    }
}
