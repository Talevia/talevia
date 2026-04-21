package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end: Agent.run reads `Session.currentProjectId` and
 *   a) injects a `Current project: …` banner at the top of every turn's system prompt
 *   b) threads it into `ToolContext.currentProjectId` when dispatching tools.
 */
class SessionProjectBindingTest {

    @Test
    fun systemPromptCarriesNoneBannerWhenSessionIsUnbound() = runTest {
        val (store, bus) = newStore()
        val sid = primeSession(store, currentProjectId = null)

        val turn = listOf(
            LlmEvent.TextStart(PartId("t")),
            LlmEvent.TextEnd(PartId("t"), "ok"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 1, output = 1)),
        )
        val provider = FakeProvider(listOf(turn))
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            systemPrompt = "BASE_PROMPT_BODY",
        )
        agent.run(RunInput(sid, "hi", ModelRef("fake", "test")))

        val sp = provider.requests.single().systemPrompt
        assertNotNull(sp)
        assertTrue(sp!!.startsWith("Current project: <none>"), "expected none-banner, got:\n$sp")
        assertTrue(sp.contains("BASE_PROMPT_BODY"), "base prompt preserved below banner:\n$sp")
    }

    @Test
    fun systemPromptCarriesBoundBanner() = runTest {
        val (store, bus) = newStore()
        val sid = primeSession(store, currentProjectId = ProjectId("p-vlog"))

        val turn = listOf(
            LlmEvent.TextStart(PartId("t")),
            LlmEvent.TextEnd(PartId("t"), "ok"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 1, output = 1)),
        )
        val provider = FakeProvider(listOf(turn))
        val agent = Agent(
            provider = provider,
            registry = ToolRegistry(),
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
            systemPrompt = "BASE_PROMPT_BODY",
        )
        agent.run(RunInput(sid, "hi", ModelRef("fake", "test")))

        val sp = provider.requests.single().systemPrompt
        assertNotNull(sp)
        assertTrue(
            sp!!.startsWith("Current project: p-vlog"),
            "expected bound banner, got:\n$sp",
        )
    }

    @Test
    fun toolContextSeesSessionBindingAtDispatch() = runTest {
        val (store, bus) = newStore()
        val sid = primeSession(store, currentProjectId = ProjectId("p-story"))

        // Fake tool that captures the ToolContext.currentProjectId it sees.
        val spy = BindingCaptureTool()
        val registry = ToolRegistry().apply { register(spy) }

        val tPart = PartId("tool-part")
        val cid = CallId("call-1")
        val turn1 = listOf(
            LlmEvent.ToolCallStart(tPart, cid, "capture_binding"),
            LlmEvent.ToolCallReady(tPart, cid, "capture_binding", buildJsonObject { put("noop", true) }),
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 1, output = 1)),
        )
        val turn2 = listOf(
            LlmEvent.TextStart(PartId("t2")),
            LlmEvent.TextEnd(PartId("t2"), "done"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 1, output = 1)),
        )
        val provider = FakeProvider(listOf(turn1, turn2))
        val agent = Agent(
            provider = provider,
            registry = registry,
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )

        agent.run(RunInput(sid, "go", ModelRef("fake", "test")))

        assertEquals(ProjectId("p-story"), spy.captured)
    }

    @Test
    fun toolContextSeesNullBindingWhenSessionUnbound() = runTest {
        val (store, bus) = newStore()
        val sid = primeSession(store, currentProjectId = null)

        val spy = BindingCaptureTool()
        val registry = ToolRegistry().apply { register(spy) }

        val tPart = PartId("tool-part")
        val cid = CallId("call-2")
        val turn1 = listOf(
            LlmEvent.ToolCallStart(tPart, cid, "capture_binding"),
            LlmEvent.ToolCallReady(tPart, cid, "capture_binding", buildJsonObject { put("noop", true) }),
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 1, output = 1)),
        )
        val turn2 = listOf(
            LlmEvent.TextStart(PartId("t2")),
            LlmEvent.TextEnd(PartId("t2"), "done"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 1, output = 1)),
        )
        val provider = FakeProvider(listOf(turn1, turn2))
        val agent = Agent(
            provider = provider,
            registry = registry,
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )
        agent.run(RunInput(sid, "go", ModelRef("fake", "test")))

        assertNull(spy.captured)
        assertTrue(spy.dispatched, "tool must have actually run")
    }

    /** Tool that records the ToolContext.currentProjectId it observed. */
    private class BindingCaptureTool : Tool<BindingCaptureTool.Input, BindingCaptureTool.Output> {
        @Serializable data class Input(val noop: Boolean = true)
        @Serializable data class Output(val seen: String?)

        var captured: ProjectId? = null
            private set
        var dispatched: Boolean = false
            private set

        override val id: String = "capture_binding"
        override val helpText: String = "test-only"
        override val inputSerializer: KSerializer<Input> = serializer()
        override val outputSerializer: KSerializer<Output> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("test.capture")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
        }

        override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
            dispatched = true
            captured = ctx.currentProjectId
            return ToolResult(
                title = "captured",
                outputForLlm = ctx.currentProjectId?.value ?: "<none>",
                data = Output(seen = ctx.currentProjectId?.value),
            )
        }
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
        currentProjectId: ProjectId?,
    ): SessionId {
        val sid = SessionId("binding-session")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("proj-origin"),
                title = "test",
                createdAt = now,
                updatedAt = now,
                currentProjectId = currentProjectId,
            ),
        )
        return sid
    }
}
