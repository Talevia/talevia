package io.talevia.core.tool.builtin.session

import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.CompactionStrategy
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
        /**
         * Optional — omit to default to the tool's owning session
         * (`ToolContext.sessionId`). Pass an explicit id only to act on a
         * different session than the one currently dispatching.
         */
        val sessionId: String? = null,
        /**
         * Optional compaction strategy; defaults to summarise + prune
         * (current behaviour). Accepts `summarize_and_prune` (alias
         * `default`, `summarise_and_prune`) or `prune_only` (alias
         * `prune`, `no_summary`). Unknown values fall back to the
         * default — typos can't silently skip the summary call.
         *
         * `prune_only` is the right pick when the session is mostly
         * tool calls + outputs with very little prose between them —
         * a generated summary adds little value and costs an extra
         * provider round-trip.
         */
        val strategy: String? = null,
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
        /** Echo back the strategy that ran — `summarize_and_prune` or `prune_only`. */
        val strategy: String = "summarize_and_prune",
    )

    override val id: String = "compact_session"
    override val helpText: String =
        "Proactively trigger two-phase compaction on a session — the manual handle on the same " +
            "Compactor the Agent runs automatically at the 120k-token threshold. Picks the model " +
            "from the session's latest assistant turn. Returns compaction-part id + summary " +
            "preview on success, or a skip reason when there's nothing to compact. " +
            "strategy=`prune_only` skips the LLM summary call (cheaper; right for tool-heavy " +
            "sessions); default `summarize_and_prune` keeps current behaviour."
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
                    "Optional — omit to act on this session (context-resolved). Explicit id from session_query(select=sessions) to target a different session.",
                )
            }
            putJsonObject("strategy") {
                put("type", "string")
                put(
                    "description",
                    "Optional. `summarize_and_prune` (default) prunes oldest tool outputs " +
                        "and writes an LLM-generated summary part. `prune_only` prunes " +
                        "only — no provider call, no summary part written. Unknown values " +
                        "fall back to the default.",
                )
            }
        }
        put("required", JsonArray(emptyList()))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val sid = ctx.resolveSessionId(input.sessionId)
        val strategy = CompactionStrategy.parseOrDefault(input.strategy)
        val strategyLabel = strategy.toLabel()
        val session = sessions.getSession(sid)
            ?: error(
                "Session ${sid.value} not found. Call session_query(select=sessions) to discover valid session ids.",
            )

        val history = sessions.listMessagesWithParts(sid, includeCompacted = false)
        val lastAssistant = history.lastOrNull { it.message is Message.Assistant }?.message as? Message.Assistant
            ?: return noOp(session.id, "session has no assistant messages — nothing to compact", strategyLabel)

        val model: ModelRef = lastAssistant.model
        val provider = providers.get(model.providerId)
            ?: return noOp(
                session.id,
                "session's model '${model.providerId}/${model.modelId}' references a provider " +
                    "that is not registered in this container",
                strategyLabel,
            )

        val compactor = Compactor(provider = provider, store = sessions, bus = bus)
        val result = compactor.process(
            sessionId = sid,
            history = history,
            model = model,
            strategy = strategy,
        )

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
                    strategy = strategyLabel,
                ),
            )
            is Compactor.Result.Pruned -> ToolResult(
                title = "compact session ${sid.value} (prune-only)",
                outputForLlm = "Pruned ${result.prunedCount} tool output(s) on ${sid.value} " +
                    "(prune-only — no summary written).",
                data = Output(
                    sessionId = sid.value,
                    compacted = true,
                    prunedPartCount = result.prunedCount,
                    strategy = strategyLabel,
                ),
            )
            is Compactor.Result.Skipped -> ToolResult(
                title = "compact session ${sid.value} (skipped)",
                outputForLlm = "Skipped compaction on ${sid.value}: ${result.reason}.",
                data = Output(
                    sessionId = sid.value,
                    compacted = false,
                    skipReason = result.reason,
                    strategy = strategyLabel,
                ),
            )
        }
    }

    private fun noOp(sessionId: SessionId, reason: String, strategy: String): ToolResult<Output> =
        ToolResult(
            title = "compact session ${sessionId.value} (noop)",
            outputForLlm = "No-op on ${sessionId.value}: $reason.",
            data = Output(
                sessionId = sessionId.value,
                compacted = false,
                skipReason = reason,
                strategy = strategy,
            ),
        )

    private fun CompactionStrategy.toLabel(): String = when (this) {
        CompactionStrategy.SUMMARIZE_AND_PRUNE -> "summarize_and_prune"
        CompactionStrategy.PRUNE_ONLY -> "prune_only"
    }
}
