package io.talevia.core.tool.builtin

import io.talevia.core.CallId
import io.talevia.core.MessageId
import io.talevia.core.SessionId
import io.talevia.core.permission.PermissionDecision
import io.talevia.core.session.Part
import io.talevia.core.session.PlanApprovalStatus
import io.talevia.core.session.PlanStepStatus
import io.talevia.core.tool.ToolContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `draft_plan`:
 *  - emits a [Part.Plan] with the provided goal + numbered steps;
 *  - rejects empty / blank inputs loudly (§3a rule 9);
 *  - echoes approvalStatus + counts pending steps for LLM-side
 *    introspection;
 *  - renders a scannable textual preview with status markers.
 */
class DraftPlanToolTest {

    private fun ctx(emitted: MutableList<Part>): ToolContext = ToolContext(
        sessionId = SessionId("s"),
        messageId = MessageId("m"),
        callId = CallId("c"),
        askPermission = { PermissionDecision.Once },
        emitPart = { p -> emitted += p },
        messages = emptyList(),
    )

    @Test fun emitsPartPlanWithNumberedSteps() = runTest {
        val emitted = mutableListOf<Part>()
        val tool = DraftPlanTool()

        val result = tool.execute(
            DraftPlanTool.Input(
                goalDescription = "Make a 30s graduation vlog",
                steps = listOf(
                    DraftPlanTool.PlanStepInput("generate_image", "prompt=\"grad photo\" 1024×1024"),
                    DraftPlanTool.PlanStepInput("synthesize_speech", "voice=alloy, text=\"congrats\""),
                    DraftPlanTool.PlanStepInput("add_clip", "assetId=img-1, at 0.0s"),
                ),
            ),
            ctx(emitted),
        )

        val plan = emitted.single() as Part.Plan
        assertEquals("Make a 30s graduation vlog", plan.goalDescription)
        assertEquals(3, plan.steps.size)
        assertEquals(listOf(1, 2, 3), plan.steps.map { it.step })
        assertEquals("generate_image", plan.steps[0].toolName)
        assertEquals(PlanApprovalStatus.PENDING_APPROVAL, plan.approvalStatus)
        assertTrue(plan.steps.all { it.status == PlanStepStatus.PENDING })

        assertEquals(3, result.data.stepCount)
        assertEquals(3, result.data.pendingStepCount)
        assertEquals(PlanApprovalStatus.PENDING_APPROVAL, result.data.approvalStatus)
        assertEquals(plan.id.value, result.data.partId)
    }

    @Test fun rejectsEmptyStepsList() = runTest {
        val emitted = mutableListOf<Part>()
        val tool = DraftPlanTool()
        val e = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                DraftPlanTool.Input(
                    goalDescription = "do something",
                    steps = emptyList(),
                ),
                ctx(emitted),
            )
        }
        assertTrue(e.message?.contains("steps must not be empty") == true)
        assertTrue(emitted.isEmpty(), "no Part emitted on failure")
    }

    @Test fun rejectsBlankGoal() = runTest {
        val emitted = mutableListOf<Part>()
        val tool = DraftPlanTool()
        assertFailsWith<IllegalArgumentException> {
            tool.execute(
                DraftPlanTool.Input(
                    goalDescription = "   ",
                    steps = listOf(DraftPlanTool.PlanStepInput("t", "x")),
                ),
                ctx(emitted),
            )
        }
    }

    @Test fun rejectsBlankToolNameInStep() = runTest {
        val emitted = mutableListOf<Part>()
        val tool = DraftPlanTool()
        val e = assertFailsWith<IllegalArgumentException> {
            tool.execute(
                DraftPlanTool.Input(
                    goalDescription = "g",
                    steps = listOf(
                        DraftPlanTool.PlanStepInput("valid_tool", "ok"),
                        DraftPlanTool.PlanStepInput("   ", "bad"),
                    ),
                ),
                ctx(emitted),
            )
        }
        assertTrue(e.message?.contains("step 2") == true, "error must pinpoint the offending step: ${e.message}")
    }

    @Test fun countsPendingWhenSomeStepsAlreadyComplete() = runTest {
        // Simulates a mid-execution plan re-emission: step 1 done, step 2 running, step 3 pending.
        val emitted = mutableListOf<Part>()
        val tool = DraftPlanTool()
        val out = tool.execute(
            DraftPlanTool.Input(
                goalDescription = "mid-run",
                steps = listOf(
                    DraftPlanTool.PlanStepInput("t-1", "a", status = PlanStepStatus.COMPLETED),
                    DraftPlanTool.PlanStepInput("t-2", "b", status = PlanStepStatus.IN_PROGRESS),
                    DraftPlanTool.PlanStepInput("t-3", "c", status = PlanStepStatus.PENDING),
                ),
                approvalStatus = PlanApprovalStatus.APPROVED,
            ),
            ctx(emitted),
        )
        val plan = emitted.single() as Part.Plan
        assertEquals(PlanApprovalStatus.APPROVED, plan.approvalStatus)
        assertEquals(1, out.data.pendingStepCount, "only step 3 is pending")
    }

    @Test fun rendersTextPreviewWithStatusMarkers() {
        val tool = DraftPlanTool()
        val rendered = tool.renderForLlm(
            goal = "demo",
            steps = listOf(
                io.talevia.core.session.PlanStep(1, "tool_a", "first", PlanStepStatus.COMPLETED),
                io.talevia.core.session.PlanStep(2, "tool_b", "second", PlanStepStatus.IN_PROGRESS),
                io.talevia.core.session.PlanStep(3, "tool_c", "third", PlanStepStatus.PENDING, note = "maybe skip"),
                io.talevia.core.session.PlanStep(4, "tool_d", "fourth", PlanStepStatus.FAILED),
                io.talevia.core.session.PlanStep(5, "tool_e", "fifth", PlanStepStatus.CANCELLED),
            ),
            approval = PlanApprovalStatus.APPROVED_WITH_EDITS,
        )
        assertTrue(rendered.contains("Plan: demo"))
        assertTrue(rendered.contains("approved (edited)"))
        assertTrue(rendered.contains("[x] 1. tool_a"))
        assertTrue(rendered.contains("[~] 2. tool_b"))
        assertTrue(rendered.contains("[ ] 3. tool_c — third  (maybe skip)"))
        assertTrue(rendered.contains("[!] 4. tool_d"))
        assertTrue(rendered.contains("[-] 5. tool_e"))
    }

    @Test fun planPartHasNullCompactedAtByDefault() = runTest {
        val emitted = mutableListOf<Part>()
        val tool = DraftPlanTool()
        tool.execute(
            DraftPlanTool.Input(
                goalDescription = "g",
                steps = listOf(DraftPlanTool.PlanStepInput("t", "x")),
            ),
            ctx(emitted),
        )
        val plan = emitted.single() as Part.Plan
        assertNull(plan.compactedAt)
    }
}
