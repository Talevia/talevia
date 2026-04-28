package io.talevia.core.agent

import io.talevia.core.bus.BusEvent
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.session.query.computeToolSpecBudget

/**
 * Runtime watchdog for the tool-spec token budget. On every Agent run start
 * it estimates the registry-wide token cost (same heuristic as
 * `session_query(select=tool_spec_budget)`) and emits a single
 * [BusEvent.ToolSpecBudgetWarning] on each upward threshold crossing —
 * never repeated while the registry stays continuously over.
 *
 * Why hysteresis: a registry that stays at 19k tokens shouldn't spam a
 * warning every turn (the LLM context surface didn't get worse just
 * because another turn started). Only the under→over edge is actionable;
 * subsequent turns are already known-over, and the user (or audit test)
 * has had their notification.
 *
 * State is per-monitor — composition roots wire one instance into the
 * Agent. Tests can construct fresh instances and assert the edge
 * behavior without coordinating with a long-lived process.
 *
 * Concurrency note: the over/under flag is a plain `var`. Two
 * concurrent Agent.run invocations on different sessions might both
 * observe `lastWasOver = false` and both emit a warning — the cost is
 * one duplicate notification per crossing across all sessions, which
 * is benign for a non-critical user-facing warning. The monitor
 * self-corrects on the next call (whichever write wins, subsequent
 * checks see `true` and stop emitting). A Mutex would shield the
 * single edge but inject a coroutine suspension into the run-start
 * hot path; the trade isn't worth it for an at-most-one-extra-event
 * race.
 */
class ToolSpecBudgetMonitor(
    private val threshold: Int = DEFAULT_THRESHOLD,
) {
    private var lastWasOver: Boolean = false

    /**
     * Compute the current registry budget and decide whether to emit a
     * warning. Returns the event to publish, or `null` when no edge fired.
     * Caller is expected to publish (`bus.publish(event)`) and not retain
     * the returned object — the monitor's own state is the source of truth
     * for "did we already warn?".
     *
     * `null` registry → no estimate possible → no event. Callers that
     * never wire a registry get a silent monitor; the runtime path is
     * still correct because the audit test catches a zero-spec regression
     * separately.
     */
    fun check(registry: ToolRegistry?): BusEvent.ToolSpecBudgetWarning? {
        if (registry == null) return null
        val row = computeToolSpecBudget(registry)
        val isOver = row.estimatedTokens > threshold
        return if (isOver && !lastWasOver) {
            lastWasOver = true
            BusEvent.ToolSpecBudgetWarning(
                estimatedTokens = row.estimatedTokens,
                threshold = threshold,
                toolCount = row.toolCount,
            )
        } else {
            // Either still over (already-warned, no spam) or under (clear
            // the flag so the next upward crossing fires fresh).
            if (!isOver) lastWasOver = false
            null
        }
    }

    companion object {
        /**
         * Soft-warning threshold, chosen to match
         * `ToolSpecBudgetAuditTest`'s 18k token gate (cycle-167 67de5f53).
         * Below this the registry is comfortably affordable; above, a
         * Prometheus alert / UI banner is justified.
         */
        const val DEFAULT_THRESHOLD: Int = 18_000
    }
}
