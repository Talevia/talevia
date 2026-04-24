package io.talevia.core.bench

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.agent.FakeProvider
import io.talevia.core.bus.EventBus
import io.talevia.core.compaction.Compactor
import io.talevia.core.db.TaleviaDb
import io.talevia.core.provider.LlmEvent
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.MessageWithParts
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

/**
 * Wall-time regression guard for [Compactor.process] on a realistic long
 * session. Scope: a 200-message scripted history (100 user / 100 assistant
 * turns) carrying ~100 completed-tool parts of varying size, driven through
 * the real `SqlDelightSessionStore` so the `markPartCompacted` SQL updates
 * and `upsertPart(Part.Compaction)` path are exercised on actual rows — what
 * we are measuring is the prune-candidate scan + SQL update batch + the
 * no-op summary round-trip via [FakeProvider]. Provider cost is stubbed out
 * at zero so a regression shows up as pure coordination / storage overhead.
 *
 * **Budget policy (v1).** Soft budget only — prints wall time with a
 * WARN-OVER-BUDGET marker if it exceeds [SOFT_BUDGET] (5s), otherwise
 * `ok`. Matches [AgentLoopBenchmark] and §R.6 #4 ("初版 budget 仅 warning，
 * 不 fail") — promote once 10+ cycles of data stabilise.
 *
 * Why 200 messages: sits comfortably above the `protectUserTurns = 2`
 * default so the pre-window zone actually has dozens of Tool-part drop
 * candidates to sort by cost. Lower-count fixtures (~10 messages) would
 * noop out of the candidate sort loop and measure the wrong hot path.
 */
class CompactionBenchmark {

    @Test fun twoHundredMessageSessionWarmup() = runTest {
        // One warm-up run so SQLite schema create / JDBC class load / the
        // first JIT compilation of `prune` do not fold into the
        // measurement — mirrors the AgentLoopBenchmark.warmup pattern.
        runScriptedCompaction(messageCount = 200)
        val elapsed = measureTime { runScriptedCompaction(messageCount = 200) }
        AgentLoopBenchmark.report(name = "compaction.200-msg", elapsed = elapsed, softBudget = SOFT_BUDGET)
    }

    private suspend fun runScriptedCompaction(messageCount: Int) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val db = TaleviaDb(driver)
        val bus = EventBus()
        val store = SqlDelightSessionStore(db, bus)
        val sid = SessionId("bench-compaction")
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

        val history = buildHistory(sid, messageCount)
        history.forEach { mwp ->
            store.appendMessage(mwp.message)
            mwp.parts.forEach { store.upsertPart(it) }
        }

        val summaryTurn = listOf(
            LlmEvent.TextStart(PartId("bench-summary-text")),
            LlmEvent.TextEnd(PartId("bench-summary-text"), BENCH_SUMMARY_BODY),
            LlmEvent.StepFinish(FinishReason.END_TURN, TokenUsage(input = 50, output = 20)),
        )
        val provider = FakeProvider(listOf(summaryTurn))
        val compactor = Compactor(
            provider = provider,
            store = store,
            bus = bus,
            // Defaults: protectUserTurns=2, pruneProtectTokens=40_000. The
            // history we build fits inside 40k total so without the override
            // the drop-candidate scan would early-exit. Tightening the budget
            // to 4_000 forces the prune loop to do actual sorting work, which
            // is what we want to time.
            pruneProtectTokens = 4_000,
        )

        val result = compactor.process(sid, history, ModelRef("fake", "bench"))
        check(result is Compactor.Result.Compacted) { "bench expects a Compacted outcome, got $result" }
    }

    private fun buildHistory(sid: SessionId, messageCount: Int): List<MessageWithParts> {
        val now = kotlinx.datetime.Instant.fromEpochMilliseconds(0)
        val model = ModelRef("fake", "bench")
        val pairs = messageCount / 2
        val out = ArrayList<MessageWithParts>(messageCount)
        for (i in 0 until pairs) {
            val userId = MessageId("u-$i")
            val user = Message.User(
                id = userId,
                sessionId = sid,
                createdAt = now,
                agent = "default",
                model = model,
            )
            val userText = Part.Text(
                id = PartId("u-$i-t"),
                messageId = userId,
                sessionId = sid,
                createdAt = now,
                text = "turn-$i user prompt".padEnd(200, '.'),
            )
            out += MessageWithParts(user, listOf(userText))

            val asstId = MessageId("a-$i")
            val asst = Message.Assistant(
                id = asstId,
                sessionId = sid,
                createdAt = now,
                parentId = userId,
                model = model,
                finish = FinishReason.TOOL_CALLS,
            )
            val asstParts = mutableListOf<Part>()
            asstParts += Part.Text(
                id = PartId("a-$i-t"),
                messageId = asstId,
                sessionId = sid,
                createdAt = now,
                text = "turn-$i assistant reasoning".padEnd(250, '.'),
            )
            // Alternate-size Tool parts so prune has a non-trivial sort.
            // `outputForLlm` sizes picked to span two orders of magnitude
            // (600 char ↔ 6000 char) without inflating the fixture to
            // megabytes — same shape as CompactorTest.pruneDropsBiggest.
            val bigOutput = (i % 2 == 0)
            val toolPartLen = if (bigOutput) 6_000 else 600
            asstParts += Part.Tool(
                id = PartId("tool-$i"),
                messageId = asstId,
                sessionId = sid,
                createdAt = now,
                callId = CallId("c-$i"),
                toolId = "echo",
                state = ToolState.Completed(
                    input = JsonObject(mapOf("i" to JsonPrimitive(i))),
                    outputForLlm = "x".repeat(toolPartLen),
                    data = JsonObject(emptyMap()),
                    estimatedTokens = toolPartLen / 4,
                ),
            )
            out += MessageWithParts(asst, asstParts)
        }
        return out
    }

    companion object {
        /**
         * 5 s is 10× the observed local-run number on a recent MacBook Pro
         * (~400–500 ms for 200-message scripted compaction). A genuine
         * regression at that scale will cross the threshold; normal noise
         * (GC stalls, JDBC connect variance) won't.
         */
        private val SOFT_BUDGET: Duration = 5.seconds

        private val BENCH_SUMMARY_BODY: String = """
            Goal: finish editing the video
            Discoveries: none — synthetic bench fixture
            Accomplished: many tool calls replayed
            Current Timeline State: empty
            Open Questions: none
        """.trimIndent()
    }
}
