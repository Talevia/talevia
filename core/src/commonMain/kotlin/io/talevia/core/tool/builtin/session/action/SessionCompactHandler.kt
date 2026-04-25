package io.talevia.core.tool.builtin.session.action

import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.CompactionStrategy
import io.talevia.core.compaction.Compactor
import io.talevia.core.provider.ProviderRegistry
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.SessionActionTool

/**
 * `session_action(action="compact", strategy?)` dispatch handler.
 *
 * Cycle 147 absorbed the standalone `compact_session` tool into the
 * dispatcher, completing the session-cluster fold series. Wraps the
 * long-existing [Compactor] domain primitive — same semantics the
 * standalone tool documented:
 *  - Protects the last N user turns (default 2 in [Compactor.process]).
 *  - Drops completed-tool outputs older than the protect window once
 *    the envelope passes `pruneProtectTokens` (default 40k).
 *  - Summarises surviving history via the session's bound provider +
 *    model into a new `Part.Compaction` attached to the latest
 *    assistant message (summarise_and_prune path only).
 *
 * Model resolution: picks the `ModelRef` from the session's most
 * recent assistant turn. If the session has no assistant messages
 * yet, returns `compactSkipReason="session has no assistant messages"`
 * — nothing to compact.
 *
 * Permission: inherits the dispatcher's base `session.write` tier —
 * compaction mutates session state (`time_compacted` stamps on older
 * parts + a new Compaction part).
 *
 * Missing-deps fail-loud at dispatch time matches the
 * `import` / `export` / `set_x` patterns:
 *  - `providers=null` (the SessionActionTool was wired without a
 *    [ProviderRegistry] — `DefaultBuiltinRegistrations` first-pass
 *    can't pass providers because `ProviderRegistry` is built FROM
 *    the same registry; AppContainer second-pass re-registers
 *    SessionActionTool with providers wired in).
 *  - `bus=null` (same shape — production wires it; tests opting
 *    into compaction must provide one).
 */
internal suspend fun executeSessionCompact(
    sessions: SessionStore,
    providers: ProviderRegistry?,
    bus: EventBus?,
    input: SessionActionTool.Input,
    ctx: ToolContext,
): ToolResult<SessionActionTool.Output> {
    val sid = ctx.resolveSessionId(input.sessionId)
    val strategy = CompactionStrategy.parseOrDefault(input.strategy)
    val strategyLabel = strategy.toLabel()

    val resolvedProviders = providers
        ?: error(
            "action=compact requires the SessionActionTool to be constructed with a ProviderRegistry — " +
                "this AppContainer didn't wire one (the first-pass registration can't, because " +
                "ProviderRegistry is built FROM the same registry; AppContainers must re-register " +
                "SessionActionTool with providers in the second pass).",
        )
    val resolvedBus = bus
        ?: error(
            "action=compact requires the SessionActionTool to be constructed with an EventBus — " +
                "this AppContainer didn't wire one. Register the tool with `bus=` so the " +
                "compactor can publish progress events.",
        )

    val session = sessions.getSession(sid)
        ?: error(
            "Session ${sid.value} not found. Call session_query(select=sessions) to discover " +
                "valid session ids.",
        )

    val history = sessions.listMessagesWithParts(sid, includeCompacted = false)
    val lastAssistant = history.lastOrNull { it.message is Message.Assistant }?.message as? Message.Assistant
        ?: return noOpCompact(
            session.id,
            session.title,
            "session has no assistant messages — nothing to compact",
            strategyLabel,
        )

    val model: ModelRef = lastAssistant.model
    val provider = resolvedProviders.get(model.providerId)
        ?: return noOpCompact(
            session.id,
            session.title,
            "session's model '${model.providerId}/${model.modelId}' references a provider " +
                "that is not registered in this container",
            strategyLabel,
        )

    val compactor = Compactor(provider = provider, store = sessions, bus = resolvedBus)
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
            data = SessionActionTool.Output(
                sessionId = sid.value,
                action = "compact",
                title = session.title,
                compacted = true,
                compactPrunedPartCount = result.prunedCount,
                compactPartId = result.partId.value,
                compactSummaryPreview = result.summary.take(200),
                compactStrategy = strategyLabel,
            ),
        )
        is Compactor.Result.Pruned -> ToolResult(
            title = "compact session ${sid.value} (prune-only)",
            outputForLlm = "Pruned ${result.prunedCount} tool output(s) on ${sid.value} " +
                "(prune-only — no summary written).",
            data = SessionActionTool.Output(
                sessionId = sid.value,
                action = "compact",
                title = session.title,
                compacted = true,
                compactPrunedPartCount = result.prunedCount,
                compactStrategy = strategyLabel,
            ),
        )
        is Compactor.Result.Skipped -> ToolResult(
            title = "compact session ${sid.value} (skipped)",
            outputForLlm = "Skipped compaction on ${sid.value}: ${result.reason}.",
            data = SessionActionTool.Output(
                sessionId = sid.value,
                action = "compact",
                title = session.title,
                compacted = false,
                compactSkipReason = result.reason,
                compactStrategy = strategyLabel,
            ),
        )
    }
}

private fun noOpCompact(
    sessionId: SessionId,
    title: String,
    reason: String,
    strategy: String,
): ToolResult<SessionActionTool.Output> = ToolResult(
    title = "compact session ${sessionId.value} (noop)",
    outputForLlm = "No-op on ${sessionId.value}: $reason.",
    data = SessionActionTool.Output(
        sessionId = sessionId.value,
        action = "compact",
        title = title,
        compacted = false,
        compactSkipReason = reason,
        compactStrategy = strategy,
    ),
)

private fun CompactionStrategy.toLabel(): String = when (this) {
    CompactionStrategy.SUMMARIZE_AND_PRUNE -> "summarize_and_prune"
    CompactionStrategy.PRUNE_ONLY -> "prune_only"
}
