package io.talevia.core.tool.builtin

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.EventBus
import io.talevia.core.db.TaleviaDb
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Message
import io.talevia.core.session.ModelRef
import io.talevia.core.session.Part
import io.talevia.core.session.PlanApprovalStatus
import io.talevia.core.session.PlanStep
import io.talevia.core.session.PlanStepStatus
import io.talevia.core.session.Session
import io.talevia.core.session.SqlDelightSessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExecutePlanToolTest {

    private val now: Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val sid = SessionId("s-plan")
    private val pid = ProjectId("p-plan")
    private val mid = MessageId("m-plan")

    @Serializable data class StubInput(val value: String = "")
    @Serializable data class StubOutput(val echoed: String, val step: String)

    /** Records each dispatched input so tests can verify order + payloads. */
    private class StubTool(
        override val id: String,
        val shouldFail: Boolean = false,
    ) : Tool<StubInput, StubOutput> {
        val dispatched = mutableListOf<StubInput>()
        override val helpText: String = "stub"
        override val inputSerializer: KSerializer<StubInput> = StubInput.serializer()
        override val outputSerializer: KSerializer<StubOutput> = StubOutput.serializer()
        override val permission: PermissionSpec = PermissionSpec.fixed("echo")
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("value") { put("type", "string") }
            }
            put("required", JsonArray(emptyList()))
        }

        override suspend fun execute(input: StubInput, ctx: ToolContext): ToolResult<StubOutput> {
            dispatched += input
            if (shouldFail) error("simulated failure in $id (${input.value})")
            return ToolResult(
                title = "stub-$id",
                outputForLlm = "stub $id ran with ${input.value}",
                data = StubOutput(echoed = input.value, step = id),
            )
        }
    }

    private fun freshSessions(): SqlDelightSessionStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TaleviaDb.Schema.create(driver)
        return SqlDelightSessionStore(TaleviaDb(driver), EventBus())
    }

    private suspend fun seedSession(sessions: SqlDelightSessionStore) {
        sessions.createSession(
            Session(
                id = sid,
                projectId = pid,
                title = "t",
                createdAt = now,
                updatedAt = now,
            ),
        )
        // parent of the assistant message: synthesise a user anchor.
        val userId = MessageId("u-anchor")
        sessions.appendMessage(
            Message.User(
                id = userId,
                sessionId = sid,
                createdAt = now,
                agent = "test",
                model = ModelRef("test", "test"),
            ),
        )
        sessions.appendMessage(
            Message.Assistant(
                id = mid,
                sessionId = sid,
                createdAt = now,
                parentId = userId,
                model = ModelRef("test", "test"),
            ),
        )
    }

    private fun stepInput(value: String): JsonObject = buildJsonObject { put("value", JsonPrimitive(value)) }

    private suspend fun emitPlan(
        sessions: SqlDelightSessionStore,
        planId: String,
        approval: PlanApprovalStatus,
        steps: List<PlanStep>,
    ) {
        sessions.upsertPart(
            Part.Plan(
                id = PartId(planId),
                messageId = mid,
                sessionId = sid,
                createdAt = now,
                goalDescription = "stub plan",
                steps = steps,
                approvalStatus = approval,
            ),
        )
    }

    private fun ctx(): ToolContext = ToolContext(
        sessionId = sid,
        messageId = MessageId("dispatcher-msg"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { /* ignored in test; assertions read sessions directly */ },
        messages = emptyList(),
    )

    @Test fun refusesExecutionWhenPlanNotApproved() = runTest {
        val sessions = freshSessions()
        seedSession(sessions)
        emitPlan(
            sessions, "plan-1", PlanApprovalStatus.PENDING_APPROVAL,
            listOf(PlanStep(1, "echo", "step 1", input = stepInput("a"))),
        )
        val stub = StubTool("echo")
        val registry = ToolRegistry().apply { register(stub) }
        val tool = ExecutePlanTool(registry, sessions)

        val ex = assertFailsWith<IllegalArgumentException> {
            tool.execute(ExecutePlanTool.Input(planId = "plan-1"), ctx())
        }
        assertTrue("pending_approval" in ex.message.orEmpty())
        assertEquals(0, stub.dispatched.size, "must not dispatch when unapproved")
    }

    @Test fun approvedPlanDispatchesEveryStepInOrder() = runTest {
        val sessions = freshSessions()
        seedSession(sessions)
        val stub1 = StubTool("echo-a")
        val stub2 = StubTool("echo-b")
        val stub3 = StubTool("echo-c")
        val registry = ToolRegistry().apply {
            register(stub1); register(stub2); register(stub3)
        }
        emitPlan(
            sessions, "plan-ok", PlanApprovalStatus.APPROVED,
            listOf(
                PlanStep(1, "echo-a", "first", input = stepInput("a")),
                PlanStep(2, "echo-b", "second", input = stepInput("b")),
                PlanStep(3, "echo-c", "third", input = stepInput("c")),
            ),
        )
        // A real dispatch path needs `emitPart` to update the Plan via sessions;
        // inject a ctx that routes emitPart through the session store.
        val tool = ExecutePlanTool(registry, sessions)
        val result = tool.execute(
            ExecutePlanTool.Input(planId = "plan-ok"),
            ToolContext(
                sessionId = sid,
                messageId = MessageId("dispatch"),
                callId = CallId("c"),
                askPermission = { PermissionDecision.Once },
                emitPart = { p -> sessions.upsertPart(p) },
                messages = emptyList(),
            ),
        ).data
        assertEquals(3, result.totalSteps)
        assertEquals(3, result.executedSteps)
        assertEquals(0, result.skippedSteps)
        assertNull(result.failedAtStep)
        // Verify dispatch order + payloads reached the stubs.
        assertEquals(listOf("a"), stub1.dispatched.map { it.value })
        assertEquals(listOf("b"), stub2.dispatched.map { it.value })
        assertEquals(listOf("c"), stub3.dispatched.map { it.value })
        // Verify Plan was re-emitted with terminal COMPLETED status.
        val latest = sessions.getPart(PartId("plan-ok")) as Part.Plan
        assertTrue(latest.steps.all { it.status == PlanStepStatus.COMPLETED })
    }

    @Test fun stepWithoutInputIsSkippedButDoesNotHalt() = runTest {
        val sessions = freshSessions()
        seedSession(sessions)
        val stub = StubTool("echo-a")
        val stub2 = StubTool("echo-b")
        val registry = ToolRegistry().apply { register(stub); register(stub2) }
        emitPlan(
            sessions, "plan-skip", PlanApprovalStatus.APPROVED,
            listOf(
                PlanStep(1, "echo-a", "first", input = stepInput("a")),
                PlanStep(2, "echo-b", "second, preview only", input = null),
                PlanStep(3, "echo-a", "third", input = stepInput("third")),
            ),
        )
        val tool = ExecutePlanTool(registry, sessions)
        val result = tool.execute(
            ExecutePlanTool.Input(planId = "plan-skip"),
            ToolContext(sid, MessageId("m"), CallId("c"), { PermissionDecision.Once }, { p -> sessions.upsertPart(p) }, emptyList()),
        ).data
        assertEquals(2, result.executedSteps)
        assertEquals(1, result.skippedSteps)
        assertNull(result.failedAtStep)
        // stub-a ran twice (steps 1 and 3); stub-b didn't run (no input).
        assertEquals(listOf("a", "third"), stub.dispatched.map { it.value })
        assertEquals(0, stub2.dispatched.size)
    }

    @Test fun failedStepHaltsRemaining() = runTest {
        val sessions = freshSessions()
        seedSession(sessions)
        val stubA = StubTool("echo-a")
        val stubFail = StubTool("echo-b", shouldFail = true)
        val stubAfter = StubTool("echo-c")
        val registry = ToolRegistry().apply {
            register(stubA); register(stubFail); register(stubAfter)
        }
        emitPlan(
            sessions, "plan-fail", PlanApprovalStatus.APPROVED,
            listOf(
                PlanStep(1, "echo-a", "ok", input = stepInput("a")),
                PlanStep(2, "echo-b", "will fail", input = stepInput("boom")),
                PlanStep(3, "echo-c", "never runs", input = stepInput("c")),
            ),
        )
        val tool = ExecutePlanTool(registry, sessions)
        val result = tool.execute(
            ExecutePlanTool.Input(planId = "plan-fail"),
            ToolContext(sid, MessageId("m"), CallId("c"), { PermissionDecision.Once }, { p -> sessions.upsertPart(p) }, emptyList()),
        ).data
        assertEquals(1, result.executedSteps)
        assertEquals(2, result.failedAtStep)
        // Step 3 must NOT have been dispatched.
        assertEquals(0, stubAfter.dispatched.size)
        val latest = sessions.getPart(PartId("plan-fail")) as Part.Plan
        assertEquals(PlanStepStatus.COMPLETED, latest.steps[0].status)
        assertEquals(PlanStepStatus.FAILED, latest.steps[1].status)
        assertEquals(PlanStepStatus.CANCELLED, latest.steps[2].status)
        assertNotNull(latest.steps[1].note)
    }

    @Test fun dryRunDoesNotDispatchOrMutatePlan() = runTest {
        val sessions = freshSessions()
        seedSession(sessions)
        val stub = StubTool("echo")
        val registry = ToolRegistry().apply { register(stub) }
        emitPlan(
            sessions, "plan-dry", PlanApprovalStatus.APPROVED_WITH_EDITS,
            listOf(
                PlanStep(1, "echo", "first", input = stepInput("a")),
                PlanStep(2, "echo", "second", input = stepInput("b")),
            ),
        )
        val tool = ExecutePlanTool(registry, sessions)
        val before = sessions.getPart(PartId("plan-dry")) as Part.Plan

        val result = tool.execute(
            ExecutePlanTool.Input(planId = "plan-dry", dryRun = true),
            ToolContext(sid, MessageId("m"), CallId("c"), { PermissionDecision.Once }, { p -> sessions.upsertPart(p) }, emptyList()),
        ).data

        assertTrue(result.dryRun)
        assertEquals(0, result.executedSteps)
        assertEquals(0, stub.dispatched.size, "dryRun must not dispatch")
        // Plan part unchanged (same step statuses).
        val after = sessions.getPart(PartId("plan-dry")) as Part.Plan
        assertEquals(before.steps.map { it.status }, after.steps.map { it.status })
    }

    @Test fun missingPlanPartErrorsCleanly() = runTest {
        val sessions = freshSessions()
        seedSession(sessions)
        val registry = ToolRegistry()
        val tool = ExecutePlanTool(registry, sessions)
        val ex = assertFailsWith<IllegalStateException> {
            tool.execute(ExecutePlanTool.Input(planId = "nope"), ctx())
        }
        assertTrue("No Part found" in ex.message.orEmpty())
    }

    @Test fun missingToolMarksStepSkippedNotFailed() = runTest {
        // Tool not registered in this container → should be skipped, not
        // treated as a dispatch failure that halts the batch.
        val sessions = freshSessions()
        seedSession(sessions)
        val stubKeep = StubTool("echo-keep")
        val registry = ToolRegistry().apply { register(stubKeep) }
        emitPlan(
            sessions, "plan-missing-tool", PlanApprovalStatus.APPROVED,
            listOf(
                PlanStep(1, "echo-gone", "provider removed", input = stepInput("a")),
                PlanStep(2, "echo-keep", "still there", input = stepInput("b")),
            ),
        )
        val tool = ExecutePlanTool(registry, sessions)
        val result = tool.execute(
            ExecutePlanTool.Input(planId = "plan-missing-tool"),
            ToolContext(sid, MessageId("m"), CallId("c"), { PermissionDecision.Once }, { p -> sessions.upsertPart(p) }, emptyList()),
        ).data
        assertEquals(1, result.executedSteps)
        assertEquals(1, result.skippedSteps)
        assertNull(result.failedAtStep)
        assertEquals(listOf("b"), stubKeep.dispatched.map { it.value })
    }
}
