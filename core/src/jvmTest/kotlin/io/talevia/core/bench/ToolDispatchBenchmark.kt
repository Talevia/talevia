package io.talevia.core.bench

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.PendingToolCall
import io.talevia.core.agent.RunInput
import io.talevia.core.agent.dispatchToolCall
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Wall-time regression guard for the tool dispatch hot path — the
 * `dispatchToolCall` function that every Agent turn fans out to
 * (permission check → tool body → `store.upsertPart` → bus publish).
 * Complements [AgentLoopBenchmark]: that one measures the end-to-end
 * 5-turn agent loop, this one isolates dispatch-per-call so a
 * regression in the permission / store / bus fan-out surfaces even
 * when the turn-level number stays OK.
 *
 * Scope: 100 back-to-back dispatches of a deterministic no-op tool
 * against the real `SqlDelightSessionStore` + `EventBus` (JDBC
 * in-memory driver). Permission service is `AllowAllPermissionService`
 * so we measure the structural cost of the check, not a real-user
 * dialog. `FakeProvider` isn't used — dispatch is the whole unit of
 * work here.
 *
 * **Budget policy (v1).** Soft budget only — prints wall time with
 * `WARN-OVER-BUDGET` if > [SOFT_BUDGET], otherwise `ok`. Matches
 * sibling benches. Promote once 10+ cycles of data stabilise.
 */
class ToolDispatchBenchmark {

    @Test fun hundredDispatchesBackToBack() = runTest {
        // Warmup — first run pays JIT + class load + schema create.
        runScriptedDispatches(count = 100)
        val elapsed = measureTime { runScriptedDispatches(count = 100) }
        AgentLoopBenchmark.report(name = "tool-dispatch.100-calls", elapsed = elapsed, softBudget = SOFT_BUDGET)
    }

    private suspend fun runScriptedDispatches(count: Int) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)
        val sid = SessionId("bench-dispatch")
        val now = Clock.System.now()
        store.createSession(
            Session(
                id = sid,
                projectId = ProjectId("bench-origin"),
                title = "bench",
                createdAt = now,
                updatedAt = now,
                currentProjectId = null,
            ),
        )

        // Register a single no-op tool that does the bare minimum:
        // returns a canned ToolResult synchronously. The dispatch
        // overhead (permission check + store upsert + bus publish +
        // runCatching) dominates; tool-body cost is near-zero.
        val registry = ToolRegistry().apply { register(NoopDispatchTool) }
        val asstMsg = Message.Assistant(
            id = MessageId("a-bench"),
            sessionId = sid,
            createdAt = now,
            parentId = MessageId("u-bench"),
            model = ModelRef("fake", "bench"),
            finish = FinishReason.TOOL_CALLS,
        )
        store.appendMessage(asstMsg)

        val input = RunInput(sid, "go", ModelRef("fake", "bench"))
        val permissionMutex = Mutex()
        val permissions = AllowAllPermissionService()

        repeat(count) { i ->
            val partId = PartId("tool-$i")
            val callId = CallId("call-$i")
            val event = LlmEvent.ToolCallReady(
                partId,
                callId,
                NoopDispatchTool.id,
                buildJsonObject { put("i", i) },
            )
            dispatchToolCall(
                registry = registry,
                permissions = permissions,
                store = store,
                bus = bus,
                clock = Clock.System,
                metrics = null,
                asstMsg = asstMsg,
                history = emptyList(),
                input = input,
                event = event,
                handle = PendingToolCall(partId, NoopDispatchTool.id),
                currentProjectId = null,
                spendCapCents = null,
                permissionMutex = permissionMutex,
            )
        }
    }

    /** Cheap deterministic Tool — exists purely to exercise the dispatch path. */
    private object NoopDispatchTool : Tool<NoopDispatchTool.Input, NoopDispatchTool.Output> {
        @Serializable data class Input(val i: Int = 0)
        @Serializable data class Output(val echoed: Int)

        override val id: String = "noop_dispatch_bench"
        override val helpText: String = "benchmark-only"
        override val inputSerializer: KSerializer<Input> = serializer()
        override val outputSerializer: KSerializer<Output> = serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("bench.noop")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
        }

        override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> =
            ToolResult(title = "noop", outputForLlm = "${input.i}", data = Output(input.i))
    }

    companion object {
        /**
         * Generous soft budget. Real observed numbers on a recent MacBook
         * Pro sit around 100-300 ms for 100 dispatches; we warn at 1 s so
         * a 3× regression is visible without the test failing when GC
         * happens to stall a run.
         */
        private val SOFT_BUDGET: Duration = 1.seconds
    }
}
