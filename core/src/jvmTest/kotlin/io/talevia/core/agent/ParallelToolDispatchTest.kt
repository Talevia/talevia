package io.talevia.core.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionService
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two independent `ToolCallReady` events in the same step must dispatch
 * concurrently (VISION §5.2 / §5.4). Sequential dispatch would turn a turn
 * with N long tools into `sum(t_i)` wall time; parallel dispatch makes it
 * `max(t_i)`. Correctness requires: (a) both tool results land, (b) one
 * tool's failure doesn't cancel its siblings, (c) permission checks are
 * serialised so interactive prompts don't race for the same terminal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParallelToolDispatchTest {

    @Test
    fun twoIndependentToolsDispatchConcurrently() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val concurrency = AtomicInteger(0)
        val maxConcurrency = AtomicInteger(0)
        val slow = SlowTool(concurrency, maxConcurrency, delayMillis = 1_000)
        val registry = ToolRegistry().apply { register(slow) }

        val part1 = PartId("tool-1"); val call1 = CallId("call-1")
        val part2 = PartId("tool-2"); val call2 = CallId("call-2")
        val turn1 = listOf(
            LlmEvent.ToolCallStart(part1, call1, SlowTool.ID),
            LlmEvent.ToolCallStart(part2, call2, SlowTool.ID),
            LlmEvent.ToolCallReady(part1, call1, SlowTool.ID, buildJsonObject { put("label", "a") }),
            LlmEvent.ToolCallReady(part2, call2, SlowTool.ID, buildJsonObject { put("label", "b") }),
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 10, output = 2)),
        )
        val replyPart = PartId("text-1")
        val turn2 = listOf(
            LlmEvent.TextStart(replyPart),
            LlmEvent.TextEnd(replyPart, "done"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 12, output = 5)),
        )
        val provider = FakeProvider(listOf(turn1, turn2))

        val agent = Agent(
            provider = provider,
            registry = registry,
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )

        val before = testScheduler.currentTime
        agent.run(RunInput(sessionId, "do two things", ModelRef("fake", "test")))
        val elapsed = testScheduler.currentTime - before

        // Parallel dispatch: virtual time advances to `max(delay_i)` not
        // `sum(delay_i)`. With two 1 000 ms tools, sequential would take
        // ≥ 2 000 ms; parallel should stay near 1 000 ms.
        assertTrue(
            elapsed < 1_500L,
            "expected parallel dispatch (< 1500 ms virtual), got ${'$'}elapsed ms",
        )
        assertEquals(
            2,
            maxConcurrency.get(),
            "both tools should have been running at the same time at some point",
        )

        val toolParts = store.listSessionParts(sessionId).filterIsInstance<Part.Tool>()
        assertEquals(2, toolParts.size)
        toolParts.forEach { part ->
            val state = part.state
            assertTrue(state is ToolState.Completed, "tool ${'$'}{part.callId.value} should complete; got ${'$'}state")
        }
    }

    @Test
    fun siblingDispatchSurvivesOneFailure() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val concurrency = AtomicInteger(0)
        val maxConcurrency = AtomicInteger(0)
        val registry = ToolRegistry().apply {
            register(SlowTool(concurrency, maxConcurrency, delayMillis = 500))
            register(ThrowingTool())
        }

        val p1 = PartId("a"); val c1 = CallId("a")
        val p2 = PartId("b"); val c2 = CallId("b")
        val turn1 = listOf(
            LlmEvent.ToolCallStart(p1, c1, SlowTool.ID),
            LlmEvent.ToolCallStart(p2, c2, ThrowingTool.ID),
            LlmEvent.ToolCallReady(p1, c1, SlowTool.ID, buildJsonObject { put("label", "slow") }),
            LlmEvent.ToolCallReady(p2, c2, ThrowingTool.ID, buildJsonObject {}),
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 10, output = 2)),
        )
        val replyPart = PartId("text-2")
        val turn2 = listOf(
            LlmEvent.TextStart(replyPart),
            LlmEvent.TextEnd(replyPart, "ok"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage()),
        )
        val provider = FakeProvider(listOf(turn1, turn2))

        val agent = Agent(
            provider = provider,
            registry = registry,
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )
        agent.run(RunInput(sessionId, "run both", ModelRef("fake", "test")))

        val toolParts = store.listSessionParts(sessionId).filterIsInstance<Part.Tool>()
        val byCall = toolParts.associateBy { it.callId }
        val slowState = byCall[c1]!!.state
        val throwState = byCall[c2]!!.state
        assertTrue(slowState is ToolState.Completed, "slow sibling completes despite peer throw; got ${'$'}slowState")
        assertTrue(throwState is ToolState.Failed, "throwing tool surfaces as Failed; got ${'$'}throwState")
    }

    @Test
    fun permissionPromptsSerialiseAcrossConcurrentDispatches() = runTest {
        val (store, bus) = newStore()
        val sessionId = primeSession(store)

        val gate = CompletableDeferred<Unit>()
        val activePermissionChecks = AtomicInteger(0)
        val maxActivePermissionChecks = AtomicInteger(0)
        val permissions = SynchronisingPermissionService(gate, activePermissionChecks, maxActivePermissionChecks)

        val registry = ToolRegistry().apply {
            register(SlowTool(AtomicInteger(), AtomicInteger(), delayMillis = 100))
        }
        val p1 = PartId("x1"); val c1 = CallId("x1")
        val p2 = PartId("x2"); val c2 = CallId("x2")
        val turn1 = listOf(
            LlmEvent.ToolCallStart(p1, c1, SlowTool.ID),
            LlmEvent.ToolCallStart(p2, c2, SlowTool.ID),
            LlmEvent.ToolCallReady(p1, c1, SlowTool.ID, buildJsonObject { put("label", "one") }),
            LlmEvent.ToolCallReady(p2, c2, SlowTool.ID, buildJsonObject { put("label", "two") }),
            LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage()),
        )
        val replyPart = PartId("t2")
        val turn2 = listOf(
            LlmEvent.TextStart(replyPart),
            LlmEvent.TextEnd(replyPart, "done"),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage()),
        )
        val provider = FakeProvider(listOf(turn1, turn2))

        val agent = Agent(
            provider = provider,
            registry = registry,
            store = store,
            permissions = permissions,
            bus = bus,
        )
        // Release the permission gate after dispatch kicks off; with a mutex
        // around permissions.check, the first caller holds the lock while the
        // second suspends — max active == 1. Without a mutex both would
        // enter concurrently and max active would be 2.
        gate.complete(Unit)
        agent.run(RunInput(sessionId, "check perms", ModelRef("fake", "test")))

        assertEquals(
            1,
            maxActivePermissionChecks.get(),
            "permission checks must serialise across concurrent dispatches",
        )
    }

    private class SlowTool(
        private val active: AtomicInteger,
        private val maxActive: AtomicInteger,
        private val delayMillis: Long,
    ) : Tool<SlowTool.Input, SlowTool.Output> {
        @Serializable data class Input(val label: String = "")
        @Serializable data class Output(val echoed: String)

        override val id: String = ID
        override val helpText: String = "Test tool that delays for a configurable period."
        override val inputSerializer: KSerializer<Input> = serializer()
        override val outputSerializer: KSerializer<Output> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("slow.test")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("label") { put("type", "string") }
            }
            put("required", JsonArray(emptyList()))
            put("additionalProperties", false)
        }

        override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
            val now = active.incrementAndGet()
            maxActive.updateAndGet { prev -> if (now > prev) now else prev }
            try {
                delay(delayMillis)
            } finally {
                active.decrementAndGet()
            }
            return ToolResult("slow", "slow:${'$'}{input.label}", Output("slow:${'$'}{input.label}"))
        }

        companion object { const val ID: String = "slow_test" }
    }

    private class ThrowingTool : Tool<ThrowingTool.Input, ThrowingTool.Output> {
        @Serializable class Input
        @Serializable data class Output(val ok: Boolean)

        override val id: String = ID
        override val helpText: String = "Always throws — used to prove sibling dispatch survives one failure."
        override val inputSerializer: KSerializer<Input> = serializer()
        override val outputSerializer: KSerializer<Output> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("throw.test")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
            put("required", JsonArray(emptyList()))
            put("additionalProperties", false)
        }

        override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
            error("boom (test)")
        }

        companion object { const val ID: String = "throwing_test" }
    }

    private class SynchronisingPermissionService(
        private val gate: CompletableDeferred<Unit>,
        private val active: AtomicInteger,
        private val maxActive: AtomicInteger,
    ) : PermissionService {
        override suspend fun check(
            rules: List<PermissionRule>,
            request: PermissionRequest,
        ): PermissionDecision {
            gate.await()
            val now = active.incrementAndGet()
            maxActive.updateAndGet { prev -> if (now > prev) now else prev }
            try {
                // Hold the permission-check window open briefly so a second
                // concurrent caller would overlap if the mutex were missing.
                delay(20)
            } finally {
                active.decrementAndGet()
            }
            return PermissionDecision.Once
        }

        override suspend fun reply(requestId: String, decision: PermissionDecision) = Unit
    }

    private fun newStore(): Pair<SqlDelightSessionStore, EventBus> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        return SqlDelightSessionStore(db, bus) to bus
    }

    private suspend fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("parallel-dispatch")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("proj"),
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
        return sid
    }
}
