package io.talevia.core.bench

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.Agent
import io.talevia.core.agent.FakeProvider
import io.talevia.core.agent.RunInput
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Wall-time regression guard for the agent loop's hot path.
 *
 * Scope: one fixed synthetic session — 5 turns, each dispatching a no-op
 * tool — exercised through the real [Agent.run] / [AgentTurnExecutor]
 * orchestration against an in-memory [SqlDelightSessionStore]. What we
 * measure is the coordination overhead (stream-plumbing, part persistence,
 * permission check, bus publish, supervisorScope wiring) because provider
 * latency is stubbed out at zero — the moment *any* of those four start
 * costing materially more, the printed wall time moves and a reviewer sees
 * it in the next `:core:jvmTest` run's console.
 *
 * **Budget policy (v1).** This test never fails. It prints wall time and a
 * soft-warning line when it exceeds [SOFT_BUDGET]; actual gating is
 * deferred until we have 10+ cycles of data to set a real threshold.
 * Rationale matches the §R.6 #4 bullet text ("初版 budget 仅 warning，不
 * fail") — fail-fast budgets on a fresh benchmark generate false-positive
 * noise before the signal stabilises, and a noisy gate gets disabled
 * rather than tightened.
 *
 * Follow-up (once the printed numbers settle across a few machines): promote
 * the warning into an assert and add a p95-over-N-runs harness — filed as
 * `debt-promote-agent-loop-benchmark` if it isn't picked up organically.
 */
class AgentLoopBenchmark {

    @Test fun fiveTurnSyntheticSessionWarmup() = runTest {
        // One untimed warm-up run so JIT / class loading / SQLite schema
        // create / kotlinx.serialization reflection init are not folded
        // into the measurement. Without it, the first measured number is
        // consistently 3–5× higher than steady state on every machine we
        // tried, which would make regression detection useless.
        runScriptedSession(turnCount = 5)
        val elapsed = measureTime { runScriptedSession(turnCount = 5) }
        report(name = "agent-loop.5-turn", elapsed = elapsed, softBudget = SOFT_BUDGET)
    }

    private suspend fun runScriptedSession(turnCount: Int) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)
        val sid = SessionId("bench-session")
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

        val registry = ToolRegistry().apply { register(NoopBenchTool) }
        val turns = buildList {
            // Turns 1..N-1 each dispatch one no-op tool and request another
            // turn. The final turn emits text + end-turn so the agent loop
            // exits without an extra provider call.
            repeat(turnCount - 1) { idx ->
                val partId = PartId("tool-$idx")
                val callId = CallId("call-$idx")
                add(
                    listOf(
                        LlmEvent.ToolCallStart(partId, callId, NoopBenchTool.id),
                        LlmEvent.ToolCallReady(
                            partId,
                            callId,
                            NoopBenchTool.id,
                            buildJsonObject { put("i", idx) },
                        ),
                        LlmEvent.StepFinish(FinishReason.TOOL_CALLS, TokenUsage(input = 1, output = 1)),
                    ),
                )
            }
            add(
                listOf(
                    LlmEvent.TextStart(PartId("t-final")),
                    LlmEvent.TextEnd(PartId("t-final"), "done"),
                    LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 1, output = 1)),
                ),
            )
        }
        val provider = FakeProvider(turns)
        val agent = Agent(
            provider = provider,
            registry = registry,
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )
        agent.run(RunInput(sid, "go", ModelRef("fake", "bench")))
    }

    /** Cheap deterministic Tool — exists purely to exercise the dispatch path. */
    private object NoopBenchTool : Tool<NoopBenchTool.Input, NoopBenchTool.Output> {
        @Serializable data class Input(val i: Int = 0)
        @Serializable data class Output(val echoed: Int)

        override val id: String = "noop_bench"
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
         * Generous soft budget. Real observed numbers on CI were <200 ms for
         * this scenario; we warn at 2 s so a 10× regression is visible
         * without the test failing when GC happens to stall a run.
         */
        private val SOFT_BUDGET: Duration = 2.seconds

        fun report(name: String, elapsed: Duration, softBudget: Duration) {
            val marker = if (elapsed > softBudget) "WARN-OVER-BUDGET" else "ok"
            println("[bench] $name elapsed=${elapsed} softBudget=${softBudget} ($marker)")
        }
    }
}
