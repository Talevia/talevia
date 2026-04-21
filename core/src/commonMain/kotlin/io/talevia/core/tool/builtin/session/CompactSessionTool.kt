package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
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
 * Manually trigger two-phase context compaction on a session — the
 * agent-callable surface of the long-existing `Compactor` primitive
 * (`core/compaction/Compactor.kt`) the Agent loop already runs
 * automatically when history crosses a token threshold.
 *
 * Why expose this to the agent explicitly:
 * - Before a long autonomous task the agent wants to proactively free
 *   up context budget: "compact now, then start the big render
 *   sweep."
 * - After an aggressive debugging session the user says "clean up this
 *   session's context before we continue" — there's no reason to wait
 *   for the automatic threshold to fire.
 * - Debugging: a user audits whether compaction is happening and wants
 *   to force a run to compare before/after.
 *
 * Behaviour matches [Compactor.process] verbatim:
 *  - Protects the last N user turns (default 2).
 *  - Drops completed-tool outputs older than the protect window, once
 *    the envelope passes `pruneProtectTokens` (default 40k).
 *  - Summarises surviving history via the session's provider + model
 *    into a new [io.talevia.core.session.Part.Compaction] attached to
 *    the latest assistant message.
 *
 * Model resolution: picks the `ModelRef` from the session's most
 * recent assistant turn. If the session has no assistant messages yet
 * (nothing to compact), returns `skipped` with a clear reason.
 *
 * Permission: `session.write` — compaction mutates session state
 * (`time_compacted` stamps on older parts + a new Compaction part).
 */
class CompactSessionTool(
    private val providers: ProviderRegistry,
    private val sessions: SessionStore,
    private val bus: EventBus,
) : Tool<CompactSessionTool.Input, CompactSessionTool.Output> {

    @Serializable data class Input(
        val sessionId: String,
    )

    @Serializable data class Output(
        val sessionId: String,
        val compacted: Boolean,
        /** Count of parts whose `time_compacted` was stamped in this run. */
        val prunedPartCount: Int = 0,
        /** Id of the new Part.Compaction attached to the latest assistant message. */
        val compactionPartId: String? = null,
        /** First 200 chars of the summary. Use read_part for the full text. */
        val summaryPreview: String? = null,
        /** Non-null when the run was a no-op. */
        val skipReason: String? = null,
    )

    override val id: String = "compact_session"
    override val helpText: String =
        "Proactively trigger two-phase compaction on a session — the manual handle on the same " +
            "Compactor the Agent runs automatically at the 120k-token threshold. Picks the model " +
            "from the session's latest assistant turn. Returns compaction-part id + summary " +
            "preview on success, or a skip reason when there's nothing to compact."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("sessionId") {
                put("type", "string")
                put("description", "Session id from list_sessions.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("sessionId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val sid = SessionId(input.sessionId)
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${input.sessionId} not found. Call list_sessions to discover valid session ids.",
            )

        val history = sessions.listMessagesWithParts(sid, includeCompacted = false)
        val lastAssistant = history.lastOrNull { it.message is Message.Assistant }?.message as? Message.Assistant
            ?: return noOp(session.id, "session has no assistant messages — nothing to compact")

        val model: ModelRef = lastAssistant.model
        val provider = providers.get(model.providerId)
            ?: return noOp(
                session.id,
                "session's model '${model.providerId}/${model.modelId}' references a provider " +
                    "that is not registered in this container",
            )

        val compactor = Compactor(provider = provider, store = sessions, bus = bus)
        val result = compactor.process(sessionId = sid, history = history, model = model)

        return when (result) {
            is Compactor.Result.Compacted -> ToolResult(
                title = "compact session ${sid.value}",
                outputForLlm = "Compacted ${result.prunedCount} part(s) on ${sid.value}. " +
                    "Summary part ${result.partId.value}: ${result.summary.take(80)}" +
                    if (result.summary.length > 80) "…" else "",
                data = Output(
                    sessionId = sid.value,
                    compacted = true,
                    prunedPartCount = result.prunedCount,
                    compactionPartId = result.partId.value,
                    summaryPreview = result.summary.take(200),
                ),
            )
            is Compactor.Result.Skipped -> ToolResult(
                title = "compact session ${sid.value} (skipped)",
                outputForLlm = "Skipped compaction on ${sid.value}: ${result.reason}.",
                data = Output(
                    sessionId = sid.value,
                    compacted = false,
                    skipReason = result.reason,
                ),
            )
        }
    }

    private fun noOp(sessionId: SessionId, reason: String): ToolResult<Output> =
        ToolResult(
            title = "compact session ${sessionId.value} (noop)",
            outputForLlm = "No-op on ${sessionId.value}: $reason.",
            data = Output(
                sessionId = sessionId.value,
                compacted = false,
                skipReason = reason,
            ),
        )
}
