package io.talevia.core.tool.builtin.session

import io.talevia.core.JsonConfig
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Serialize a session (metadata + every message + every part) into a
 * portable JSON envelope — the session-lane counterpart of
 * `export_project` / `export_source_node`.
 *
 * VISION §5.4 treats sessions as a first-class surface users navigate
 * between. The cross-instance portability leg was missing — users who
 * wanted to:
 *   - back up a long agent conversation to disk,
 *   - share a failed / notable turn with a collaborator on another
 *     Talevia instance to reproduce,
 *   - archive a session snapshot outside the per-instance SQLite,
 *   - ship a pre-baked "tutorial" session transcript as a reusable
 *     template a team member can replay locally,
 * had no tool to produce an envelope. `session_query(select=parts, ...)`
 * returns raw row JSON over the wire but can't write to a file and
 * truncates Part.Text / Part.Compaction previews at 80 chars by
 * design — it's a query primitive, not a persistence format.
 *
 * Envelope shape:
 *   - `formatVersion` — schema identifier; future breaking changes
 *     bump the version so `import_session` (follow-up tool) rejects
 *     unknown payloads loudly rather than silently corrupting state.
 *   - `session` — full [Session] object (id, project binding, title,
 *     parentId, archived, createdAt, updatedAt, compactingFrom,
 *     permissionRules).
 *   - `messages` — every `Message.User` / `Message.Assistant` on the
 *     session, insertion order (store's `listMessages` contract).
 *   - `parts` — every `Part` (text, reasoning, tool, media, compaction,
 *     todos, step-start, step-finish, timeline-snapshot, render-progress),
 *     `includeCompacted=true` so the archive is lossless.
 *
 * **Project + asset blobs deliberately excluded.** Sessions reference a
 * `projectId`; re-importing the envelope onto a target instance requires
 * the target to have the same project (or a fork of it). If the agent
 * needs project payload too, pair this with `export_project`. Keeping
 * the envelope session-scoped matches the same rationale documented on
 * `ExportProjectTool` for the reverse direction ("sessions not included
 * — they reference projects, not vice versa").
 *
 * Read-only, `session.read`.
 */
class ExportSessionTool(
    private val sessions: SessionStore,
) : Tool<ExportSessionTool.Input, ExportSessionTool.Output> {

    private val json: Json get() = JsonConfig.default

    @Serializable data class Input(
        /**
         * Optional — omit to default to the tool's owning session
         * (`ToolContext.sessionId`). Explicit id exports a different session.
         */
        val sessionId: String? = null,
        /** Pretty-print the envelope? Default false (compact wire shape). */
        val prettyPrint: Boolean = false,
    )

    @Serializable data class Output(
        val sessionId: String,
        val title: String,
        val projectId: String,
        val formatVersion: String,
        val messageCount: Int,
        val partCount: Int,
        /**
         * The serialized envelope string, ready for `write_file` or a future
         * `import_session` tool. No filesystem I/O is performed here — the
         * agent chains `write_file` for persistence (keeps the fs boundary in
         * the fs-tool domain, matches export_project / export_source_node).
         */
        val envelope: String,
    )

    override val id: String = "export_session"
    override val helpText: String =
        "Serialize a session (metadata + every message + every part, including compacted) into " +
            "a portable JSON envelope. Use for backup / cross-instance share / version control / " +
            "transcript archival. Pair with write_file to persist. formatVersion tags the schema so " +
            "a future import_session rejects unknown versions loudly. Projects are NOT included " +
            "(sessions reference projects, not vice versa) — export_project handles that."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to export this session (context-resolved). Explicit id for a different session.",
                )
            }
            putJsonObject("prettyPrint") {
                put("type", "boolean")
                put("description", "Pretty-print the envelope. Default false (compact).")
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

        val messages = sessions.listMessages(sid)
        val parts = sessions.listSessionParts(sid, includeCompacted = true)

        val envelope = SessionEnvelope(
            formatVersion = FORMAT_VERSION,
            session = session,
            messages = messages,
            parts = parts,
        )
        val jsonInstance = if (input.prettyPrint) Json(from = json) { prettyPrint = true } else json
        val serialized = jsonInstance.encodeToString(SessionEnvelope.serializer(), envelope)

        return ToolResult(
            title = "export session ${session.id.value}",
            outputForLlm = "Exported session ${session.id.value} '${session.title}' on project " +
                "${session.projectId.value} as $FORMAT_VERSION " +
                "(${messages.size} message(s), ${parts.size} part(s); ${serialized.length} bytes). " +
                "Pass data.envelope to write_file to persist.",
            data = Output(
                sessionId = session.id.value,
                title = session.title,
                projectId = session.projectId.value,
                formatVersion = FORMAT_VERSION,
                messageCount = messages.size,
                partCount = parts.size,
                envelope = serialized,
            ),
        )
    }

    companion object {
        /**
         * Schema identifier embedded in every envelope. Bumped on breaking
         * changes so the future `import_session` refuses unknown versions —
         * silent tolerance risks corrupting the target session store when
         * Message / Part schemas evolve.
         */
        const val FORMAT_VERSION: String = "talevia-session-export-v1"
    }
}

/**
 * On-the-wire envelope for [ExportSessionTool]. Stable JSON shape — additive
 * changes only; breaking changes bump [ExportSessionTool.FORMAT_VERSION].
 */
@Serializable
data class SessionEnvelope(
    val formatVersion: String,
    val session: Session,
    val messages: List<Message>,
    val parts: List<Part>,
)
