package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Coverage for [io.talevia.core.session.Session.systemPromptOverride] —
 * the per-session escape hatch that lets a single Agent host sessions
 * with different personas without spinning up a second Agent (VISION
 * §5.4 control surface).
 *
 * Asserts on the [io.talevia.core.provider.LlmRequest.systemPrompt]
 * captured by [FakeProvider] — that's the byte-level surface the LLM
 * actually sees, so we're testing what reaches the model rather than
 * an intermediate layer.
 */
class AgentSystemPromptOverrideTest {

    @Test
    fun overridingPromptReplacesAgentDefault() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store, override = "ROLE: code reviewer")

        val provider = FakeProvider(listOf(oneTextTurn("ack")))
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            systemPrompt = "DEFAULT: video editor",
        )

        agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))

        val captured = provider.requests.single().systemPrompt
        assertNotNull(captured, "request should carry a system prompt")
        assertTrue(
            "ROLE: code reviewer" in captured,
            "session override should reach the model (got: $captured)",
        )
        assertFalse(
            "DEFAULT: video editor" in captured,
            "Agent default should NOT leak when an override is set (got: $captured)",
        )
    }

    @Test
    fun nullOverrideFallsBackToAgentDefault() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store, override = null)

        val provider = FakeProvider(listOf(oneTextTurn("ack")))
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            systemPrompt = "DEFAULT: video editor",
        )

        agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))

        val captured = provider.requests.single().systemPrompt
        assertNotNull(captured)
        assertTrue(
            "DEFAULT: video editor" in captured,
            "no-override session should see Agent default verbatim (got: $captured)",
        )
    }

    @Test
    fun overrideMutationMidRunTakesEffectNextTurn() = runTest {
        // The Agent re-reads the session each step, so a tool call (or
        // any other writer) that flips `systemPromptOverride` mid-run
        // must take effect on the very next turn — without the caller
        // having to re-construct the Agent.
        val (store, bus) = newStore()
        val sessionId = primeSession(store, override = null)

        val provider = FakeProvider(
            listOf(
                oneTextTurn("first ack", finish = FinishReason.END_TURN),
                oneTextTurn("second ack", finish = FinishReason.END_TURN),
            ),
        )
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            systemPrompt = "DEFAULT: video editor",
        )

        agent.run(RunInput(sessionId, "first", ModelRef("fake", "test")))

        // Mid-conversation, swap the override directly on the store —
        // simulates `session_action(action=set_system_prompt)` having
        // landed during a prior tool dispatch.
        val current = store.getSession(sessionId)!!
        store.updateSession(
            current.copy(systemPromptOverride = "ROLE: code reviewer"),
        )

        agent.run(RunInput(sessionId, "second", ModelRef("fake", "test")))

        val first = provider.requests[0].systemPrompt!!
        val second = provider.requests[1].systemPrompt!!
        assertTrue("DEFAULT: video editor" in first, "first turn before override")
        assertTrue("ROLE: code reviewer" in second, "second turn after override")
        assertFalse("DEFAULT: video editor" in second, "default must not leak after override applied")
    }

    @Test
    fun emptyStringOverrideIsLegitimateNoPrompt() = runTest {
        // Empty string is intentionally distinct from null: the user
        // wants this session to run with no Agent-default content. The
        // banner (project + session lines) still renders because the
        // composer always includes it, but the Agent's own default
        // prompt body is absent.
        val (store, bus) = newStore()
        val sessionId = primeSession(store, override = "")

        val provider = FakeProvider(listOf(oneTextTurn("ack")))
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            systemPrompt = "DEFAULT: video editor",
        )

        agent.run(RunInput(sessionId, "hi", ModelRef("fake", "test")))

        val captured = provider.requests.single().systemPrompt!!
        assertFalse(
            "DEFAULT: video editor" in captured,
            "empty-string override must silence the Agent default (got: $captured)",
        )
        // Banner still present — the override only suppresses the base prompt.
        assertTrue("Current session" in captured, "session banner stays even with empty override")
    }

    @Test
    fun overrideSurvivesPersistenceRoundTrip() = runTest {
        // Field is stored on the SQL blob via kotlinx.serialization with
        // a default — re-reading the session must round-trip the value.
        val (store, _) = newStore()
        val sid = primeSession(store, override = "ROLE: persisted reviewer")
        val reread = store.getSession(sid)
        assertNotNull(reread)
        assertTrue(reread.systemPromptOverride == "ROLE: persisted reviewer")
    }

    private fun oneTextTurn(text: String, finish: FinishReason = FinishReason.END_TURN): List<LlmEvent> {
        val partId = PartId("text-${text.hashCode()}")
        return listOf(
            LlmEvent.TextStart(partId),
            LlmEvent.TextDelta(partId, text),
            LlmEvent.TextEnd(partId, text),
            LlmEvent.StepFinish(finish, TokenUsage(input = 1, output = 1)),
        )
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(
        store: SqlDelightSessionStore,
        override: String?,
    ): SessionId {
        val sid = SessionId("sys-prompt-override-test")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("proj"),
                title = "test",
                createdAt = now,
                updatedAt = now,
                systemPromptOverride = override,
            ),
        )
        return sid
    }
}
