package io.talevia.core.agent

import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.logging.Logger
import io.talevia.core.logging.info
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.SessionStore
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Extracted retry + provider-fallback sub-state-machine of [Agent.runLoop].
 *
 * Per-step structure: start a fresh assistant message, stream one turn,
 * and if the turn terminated with ERROR + retriable + no content, retry
 * up to [RetryPolicy.maxAttempts] with backoff. When same-provider
 * retries exhaust AND a fallback provider exists, advance the provider
 * chain and reset attempt count — mirrors OpenCode's `session/llm.ts`
 * two-tier model (retry for transient blips, fallback for sustained
 * outages).
 *
 * Keeping this out of [Agent.runLoop] isolates the state machine from
 * the step-level outer driver so the two concerns evolve
 * independently: adding a new retriable error classifier, a new delay-
 * kind, or a circuit-breaker lives here; the step / compaction / user-
 * message appending stays in `Agent`. Round-2 companion of the earlier
 * [AgentTurnExecutor] extraction.
 *
 * Behaviourally byte-identical to the pre-extraction inline block —
 * same state variables, same ordering of emits, same `deleteMessage`
 * cleanup on retry / fallback.
 */
internal class RetryLoop(
    private val executor: AgentTurnExecutor,
    private val retryPolicy: RetryPolicy,
    private val store: SessionStore,
    private val bus: EventBus,
    private val clock: Clock,
    private val log: Logger,
) {

    /** Ordered deps a single-turn dispatch needs — read from session snapshot once per step. */
    internal data class SessionSnapshot(
        val currentProjectId: ProjectId?,
        val spendCapCents: Long?,
        val disabledToolIds: Set<String>,
    )

    /**
     * Dispatch one step (with retries and provider-fallback) and return
     * the final `(assistant message, turn result)` pair. The returned
     * assistant message is NOT yet finalized with tokens / finish /
     * error — the caller ([Agent.runLoop]) folds those in before
     * persisting via `updateMessage`.
     *
     * [handle] is mutated: `lastRetryAttempt` bumps on each retry (read
     * by [AgentTurnExecutor.streamTurn] to stamp subsequent bus events)
     * and `currentAssistantId` is set to the latest assistant message
     * id so `Agent.cancel` can finalize the correct row.
     */
    @OptIn(ExperimentalUuidApi::class)
    suspend fun runStepWithRetry(
        input: RunInput,
        handle: Handle,
        history: List<MessageWithParts>,
        parentUserMsgId: MessageId,
        snapshot: SessionSnapshot,
    ): Pair<Message.Assistant, TurnResult> {
        var providerIndex = 0
        var attempt = 0
        var asstMsg: Message.Assistant
        var turnResult: TurnResult
        while (true) {
            attempt++
            asstMsg = Message.Assistant(
                id = MessageId(Uuid.random().toString()),
                sessionId = input.sessionId,
                createdAt = clock.now(),
                parentId = parentUserMsgId,
                model = input.model,
            )
            store.appendMessage(asstMsg)
            handle.currentAssistantId = asstMsg.id

            turnResult = executor.streamTurn(
                asstMsg = asstMsg,
                history = history,
                input = input,
                currentProjectId = snapshot.currentProjectId,
                providerIndex = providerIndex,
                spendCapCents = snapshot.spendCapCents,
                disabledToolIds = snapshot.disabledToolIds,
                retryAttempt = handle.lastRetryAttempt,
            )

            // Only retry when the turn failed and nothing useful was streamed.
            // Mid-stream errors (rare — an error event after text/tool_calls) are
            // preserved so the user sees the partial output.
            if (turnResult.finish != FinishReason.ERROR) break
            if (turnResult.emittedContent) break
            val reason = RetryClassifier.reason(turnResult.error, turnResult.retriable) ?: break

            // Same-provider retry budget exhausted AND a fallback provider is
            // configured → advance the chain. Mirrors OpenCode's
            // `session/llm.ts` two-tier model: retry covers the same provider's
            // transient blips, fallback covers sustained per-provider outages
            // (a whole cloud down, a whole account rate-limited, etc.).
            if (attempt >= retryPolicy.maxAttempts) {
                val nextIndex = providerIndex + 1
                if (nextIndex >= executor.providerCount) break
                val fromId = executor.providerIdAt(providerIndex)
                val toId = executor.providerIdAt(nextIndex)
                log.info(
                    "provider.fallback",
                    "session" to input.sessionId.value,
                    "from" to fromId,
                    "to" to toId,
                    "reason" to reason,
                )
                bus.publish(
                    BusEvent.AgentProviderFallback(
                        sessionId = input.sessionId,
                        fromProviderId = fromId,
                        toProviderId = toId,
                        reason = reason,
                    ),
                )
                runCatching { store.deleteMessage(asstMsg.id) }
                providerIndex = nextIndex
                attempt = 0
                continue
            }

            val kind = RetryClassifier.kind(turnResult.error, turnResult.retriable)
            val wait = retryPolicy.delayFor(attempt, turnResult.retryAfterMs, kind)
            log.info(
                "retry.scheduled",
                "session" to input.sessionId.value,
                "attempt" to attempt,
                "waitMs" to wait,
                "reason" to reason,
            )
            bus.publish(
                BusEvent.AgentRetryScheduled(
                    sessionId = input.sessionId,
                    attempt = attempt,
                    waitMs = wait,
                    reason = reason,
                ),
            )
            // Stamp the handle so subsequent AgentRunStateChanged emits
            // (inside executor.streamTurn, the compaction transitions, and
            // the terminal Idle/Failed/Cancelled in run()) can correlate
            // their state with this retry attempt. Monotonic across steps
            // and provider fallbacks within one run — resetting would hide
            // "retry #N succeeded after 2 steps" from the terminal emit.
            handle.lastRetryAttempt = attempt
            // Wipe the failed assistant message (+ its StepFinish(ERROR) part)
            // so the retry produces a clean single message per turn.
            runCatching { store.deleteMessage(asstMsg.id) }
            delay(wait)
        }
        return asstMsg to turnResult
    }

    /**
     * Mutation handle the retry loop needs: wraps the two fields of
     * [Agent.RunHandle] we touch from here. Agent adapts its own
     * handle into this view (see `Agent.run` call site). Inherits
     * `var lastRetryAttempt` from [AgentRunHandleView].
     */
    internal interface Handle : AgentRunHandleView {
        var currentAssistantId: MessageId?
    }
}
