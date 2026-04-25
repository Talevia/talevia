package io.talevia.core.tool.builtin.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.session.TokenUsage
import io.talevia.core.session.ToolState
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.builtin.session.query.StepHistoryRow
import io.talevia.core.tool.query.decodeRowsAs
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Coverage for `session_query(select=step_history)` — flatten the
 * StepStart/StepFinish/Tool interleaving in an Assistant message into
 * a per-step (model, finish, tokens, toolCallCount, elapsedMs)
 * timeline.
 */
class SessionQueryStepHistoryTest {

    private fun ctx(): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { },
        messages = emptyList(),
    )

    private suspend fun newRig(): Triple<SqlDelightSessionStore, SessionId, Instant> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        val store = SqlDelightSessionStore(TaleviaDb(driver), EventBus())
        val sid = SessionId("step-test")
        val now = Clock.System.now()
        store.createSession(
            Session(id = sid, projectId = ProjectId("p"), title = "t", createdAt = now, updatedAt = now),
        )
        return Triple(store, sid, now)
    }

    private suspend fun seedAssistantWithSteps(
        store: SqlDelightSessionStore,
        sid: SessionId,
        msgId: MessageId,
        baseTime: Instant,
        steps: List<TestStep>,
    ) {
        // User parent first so the Assistant.parentId references a real id.
        val userId = MessageId("usr-${msgId.value}")
        store.appendMessage(
            Message.User(
                id = userId,
                sessionId = sid,
                createdAt = baseTime,
                agent = "default",
                model = ModelRef("openai", "gpt-5"),
            ),
        )
        store.appendMessage(
            Message.Assistant(
                id = msgId,
                sessionId = sid,
                createdAt = baseTime,
                parentId = userId,
                model = ModelRef("openai", "gpt-5"),
            ),
        )
        steps.forEachIndexed { i, step ->
            val startAt = baseTime.plus((i * 1000L).milliseconds)
            val finishAt = startAt.plus(step.elapsedMs.milliseconds)
            store.upsertPart(
                Part.StepStart(
                    id = PartId("ps-${msgId.value}-$i"),
                    messageId = msgId,
                    sessionId = sid,
                    createdAt = startAt,
                ),
            )
            // Tool parts created between StepStart and StepFinish.
            repeat(step.toolCallCount) { t ->
                val toolAt = startAt.plus((100 + t * 10).milliseconds)
                store.upsertPart(
                    Part.Tool(
                        id = PartId("pt-${msgId.value}-$i-$t"),
                        messageId = msgId,
                        sessionId = sid,
                        createdAt = toolAt,
                        callId = CallId("c-$i-$t"),
                        toolId = "fake_tool",
                        state = ToolState.Pending,
                    ),
                )
            }
            if (step.finished) {
                store.upsertPart(
                    Part.StepFinish(
                        id = PartId("pf-${msgId.value}-$i"),
                        messageId = msgId,
                        sessionId = sid,
                        createdAt = finishAt,
                        tokens = TokenUsage(input = step.tokensIn, output = step.tokensOut),
                        finish = step.finish,
                    ),
                )
            }
        }
    }

    private data class TestStep(
        val elapsedMs: Long,
        val tokensIn: Long,
        val tokensOut: Long,
        val finish: FinishReason = FinishReason.TOOL_CALLS,
        val toolCallCount: Int = 0,
        val finished: Boolean = true,
    )

    @Test fun threeStepsFlattenWithCorrectIndicesAndTokenSums() = runTest {
        val (store, sid, base) = newRig()
        seedAssistantWithSteps(
            store, sid, MessageId("ast-1"), base,
            steps = listOf(
                TestStep(elapsedMs = 250, tokensIn = 100, tokensOut = 20, toolCallCount = 0),
                TestStep(elapsedMs = 800, tokensIn = 50, tokensOut = 5, toolCallCount = 2),
                TestStep(elapsedMs = 400, tokensIn = 10, tokensOut = 30, finish = FinishReason.END_TURN),
            ),
        )

        val tool = SessionQueryTool(sessions = store)
        val out = tool.execute(
            SessionQueryTool.Input(select = "step_history", sessionId = sid.value),
            ctx(),
        ).data
        assertEquals(3, out.total)
        val rows = out.rows.decodeRowsAs(StepHistoryRow.serializer())
        assertEquals(listOf(0, 1, 2), rows.map { it.stepIndex })
        assertEquals(listOf("openai/gpt-5", "openai/gpt-5", "openai/gpt-5"), rows.map { it.model })
        assertEquals(listOf(250L, 800L, 400L), rows.map { it.elapsedMs })
        assertEquals(listOf(100L, 50L, 10L), rows.map { it.tokensIn })
        assertEquals(listOf(0, 2, 0), rows.map { it.toolCallCount })
        assertEquals(listOf("TOOL_CALLS", "TOOL_CALLS", "END_TURN"), rows.map { it.finishReason })
    }

    @Test fun pendingStepShowsNullElapsedAndFinish() = runTest {
        val (store, sid, base) = newRig()
        seedAssistantWithSteps(
            store, sid, MessageId("ast-pending"), base,
            steps = listOf(
                TestStep(elapsedMs = 200, tokensIn = 50, tokensOut = 5, finished = true),
                TestStep(elapsedMs = 0, tokensIn = 0, tokensOut = 0, finished = false),
            ),
        )

        val tool = SessionQueryTool(sessions = store)
        val rows = tool.execute(
            SessionQueryTool.Input(select = "step_history", sessionId = sid.value),
            ctx(),
        ).data.rows.decodeRowsAs(StepHistoryRow.serializer())
        assertEquals(2, rows.size)
        assertNull(rows[1].elapsedMs, "pending step has no elapsed yet")
        assertNull(rows[1].finishReason)
        assertEquals(0L, rows[1].tokensIn)
    }

    @Test fun messageIdFilterNarrowsToOneTurn() = runTest {
        val (store, sid, base) = newRig()
        seedAssistantWithSteps(
            store, sid, MessageId("ast-A"), base,
            steps = listOf(TestStep(100, 10, 5)),
        )
        seedAssistantWithSteps(
            store, sid, MessageId("ast-B"), base.plus(5000.milliseconds),
            steps = listOf(TestStep(150, 20, 7), TestStep(80, 5, 2)),
        )

        val tool = SessionQueryTool(sessions = store)
        val out = tool.execute(
            SessionQueryTool.Input(
                select = "step_history",
                sessionId = sid.value,
                messageId = "ast-B",
            ),
            ctx(),
        ).data
        assertEquals(2, out.total, "messageId=ast-B narrows to its 2 steps only")
        val rows = out.rows.decodeRowsAs(StepHistoryRow.serializer())
        assertTrue(rows.all { it.messageId == "ast-B" })
    }

    @Test fun emptySessionReturnsCleanZeroRows() = runTest {
        val (store, sid, _) = newRig()
        val out = SessionQueryTool(sessions = store).execute(
            SessionQueryTool.Input(select = "step_history", sessionId = sid.value),
            ctx(),
        ).data
        assertEquals(0, out.total)
    }
}
