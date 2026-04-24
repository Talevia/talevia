package io.talevia.core.agent

import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.compaction.TokenEstimator
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.SessionStore

/**
 * Extracted compaction-trigger sub-state-machine of [Agent.runLoop].
 *
 * Each step of `runLoop` has to answer "is the pre-turn history over
 * budget, and if so, compact before dispatching the next provider call?"
 * That question is three-part:
 *  1. Is compaction wired at all? (null compactor → skip.)
 *  2. Is the current history over the per-model threshold?
 *  3. If yes, publish "compacting" state, run [Compactor.process], then
 *     re-read history and publish "generating" state.
 *
 * Keeping this out of [Agent.runLoop] leaves the outer multi-step driver
 * focused on "append user message → loop until no more tool calls" and
 * isolates the compaction event-choreography for future evolution (e.g.
 * manual compaction vs auto-compaction, per-project thresholds). It's
 * the round-2 companion to the earlier [AgentTurnExecutor] extraction
 * — both move concerns out of `Agent` without changing observable
 * behaviour. No LLM-visible surface change.
 */
internal class CompactionGate(
    private val compactor: Compactor?,
    private val compactionThreshold: (ModelRef) -> Int,
    private val store: SessionStore,
    private val bus: EventBus,
) {
    /**
     * Inspect [history] and, if over budget, run the compactor and
     * return the post-compaction re-read history. Otherwise returns
     * [history] unchanged.
     *
     * Matches the pre-extraction inline block in `Agent.runLoop` byte-
     * for-byte — same `TokenEstimator.forHistory` input, same two
     * `BusEvent` publishes around the `compactor.process` call, same
     * re-read after compaction.
     */
    suspend fun maybeCompact(
        input: RunInput,
        handle: AgentRunHandleView,
        history: List<MessageWithParts>,
    ): List<MessageWithParts> {
        if (compactor == null) return history
        val estimated = TokenEstimator.forHistory(history)
        val perModelThreshold = compactionThreshold(input.model)
        if (estimated <= perModelThreshold) return history

        bus.publish(
            BusEvent.SessionCompactionAuto(
                sessionId = input.sessionId,
                historyTokensBefore = estimated,
                thresholdTokens = perModelThreshold,
            ),
        )
        bus.publish(
            BusEvent.AgentRunStateChanged(
                input.sessionId,
                AgentRunState.Compacting,
                retryAttempt = handle.lastRetryAttempt,
            ),
        )
        compactor.process(input.sessionId, history, input.model)
        val next = store.listMessagesWithParts(input.sessionId, includeCompacted = false)
        bus.publish(
            BusEvent.AgentRunStateChanged(
                input.sessionId,
                AgentRunState.Generating,
                retryAttempt = handle.lastRetryAttempt,
            ),
        )
        return next
    }
}

/**
 * View of [Agent.RunHandle] the gate + retry loop need without pulling
 * `Agent.RunHandle` (which is `private class` on `Agent`) out of
 * `Agent`'s scope. [RetryLoop.Handle] extends this with the additional
 * `currentAssistantId` setter it requires. `Agent` wraps its own handle
 * in an anonymous implementation when calling either helper.
 *
 * `lastRetryAttempt` is declared as `var` so [RetryLoop] can mutate it
 * through the interface; the gate only reads it.
 */
internal interface AgentRunHandleView {
    var lastRetryAttempt: Int?
}
