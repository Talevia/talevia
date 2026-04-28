package io.talevia.core.agent

import kotlinx.serialization.Serializable

/**
 * Explicit state machine for a single [Agent.run] invocation.
 *
 * Before this, the Agent's state was implicit — a mix of "which coroutine
 * is executing", "did we hit the compaction branch", "is a tool call
 * suspended". Callers (UI, SSE, revert) had no structural way to ask
 * "what is the agent doing right now?" or "what was it doing when it
 * stopped?".
 *
 * Transitions flow one direction per run:
 *
 * ```
 *                     Generating ──→ AwaitingTool ──→ Generating
 *                        ↑                               │
 *                        └── Compacting ──────────────────┘
 *                             │
 *                             ↓
 *            Idle ──→ run starts ──→ Generating ──→ … ──→ Idle (success)
 *                                                      ──→ Cancelled
 *                                                      ──→ Failed(cause)
 * ```
 *
 * Emitted to the [io.talevia.core.bus.EventBus] as
 * [io.talevia.core.bus.BusEvent.AgentRunStateChanged] on every transition;
 * NOT persisted to the session store this cycle (write amplification
 * per-transition is substantial). Revert support that needs to recover
 * the last pre-failure state is a follow-up — for now, subscribers
 * (`project_query(select=stale_clips)` / UI status bar / SSE) can tail the event stream.
 */
@Serializable
sealed interface AgentRunState {

    /** No run in flight (also the initial "there has never been a run" state). */
    @Serializable
    data object Idle : AgentRunState

    /**
     * Agent is streaming LLM output — text / tool-call / reasoning deltas
     * are arriving. Default state between tool-call suspensions and between
     * compaction passes.
     */
    @Serializable
    data object Generating : AgentRunState

    /**
     * Agent emitted a tool-call part and is now suspended waiting for
     * [io.talevia.core.tool.Tool.execute] to return. Multiple parallel
     * tool calls in the same turn are still reported as a single
     * `AwaitingTool` — the granular per-call events use
     * [io.talevia.core.bus.BusEvent.PartUpdated] on the tool part itself.
     */
    @Serializable
    data object AwaitingTool : AgentRunState

    /**
     * Agent is running context compaction (prune + summarise) because
     * history crossed the auto-trigger threshold. Paired with
     * [io.talevia.core.bus.BusEvent.SessionCompactionAuto] which carries
     * the token counts that caused the trigger.
     */
    @Serializable
    data object Compacting : AgentRunState

    /**
     * Run was cancelled via [Agent.cancel] or an outer coroutine-cancellation
     * reached the run scope. Terminal for this run.
     */
    @Serializable
    data object Cancelled : AgentRunState

    /**
     * Run ended in an error the Agent didn't / couldn't retry. [cause] is
     * the exception's message (or class name when blank) — not the full
     * stack trace so the field stays bus-cheap. Terminal for this run.
     *
     * [fallback] carries structured next-step suggestions classified from
     * [cause] via [FallbackClassifier] — see VISION §5.4 / M3 #5. Default
     * is [FallbackHint.Uncaught] so legacy serialized rows decode (and so
     * test fakes that build `Failed("…")` without thinking about hints
     * still get a non-empty suggestion list).
     */
    @Serializable
    data class Failed(
        val cause: String,
        val fallback: FallbackHint = FallbackHint.Uncaught(),
    ) : AgentRunState
}
