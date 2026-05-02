package io.talevia.desktop

import io.talevia.core.agent.AgentRunState
import io.talevia.core.bus.BusEvent

/**
 * Per-session "Step N · processing…" counter for the desktop chat panel.
 * M7 §4 #3 — desktop mirror of the CLI's `EventRouter` step-progress
 * surface (`apps/cli/.../event/EventRouter.kt`'s `bus.sessionScopedSubscribe<
 * BusEvent.AgentRunStateChanged>` block, cycle 166's
 * `small-user-progress-surface`).
 *
 * Behavior matches CLI exactly so a session moved between CLI and
 * desktop sees the same step numbering:
 * - Increment on the **transition** into [AgentRunState.Generating]
 *   (i.e. `prev !is Generating → state is Generating`). A
 *   `Generating → Generating` self-loop (current state machine doesn't
 *   emit it but a future refactor might) does not double-increment.
 * - Reset to `0` on transition into [AgentRunState.Idle] when there
 *   has been a prior state. Pre-run `Idle` (initial state, no prev)
 *   doesn't reset.
 * - Other states (`AwaitingTool` / `Compacting` / `Cancelled` /
 *   `Failed`) update `prev` but don't change the step. The user sees
 *   them via existing surfaces (per-tool part state, compaction
 *   notice).
 *
 * State-tracking lives outside the `@Composable` so it's directly
 * unit-testable without a Compose runtime. The composable layer wires
 * this to a `mutableStateOf<Int?>` chip so the user sees the latest
 * step number until the run finishes.
 */
internal class AgentStepCounter {

    private var previousState: AgentRunState? = null
    private var step: Int = 0

    /**
     * Feed one `AgentRunStateChanged` event. Returns the step number the UI
     * should display (`null` to clear the chip). The caller is expected to
     * filter events to a single sessionId before calling — this counter
     * doesn't shard internally; multi-session state lives in the call site
     * (existing CLI pattern: `mutableMapOf<SessionId, AgentStepCounter>()`).
     */
    fun observe(event: BusEvent.AgentRunStateChanged): Int? {
        val state = event.state
        var displayStep: Int? = stepOrNull()
        when (state) {
            is AgentRunState.Generating -> {
                if (previousState !is AgentRunState.Generating) {
                    step += 1
                }
                displayStep = step
            }
            is AgentRunState.Idle -> {
                if (previousState != null) {
                    // Run ended — reset counter and clear the chip.
                    step = 0
                    displayStep = null
                }
            }
            else -> {
                // AwaitingTool / Compacting / Cancelled / Failed: keep the
                // chip showing the most recent step (user sees "Step 3 ·
                // processing…" while a tool runs, then back to step
                // increment when the assistant resumes Generating).
            }
        }
        previousState = state
        return displayStep
    }

    /** Currently displayed step number, or `null` when no run in flight. */
    private fun stepOrNull(): Int? = if (step == 0) null else step
}
