package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.AgentRunState
import io.talevia.core.agent.AgentRunStateTracker
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.FileProjectStore
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.domain.Timeline
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guard coverage for `session-project-rebind-mid-run-guard`: when an agent
 * is mid-run on the session (Generating / AwaitingTool / Compacting),
 * `switch_project` must reject rather than silently rebind under the
 * running turn's feet. Terminal states (Idle / Cancelled / Failed) and a
 * session the tracker has never seen both pass. Same-id short-circuits
 * before the gate — a redundant rebind is a no-op regardless of run state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchProjectMidRunGuardTest {

    private val fixedClock: Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    }

    private data class Rig(
        val sessions: SqlDelightSessionStore,
        val projects: FileProjectStore,
        val bus: EventBus,
        val ctx: ToolContext,
    )

    private fun rig(bus: EventBus): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val sessions = SqlDelightSessionStore(db, bus)
        val projects = ProjectStoreTestKit.create()
        val ctx = ToolContext(
            sessionId = SessionId("s"),
            messageId = MessageId("m"),
            callId = CallId("c"),
            askPermission = { PermissionDecision.Once },
            emitPart = { },
            messages = emptyList(),
        )
        return Rig(sessions, projects, bus, ctx)
    }

    private suspend fun seedSession(
        sessions: SqlDelightSessionStore,
        id: String = "s-1",
        currentProjectId: ProjectId? = null,
    ) {
        val past = Instant.fromEpochMilliseconds(1_600_000_000_000L)
        sessions.createSession(
            Session(
                id = SessionId(id),
                projectId = ProjectId("p-origin"),
                title = "Untitled",
                createdAt = past,
                updatedAt = past,
                currentProjectId = currentProjectId,
            ),
        )
    }

    private suspend fun seedProject(projects: FileProjectStore, id: String) {
        projects.upsert(id, Project(id = ProjectId(id), timeline = Timeline()))
    }

    @Test fun rebindIsRejectedDuringGenerating() = runTest {
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val rig = rig(bus)
        seedSession(rig.sessions)
        seedProject(rig.projects, "p-target")

        // Drive the session into Generating via the bus (the Agent would normally
        // do this; in this test we publish directly to the same bus the tracker
        // subscribes to).
        bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-1"), AgentRunState.Generating))
        advanceUntilIdle()
        yield()
        assertEquals(AgentRunState.Generating, tracker.currentState(SessionId("s-1")))

        val tool = SwitchProjectTool(
            rig.sessions, rig.projects, fixedClock, bus = bus, agentStates = tracker,
        )
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-target"),
                rig.ctx,
            )
        }
        assertTrue(
            "generating" in ex.message!!,
            "error must name the mid-run state for debugging: ${ex.message}",
        )
        assertTrue(
            "s-1" in ex.message!!,
            "error must name the session id: ${ex.message}",
        )

        // Session binding unchanged — the gate must reject BEFORE mutating the store.
        val refreshed = rig.sessions.getSession(SessionId("s-1"))!!
        assertEquals(null, refreshed.currentProjectId)
    }

    @Test fun selfRebindDuringAwaitingToolSucceeds() = runTest {
        // Regression: when the model itself calls switch_project from a tool
        // batch, the dispatching session is by definition in `AwaitingTool`
        // — it's executing this very dispatch. Blocking that would mean the
        // model can NEVER call switch_project (tool calls are the only way),
        // which made the CLI's "switch to a different existing project" flow
        // unreachable. The guard's intent is to block EXTERNAL surprise
        // rebinds; self-rebinds from the running session aren't surprises.
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val rig = rig(bus)
        // Use the dispatching session's id (`rig.ctx.sessionId == "s"`) so
        // input.sessionId resolves to the same session — that's the
        // self-rebind path.
        rig.sessions.createSession(
            io.talevia.core.session.Session(
                id = SessionId("s"),
                projectId = ProjectId("p-origin"),
                title = "Untitled",
                createdAt = Instant.fromEpochMilliseconds(1_600_000_000_000L),
                updatedAt = Instant.fromEpochMilliseconds(1_600_000_000_000L),
                currentProjectId = null,
            ),
        )
        seedProject(rig.projects, "p-target")
        bus.publish(BusEvent.AgentRunStateChanged(SessionId("s"), AgentRunState.AwaitingTool))
        advanceUntilIdle()
        yield()
        assertEquals(AgentRunState.AwaitingTool, tracker.currentState(SessionId("s")))

        val tool = SwitchProjectTool(
            rig.sessions, rig.projects, fixedClock, bus = bus, agentStates = tracker,
        )
        // input.sessionId omitted → resolves to ctx.sessionId == "s" (self).
        val out = tool.execute(
            SwitchProjectTool.Input(projectId = "p-target"),
            rig.ctx,
        ).data
        assertEquals("p-target", out.currentProjectId)
        assertTrue(out.changed)
    }

    @Test fun rebindIsRejectedDuringAwaitingTool() = runTest {
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val rig = rig(bus)
        seedSession(rig.sessions)
        seedProject(rig.projects, "p-target")
        bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-1"), AgentRunState.AwaitingTool))
        advanceUntilIdle()
        yield()

        val tool = SwitchProjectTool(
            rig.sessions, rig.projects, fixedClock, bus = bus, agentStates = tracker,
        )
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-target"),
                rig.ctx,
            )
        }
        assertTrue("awaiting_tool" in ex.message!!, ex.message)
    }

    @Test fun rebindIsRejectedDuringCompacting() = runTest {
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val rig = rig(bus)
        seedSession(rig.sessions)
        seedProject(rig.projects, "p-target")
        bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-1"), AgentRunState.Compacting))
        advanceUntilIdle()
        yield()

        val tool = SwitchProjectTool(
            rig.sessions, rig.projects, fixedClock, bus = bus, agentStates = tracker,
        )
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(
                SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-target"),
                rig.ctx,
            )
        }
        assertTrue("compacting" in ex.message!!, ex.message)
    }

    @Test fun rebindSucceedsWhenIdle() = runTest {
        // Positive control: once the agent transitions to Idle (run finished),
        // switch_project must succeed.
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val rig = rig(bus)
        seedSession(rig.sessions)
        seedProject(rig.projects, "p-target")
        bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-1"), AgentRunState.Generating))
        bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-1"), AgentRunState.Idle))
        advanceUntilIdle()
        yield()
        assertEquals(AgentRunState.Idle, tracker.currentState(SessionId("s-1")))

        val tool = SwitchProjectTool(
            rig.sessions, rig.projects, fixedClock, bus = bus, agentStates = tracker,
        )
        val out = tool.execute(
            SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-target"),
            rig.ctx,
        ).data
        assertEquals("p-target", out.currentProjectId)
        assertTrue(out.changed)
    }

    @Test fun rebindSucceedsWhenTrackerHasNeverSeenSession() = runTest {
        // Sessions created outside the agent-run flow (CLI `open_project` +
        // immediate `switch_project` before any agent.run) never appear in the
        // tracker. Null-state must pass the gate — otherwise first-time binding
        // would be blocked by the guard meant to protect mid-run rebinds.
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val rig = rig(bus)
        seedSession(rig.sessions)
        seedProject(rig.projects, "p-target")
        assertEquals(null, tracker.currentState(SessionId("s-1")))

        val tool = SwitchProjectTool(
            rig.sessions, rig.projects, fixedClock, bus = bus, agentStates = tracker,
        )
        val out = tool.execute(
            SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-target"),
            rig.ctx,
        ).data
        assertTrue(out.changed)
    }

    @Test fun rebindSucceedsWhenTrackerIsNull() = runTest {
        // Legacy composition (and existing SwitchProjectToolTest rigs) don't
        // inject a tracker. The guard must NO-OP rather than fail, preserving
        // backwards compatibility.
        val rig = rig(EventBus())
        seedSession(rig.sessions)
        seedProject(rig.projects, "p-target")

        val tool = SwitchProjectTool(
            rig.sessions, rig.projects, fixedClock, // no bus, no tracker
        )
        val out = tool.execute(
            SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-target"),
            rig.ctx,
        ).data
        assertTrue(out.changed)
    }

    @Test fun sameIdNoOpShortCircuitsBeforeGate() = runTest {
        // §3a #9 counter-intuitive edge: calling switch_project with the
        // session's existing projectId while Generating must succeed as a
        // no-op. The same-id check is BEFORE the gate in the flow, so nothing
        // can block it — important because UI surfaces may redundantly call
        // switch_project on every render, and a generating-session's UI
        // re-open shouldn't produce errors.
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val rig = rig(bus)
        seedSession(rig.sessions, currentProjectId = ProjectId("p-target"))
        seedProject(rig.projects, "p-target")
        bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-1"), AgentRunState.Generating))
        advanceUntilIdle()
        yield()

        val tool = SwitchProjectTool(
            rig.sessions, rig.projects, fixedClock, bus = bus, agentStates = tracker,
        )
        val out = tool.execute(
            SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-target"),
            rig.ctx,
        ).data
        assertTrue(!out.changed, "same-id during Generating must be a no-op, not a gate-trip")
        assertEquals("p-target", out.currentProjectId)
    }

    @Test fun rebindSucceedsAfterCancel() = runTest {
        // Cancelled is a terminal state: the run is done, a rebind is safe.
        val bus = EventBus()
        val tracker = AgentRunStateTracker(bus, backgroundScope)
        advanceUntilIdle()
        yield()
        val rig = rig(bus)
        seedSession(rig.sessions)
        seedProject(rig.projects, "p-target")
        bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-1"), AgentRunState.Generating))
        bus.publish(BusEvent.AgentRunStateChanged(SessionId("s-1"), AgentRunState.Cancelled))
        advanceUntilIdle()
        yield()

        val tool = SwitchProjectTool(
            rig.sessions, rig.projects, fixedClock, bus = bus, agentStates = tracker,
        )
        val out = tool.execute(
            SwitchProjectTool.Input(sessionId = "s-1", projectId = "p-target"),
            rig.ctx,
        ).data
        assertTrue(out.changed)
    }
}
