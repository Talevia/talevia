package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.domain.ProjectStoreTestKit
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.source.SourceNodeActionTool
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end test of the greenfield onboarding path: with the onboarding
 * lane in the system prompt, the scripted `source_node_action(add,
 * style_bible)` tool call lands successfully via the real
 * [SourceNodeActionTool], the project picks up the new source node, and
 * the onboarding lane disappears from the *next* turn's system prompt
 * (greenfield gate turned off by the scaffold).
 *
 * Companion to [GreenfieldOnboardingAgentTest] — that one verifies the
 * lane is injected given the greenfield state. This one closes the loop
 * by verifying the agent + registered tool + ProjectStore cooperate so
 * the onboarding advice the lane gives the LLM is actually *actionable*
 * (i.e. `source_node_action(add)` works against a greenfield project
 * without extra setup).
 */
class GreenfieldOnboardingE2ETest {

    @Test
    fun scaffoldingStyleBibleExitsGreenfieldGate() = runTest {
        val (sessionStore, bus) = newSessionStore()
        val projects = ProjectStoreTestKit.create()
        val project = projects.createAt("/projects/bench-onboarding".toPath(), title = "onboarding-e2e")
        val sid = primeSession(sessionStore, currentProjectId = project.id)

        // Turn 1: LLM dispatches source_node_action(add style_bible).
        val tPart1 = PartId("tool-1")
        val cid1 = CallId("call-1")
        val styleInput = buildJsonObject {
            put("projectId", project.id.value)
            put("action", "add")
            put("nodeId", "style-warm")
            put("kind", "core.consistency.style_bible")
            putJsonObject("body") {
                put("name", "cinematic-warm")
                put("description", "warm teal/orange, 35mm feel")
            }
        }
        val turn1 = listOf(
            LlmEvent.ToolCallStart(tPart1, cid1, "source_node_action"),
            LlmEvent.ToolCallReady(tPart1, cid1, "source_node_action", styleInput),
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 1, output = 1)),
        )

        // Turn 2: LLM reads the tool result and closes with a text.
        val turn2 = listOf(
            LlmEvent.TextStart(PartId("t-final")),
            LlmEvent.TextEnd(PartId("t-final"), "style_bible scaffolded; ready to generate."),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 1, output = 1)),
        )

        val provider = FakeProvider(listOf(turn1, turn2))
        val registry = ToolRegistry().apply { register(SourceNodeActionTool(projects)) }
        val agent = Agent(
            provider = provider,
            registry = registry,
            store = sessionStore,
            permissions = AllowAllPermissionService(),
            bus = bus,
            systemPrompt = "BASE_PROMPT_BODY",
            projects = projects,
        )
        agent.run(RunInput(sid, "make me a vlog", ModelRef("fake", "test")))

        // Assert: turn 1's request had the onboarding lane (greenfield at
        // dispatch time), turn 2's did NOT (the style_bible scaffold
        // landed, flipping the gate).
        val requests = provider.requests
        assertEquals(2, requests.size, "expected exactly two LLM turn requests, got ${requests.size}")

        assertTrue(
            "# Greenfield onboarding" in requests[0].systemPrompt!!,
            "turn-1 system prompt must carry the onboarding lane (project was greenfield at dispatch)",
        )
        assertFalse(
            "# Greenfield onboarding" in requests[1].systemPrompt!!,
            "turn-2 system prompt must NOT carry the onboarding lane — the style_bible scaffold from turn 1 flipped the greenfield gate off",
        )

        // Assert: the scripted tool call actually mutated the project.
        val fresh = projects.get(project.id)!!
        assertEquals(1, fresh.source.nodes.size, "source_node_action(add) must have added exactly one node")
        val node = fresh.source.nodes.single()
        assertEquals("style-warm", node.id.value)
        assertEquals("core.consistency.style_bible", node.kind)
    }

    private fun newSessionStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(
        store: SqlDelightSessionStore,
        currentProjectId: ProjectId,
    ): SessionId {
        val sid = SessionId("onb-e2e-session")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("proj-origin"),
                title = "e2e",
                createdAt = now,
                updatedAt = now,
                currentProjectId = currentProjectId,
            ),
        )
        return sid
    }
}
