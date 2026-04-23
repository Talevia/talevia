package io.talevia.benchmark

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.Agent
import io.talevia.core.agent.RunInput
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.AllowAllPermissionService
import io.talevia.core.provider.LlmEvent
import io.talevia.core.provider.LlmProvider
import io.talevia.core.provider.LlmRequest
import io.talevia.core.provider.ModelInfo
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.builtin.EchoTool
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.openjdk.jmh.annotations.Level
import java.util.concurrent.TimeUnit

/**
 * Wall-time baseline for [Agent.run] driving a 10-turn sequence through the
 * full orchestration path — stream events, dispatch tool, persist results,
 * loop into the next step, until the scripted END_TURN.
 *
 * Shape: 9 tool-call turns (each calls [EchoTool] and expects a follow-up
 * from the provider) + 1 final END_TURN turn = 10 LLM turns per
 * [agent.run]. Uses real [SqlDelightSessionStore] on an in-memory JDBC
 * driver — no mocking — so the benchmark catches regressions that sneak
 * into either the agent loop or the SQLDelight write path.
 *
 * `@Setup(Level.Invocation)` rebuilds the provider + store + agent per
 * measured call because [InProcessFakeProvider] drains a scripted queue
 * once. The setup cost (driver schema create + one session insert) is
 * outside the timed region so the baseline number captures agent-loop
 * behaviour, not SQL setup.
 *
 * Real numbers (Anthropic Opus 4.7 / M3 Max, JDK 21, kotlinx-benchmark
 * 0.4.13, warmups=2 × 1s, iterations=3 × 1s) measured at infra-landing
 * time: see `docs/decisions/2026-04-23-debt-add-benchmark-agent-loop.md`.
 * A CI diff showing +20% over baseline means the agent loop has
 * regressed; expected next action is bisect + fix.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
class AgentLoopBenchmark {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: SqlDelightSessionStore
    private lateinit var bus: EventBus
    private var sessionId: SessionId = SessionId("uninit")
    private lateinit var agent: Agent

    @Setup(Level.Invocation)
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        bus = EventBus()
        store = SqlDelightSessionStore(db, bus)
        sessionId = primeSession(store)

        val registry = ToolRegistry().apply { register(EchoTool()) }
        val provider = ScriptedEchoProvider(turns = TURNS)

        agent = Agent(
            provider = provider,
            registry = registry,
            store = store,
            permissions = AllowAllPermissionService(),
            bus = bus,
        )
    }

    @Benchmark
    fun tenTurnLoop(): Message.Assistant = runBlocking {
        agent.run(RunInput(sessionId, "go", ModelRef("fake", "bench")))
    }

    private fun primeSession(store: SqlDelightSessionStore): SessionId {
        val sid = SessionId("bench-session")
        val now = Clock.System.now()
        runBlocking {
            store.createSession(
                Session(
                    id = sid,
                    projectId = ProjectId("bench-proj"),
                    title = "bench",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        return sid
    }

    companion object {
        /** 10 LLM turns: 9 tool-call round-trips + 1 final END_TURN. */
        private val TURNS: List<List<LlmEvent>> = buildTurns()

        private fun buildTurns(): List<List<LlmEvent>> {
            val turns = mutableListOf<List<LlmEvent>>()
            repeat(9) { i ->
                val partId = PartId("bench-tool-$i")
                val callId = CallId("bench-call-$i")
                turns += listOf(
                    LlmEvent.ToolCallStart(partId, callId, "echo"),
                    LlmEvent.ToolCallReady(
                        partId,
                        callId,
                        "echo",
                        buildJsonObject { put("text", "ping-$i") },
                    ),
                    LlmEvent.StepFinish(
                        FinishReason.TOOL_CALLS,
                        TokenUsage(input = 10, output = 3),
                    ),
                )
            }
            val finalText = PartId("bench-final")
            turns += listOf(
                LlmEvent.TextStart(finalText),
                LlmEvent.TextEnd(finalText, "done"),
                LlmEvent.StepFinish(
                    FinishReason.END_TURN,
                    TokenUsage(input = 10, output = 2),
                ),
            )
            return turns
        }
    }
}

/**
 * Benchmark-local scripted provider. Intentionally duplicated from
 * `core/src/commonTest/kotlin/.../FakeProvider.kt` — that one lives in the
 * commonTest source set, which isn't a published artifact, and reaching
 * into another module's test sources from a benchmark would couple the
 * benchmark module to the test lifecycle. A 10-line duplicate is cheaper
 * than building a `test-fixtures` shared artifact for a single call site.
 */
private class ScriptedEchoProvider(
    turns: List<List<LlmEvent>>,
    override val id: String = "fake",
) : LlmProvider {
    private val turnQueue = ArrayDeque(turns)
    override suspend fun listModels(): List<ModelInfo> = emptyList()
    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val events = turnQueue.removeFirstOrNull()
            ?: error("ScriptedEchoProvider exhausted (script length mismatch)")
        for (e in events) emit(e)
    }
}
