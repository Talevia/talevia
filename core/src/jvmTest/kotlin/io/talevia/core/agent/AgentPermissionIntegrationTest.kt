package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.DefaultPermissionService
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRule
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wires the Agent loop with [DefaultPermissionService] (the production service,
 * not [io.talevia.core.permission.AllowAllPermissionService]) and exercises the
 * three decision shapes the agent must respect:
 *
 *  - DENY → ToolState.Failed("Permission denied: ...") and the run still finalises.
 *  - ASK ↦ Once → tool runs normally.
 *  - ASK ↦ Reject → ToolState.Failed and the bus emits PermissionReplied(accepted=false).
 *
 * Existing PermissionServiceTest covers the rule evaluator in isolation; this
 * test guards the seam between Agent.dispatchTool and the permission service.
 */
class AgentPermissionIntegrationTest {

    @Test
    fun denyRulePersistsAsFailedAndRunFinalises() = runTest {
        val rig = newRig()
        val sessionId = rig.primeSession()

        val asst = rig.agent.run(
            RunInput(
                sessionId = sessionId,
                text = "echo with deny",
                model = ModelRef("fake", "test"),
                permissionRules = listOf(PermissionRule("echo", "*", PermissionAction.DENY)),
            ),
        )

        assertEquals(FinishReason.END_TURN, asst.finish, "run should still finalise after permission denial")
        val toolPart = rig.store.listSessionParts(sessionId).filterIsInstance<Part.Tool>().single()
        val state = toolPart.state
        assertTrue(state is ToolState.Failed, "expected Failed, got $state")
        assertEquals("Permission denied: echo", (state as ToolState.Failed).message)
    }

    @Test
    fun askResolvedToOnceRunsTool() = runTest {
        val rig = newRig()
        val sessionId = rig.primeSession()

        val runJob = async {
            rig.agent.run(
                RunInput(sessionId, "echo with ask", ModelRef("fake", "test")),
            )
        }
        val asked = rig.bus.subscribe<BusEvent.PermissionAsked>().first()
        assertEquals("echo", asked.permission)
        rig.service.reply(asked.requestId, PermissionDecision.Once)
        val asst = runJob.await()

        assertEquals(FinishReason.END_TURN, asst.finish)
        val toolPart = rig.store.listSessionParts(sessionId).filterIsInstance<Part.Tool>().single()
        val state = toolPart.state
        assertTrue(state is ToolState.Completed, "expected Completed, got $state")
        assertEquals("ping", (state as ToolState.Completed).outputForLlm)
    }

    @Test
    fun askResolvedToRejectFailsTool() = runTest {
        val rig = newRig()
        val sessionId = rig.primeSession()

        val replied = async { rig.bus.subscribe<BusEvent.PermissionReplied>().first() }
        val runJob = async {
            rig.agent.run(
                RunInput(sessionId, "echo deny via ask", ModelRef("fake", "test")),
            )
        }
        val asked = rig.bus.subscribe<BusEvent.PermissionAsked>().first()
        rig.service.reply(asked.requestId, PermissionDecision.Reject)
        val asst = runJob.await()
        val replyEvent = replied.await()

        assertEquals(FinishReason.END_TURN, asst.finish)
        assertEquals(false, replyEvent.accepted, "PermissionReplied should mark this as not accepted")

        val toolPart = rig.store.listSessionParts(sessionId).filterIsInstance<Part.Tool>().single()
        val state = toolPart.state
        assertTrue(state is ToolState.Failed, "expected Failed, got $state")
        assertEquals("Permission denied: echo", (state as ToolState.Failed).message)
    }

    private class Rig(
        val store: SqlDelightSessionStore,
        val bus: EventBus,
        val service: DefaultPermissionService,
        val agent: Agent,
    ) {
        suspend fun primeSession(): SessionId {
            val sid = SessionId("perm-${(0..1_000_000).random()}")
            val now = Clock.System.now()
            store.createSession(
                Session(
                    id = sid,
                    projectId = ProjectId("proj"),
                    title = "test",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            return sid
        }
    }

    private fun newRig(): Rig {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)
        val service = DefaultPermissionService(bus)
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val partId = PartId("tool-perm")
        val callId = CallId("call-perm")
        val turn = listOf(
            LlmEvent.ToolCallStart(partId, callId, "echo"),
            LlmEvent.ToolCallReady(partId, callId, "echo", buildJsonObject { put("text", "ping") }),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 5, output = 1)),
        )
        val provider = FakeProvider(listOf(turn))
        val agent = Agent(
            provider = provider,
            registry = registry,
            store = store,
            permissions = service,
            bus = bus,
        )
        return Rig(store, bus, service, agent)
    }
}
