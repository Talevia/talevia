package io.talevia.core.tool.builtin.session

import io.talevia.core.JsonConfig
import io.talevia.core.PartId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Part
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
 * Return the full JSON payload of a single Part — the drill-down that
 * `describe_message` deliberately stops short of. Where describe_message
 * returns ±80-char previews per part (enough to orient), this tool emits
 * the canonical serialized Part for one id — including the fields that
 * would balloon a turn-level describe call (full Compaction.summary,
 * full TimelineSnapshot.timeline, opaque ToolState.Completed.data, etc.).
 *
 * Serialization goes through `Part.serializer()` + the canonical
 * `JsonConfig.default`, so the wire shape matches what the store
 * persists (including the `type` discriminator). Callers can dispatch
 * on the discriminator to decode kind-specific fields on their end.
 *
 * Scope — raw content. We intentionally don't mask / paginate big
 * payloads: if the caller asked for a specific partId, they know what
 * kind it is (from a prior `describe_message` call) and consented to
 * the size. A bounded variant (`read_part_prefix(limit)`) is a future
 * add if this becomes a footgun.
 *
 * Read-only, `session.read`. Missing partId fails loudly with a
 * `describe_message` hint so the agent can rediscover valid ids.
 */
class ReadPartTool(
    private val sessions: SessionStore,
) : Tool<ReadPartTool.Input, ReadPartTool.Output> {

    @Serializable data class Input(
        val partId: String,
    )

    @Serializable data class Output(
        val partId: String,
        val messageId: String,
        val sessionId: String,
        /** Kind discriminator (text, reasoning, tool, media, timeline-snapshot,
         *  render-progress, step-start, step-finish, compaction, todos). */
        val kind: String,
        val createdAtEpochMs: Long,
        val compactedAtEpochMs: Long? = null,
        /** Full Part.serializer() JSON including the `type` discriminator and
         *  every kind-specific field. */
        val payload: JsonObject,
    )

    override val id: String = "read_part"
    override val helpText: String =
        "Return the full serialized JSON of a single Part. The drill-down that describe_message " +
            "flags but doesn't do — use this for full Compaction summaries, full TimelineSnapshot " +
            "timelines, opaque ToolState.Completed.data, etc. Missing partId fails loud with a " +
            "describe_message hint."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("partId") {
                put("type", "string")
                put("description", "Part id from describe_message.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("partId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = PartId(input.partId)
        val part = sessions.getPart(pid)
            ?: error(
                "Part ${input.partId} not found. Call describe_message on the owning message to " +
                    "discover valid part ids.",
            )

        // Serialize through the sealed Part hierarchy so the `type`
        // discriminator + every kind-specific field land in the output.
        val element = JsonConfig.default.encodeToJsonElement(Part.serializer(), part)
        val payload = element as? JsonObject ?: JsonObject(emptyMap())

        val kind = when (part) {
            is Part.Text -> "text"
            is Part.Reasoning -> "reasoning"
            is Part.Tool -> "tool"
            is Part.Media -> "media"
            is Part.TimelineSnapshot -> "timeline-snapshot"
            is Part.RenderProgress -> "render-progress"
            is Part.StepStart -> "step-start"
            is Part.StepFinish -> "step-finish"
            is Part.Compaction -> "compaction"
            is Part.Todos -> "todos"
        }

        val out = Output(
            partId = part.id.value,
            messageId = part.messageId.value,
            sessionId = part.sessionId.value,
            kind = kind,
            createdAtEpochMs = part.createdAt.toEpochMilliseconds(),
            compactedAtEpochMs = part.compactedAt?.toEpochMilliseconds(),
            payload = payload,
        )
        val compactedNote = if (part.compactedAt != null) " (compacted)" else ""
        return ToolResult(
            title = "read part ${part.id.value}",
            outputForLlm = "$kind part ${part.id.value} on message ${part.messageId.value}$compactedNote. " +
                "Full payload in data.payload.",
            data = out,
        )
    }
}
