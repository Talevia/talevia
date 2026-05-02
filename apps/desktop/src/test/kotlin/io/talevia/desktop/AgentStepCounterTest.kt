package io.talevia.desktop

import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.FallbackHint
import io.talevia.core.bus.BusEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit-pin the step counter state machine extracted from `ChatPanel.kt`'s
 * `BusEvent.AgentRunStateChanged` subscription. Mirrors the CLI's
 * `EventRouter` per-session counter (cycle 166's
 * `small-user-progress-surface` 行为). Cases match the CLI's:
 *   - increment on Idle → Generating edge
 *   - no double-increment on Generating → Generating self-loop
 *   - intermediate states (AwaitingTool / Compacting) don't increment
 *   - re-enter Generating after AwaitingTool → step +1
 *   - terminal Idle (with prev != null) → counter resets to null
 *   - pre-run Idle (no prev) → no reset, no display
 */
class AgentStepCounterTest {

    private val sid = SessionId("s")

    private fun ev(state: AgentRunState): BusEvent.AgentRunStateChanged =
        BusEvent.AgentRunStateChanged(sessionId = sid, state = state)

    @Test fun firstGeneratingEdgeEmitsStepOne() {
        val c = AgentStepCounter()
        assertEquals(1, c.observe(ev(AgentRunState.Generating)))
    }

    @Test fun selfLoopOnGeneratingDoesNotDoubleIncrement() {
        val c = AgentStepCounter()
        assertEquals(1, c.observe(ev(AgentRunState.Generating)))
        // Defensive: state machine does not emit Generating → Generating
        // today, but a future refactor might. Counter must stay at 1.
        assertEquals(1, c.observe(ev(AgentRunState.Generating)))
    }

    @Test fun awaitingToolDoesNotIncrement() {
        val c = AgentStepCounter()
        assertEquals(1, c.observe(ev(AgentRunState.Generating)))
        // Tool dispatch suspends the run — chip stays at "Step 1".
        assertEquals(1, c.observe(ev(AgentRunState.AwaitingTool)))
    }

    @Test fun reEnterGeneratingAfterAwaitingToolBumpsStep() {
        val c = AgentStepCounter()
        assertEquals(1, c.observe(ev(AgentRunState.Generating)))
        assertEquals(1, c.observe(ev(AgentRunState.AwaitingTool)))
        // Tool returned, agent streams again — Step 2.
        assertEquals(2, c.observe(ev(AgentRunState.Generating)))
    }

    @Test fun compactingDoesNotIncrementOrReset() {
        val c = AgentStepCounter()
        assertEquals(1, c.observe(ev(AgentRunState.Generating)))
        assertEquals(1, c.observe(ev(AgentRunState.AwaitingTool)))
        assertEquals(2, c.observe(ev(AgentRunState.Generating)))
        // Compaction passes mid-run — chip stays at "Step 2".
        assertEquals(2, c.observe(ev(AgentRunState.Compacting)))
    }

    @Test fun terminalIdleClearsTheChip() {
        val c = AgentStepCounter()
        c.observe(ev(AgentRunState.Generating))
        c.observe(ev(AgentRunState.AwaitingTool))
        c.observe(ev(AgentRunState.Generating))
        // Run finishes — chip clears.
        assertNull(c.observe(ev(AgentRunState.Idle)))
    }

    @Test fun preRunIdleDoesNotEmitAnything() {
        // Initial state is "no run has happened yet". Some bus pipelines
        // emit a startup `Idle` snapshot; that must not trigger a reset
        // (counter is already at 0) or display anything.
        val c = AgentStepCounter()
        assertNull(c.observe(ev(AgentRunState.Idle)))
    }

    @Test fun newRunAfterIdleStartsFreshAtStepOne() {
        val c = AgentStepCounter()
        c.observe(ev(AgentRunState.Generating))
        c.observe(ev(AgentRunState.AwaitingTool))
        c.observe(ev(AgentRunState.Generating)) // Step 2
        c.observe(ev(AgentRunState.Idle))
        // Next run starts at Step 1, not Step 3.
        assertEquals(1, c.observe(ev(AgentRunState.Generating)))
    }

    @Test fun failedTerminalDoesNotResetButKeepsChip() {
        // Failed is terminal but the user typically wants to see "Step N
        // · failed" — we don't auto-clear (existing CLI surfaces the
        // failure via FailedRender; the chip just stays at Step N until
        // the next run). Subsequent Idle then resets (matches the
        // "terminal Idle clears" rule).
        val c = AgentStepCounter()
        c.observe(ev(AgentRunState.Generating))
        c.observe(ev(AgentRunState.AwaitingTool))
        c.observe(ev(AgentRunState.Generating)) // Step 2
        // Failed: chip stays at 2.
        assertEquals(2, c.observe(ev(AgentRunState.Failed("boom", FallbackHint.Uncaught()))))
        // Then Idle clears.
        assertNull(c.observe(ev(AgentRunState.Idle)))
    }
}
