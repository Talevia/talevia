package io.talevia.core.tool.builtin

import io.talevia.core.PartId
import io.talevia.core.permission.PermissionAction
import io.talevia.core.permission.PermissionRule
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Part
import io.talevia.core.session.PlanApprovalStatus
import io.talevia.core.session.PlanStep
import io.talevia.core.session.PlanStepStatus
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Emit a structured pre-commit plan — the agent tells the user "here's what
 * I'm about to do" before actually dispatching the N underlying tool calls.
 * Implements VISION §5.4 "expert path wants a preview": long multi-step
 * intents ("make me a 30s graduation vlog") today connect-the-dots
 * ~10 tool calls with no intervention window; the user sees actions fly
 * by but can't stop one before it hits a provider.
 *
 * **Contract.** The tool persists a [Part.Plan] on the active assistant
 * message (new `draft_plan` call supersedes the most recent one in the
 * session, mirroring `todowrite`). It does **not** dispatch any of the
 * listed steps — execution is a regular agent-loop follow-up once the user
 * approves. Status updates flow through re-emitting the plan with updated
 * [PlanStep.status] as steps progress (PENDING → IN_PROGRESS → COMPLETED /
 * FAILED). The UI renders the most recent `Part.Plan` as a checklist.
 *
 * **Distinct from `todowrite`.** Todos are free-text; plan steps carry
 * `(toolName, inputSummary)` so the user can recognise the *specific*
 * dispatch the agent is about to run. `todowrite` stays the lightweight
 * scratchpad; `draft_plan` is the "kubectl diff" before "kubectl apply".
 *
 * Permission is "draft_plan" (default ALLOW): the tool is pure session-local
 * state with no external side effects, and demanding a confirm would defeat
 * the purpose of the tool (the tool IS the confirm step).
 */
class DraftPlanTool(private val clock: Clock = Clock.System) : Tool<DraftPlanTool.Input, DraftPlanTool.Output> {

    @Serializable
    data class Input(
        /**
         * One-sentence human description of what the plan achieves
         * (e.g. "Make a 30s vertical graduation vlog"). Surfaces at the
         * top of the rendered plan for scan-ability.
         */
        val goalDescription: String,
        /**
         * Ordered list of planned tool dispatches. Each step carries a
         * `toolName` + a human `inputSummary` — the agent's
         * recommendation of what to dispatch, NOT an executable payload.
         * The agent re-derives full inputs when it actually dispatches
         * each tool after the user approves.
         */
        val steps: List<PlanStepInput>,
        /**
         * Approval status the agent is recording. Defaults to
         * `pending_approval` — the normal "I just drafted this,
         * awaiting your review" state. Agents should flip to
         * `approved` / `approved_with_edits` / `rejected` on follow-up
         * calls, not from user intent directly.
         */
        val approvalStatus: PlanApprovalStatus = PlanApprovalStatus.PENDING_APPROVAL,
    )

    @Serializable
    data class PlanStepInput(
        val toolName: String,
        val inputSummary: String,
        val status: PlanStepStatus = PlanStepStatus.PENDING,
        val note: String? = null,
        /**
         * Optional executable Input payload for `execute_plan`. When set,
         * the agent (or the user via UI) can auto-dispatch this step
         * without returning to the LLM to reconstruct inputs. When null,
         * the step remains preview-only and `execute_plan` will skip it
         * with a diagnostic note. See [PlanStep.input].
         */
        val input: JsonObject? = null,
    )

    @Serializable
    data class Output(
        val partId: String,
        val goalDescription: String,
        val stepCount: Int,
        val approvalStatus: PlanApprovalStatus,
        val pendingStepCount: Int,
    )

    override val id: String = "draft_plan"
    override val helpText: String = HELP_TEXT
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("draft_plan")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("goalDescription") {
                put("type", "string")
                put(
                    "description",
                    "One-sentence human goal statement (e.g. \"Make a 30s vertical graduation vlog\").",
                )
            }
            putJsonObject("steps") {
                put("type", "array")
                put(
                    "description",
                    "Ordered list of planned tool dispatches. Each step is a preview, not an executable payload.",
                )
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("toolName") {
                            put("type", "string")
                            put(
                                "description",
                                "The registered tool id to dispatch (e.g. \"generate_image\", \"add_clip\").",
                            )
                        }
                        putJsonObject("inputSummary") {
                            put("type", "string")
                            put(
                                "description",
                                "Human 1-liner of the key inputs. Keep short (≤120 chars); full inputs re-derived at dispatch time.",
                            )
                        }
                        putJsonObject("status") {
                            put("type", "string")
                            put("description", "Step state. Defaults to pending.")
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("pending"))
                                    add(JsonPrimitive("in_progress"))
                                    add(JsonPrimitive("completed"))
                                    add(JsonPrimitive("failed"))
                                    add(JsonPrimitive("cancelled"))
                                },
                            )
                        }
                        putJsonObject("note") {
                            put("type", "string")
                            put(
                                "description",
                                "Optional free-text annotation (skip reason, failure cause).",
                            )
                        }
                        putJsonObject("input") {
                            put("type", "object")
                            put(
                                "description",
                                "Optional executable Input JSON for execute_plan. When present, " +
                                    "execute_plan dispatches this step directly via the registered tool " +
                                    "without re-asking the LLM for inputs. Omit when the step is " +
                                    "preview-only or when inputs depend on intermediate results.",
                            )
                            put("additionalProperties", true)
                        }
                    }
                    put(
                        "required",
                        JsonArray(listOf(JsonPrimitive("toolName"), JsonPrimitive("inputSummary"))),
                    )
                    put("additionalProperties", false)
                }
            }
            putJsonObject("approvalStatus") {
                put("type", "string")
                put(
                    "description",
                    "Plan-level approval status. Default pending_approval on first draft.",
                )
                put(
                    "enum",
                    buildJsonArray {
                        add(JsonPrimitive("pending_approval"))
                        add(JsonPrimitive("approved"))
                        add(JsonPrimitive("approved_with_edits"))
                        add(JsonPrimitive("rejected"))
                    },
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("goalDescription"), JsonPrimitive("steps"))),
        )
        put("additionalProperties", false)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.goalDescription.isNotBlank()) {
            "draft_plan: goalDescription must be non-blank — plan needs a scannable header"
        }
        require(input.steps.isNotEmpty()) {
            "draft_plan: steps must not be empty — use todowrite for free-text notes instead"
        }
        val numberedSteps = input.steps.mapIndexed { idx, s ->
            require(s.toolName.isNotBlank()) { "draft_plan: step ${idx + 1} has blank toolName" }
            require(s.inputSummary.isNotBlank()) { "draft_plan: step ${idx + 1} has blank inputSummary" }
            PlanStep(
                step = idx + 1,
                toolName = s.toolName,
                inputSummary = s.inputSummary,
                status = s.status,
                note = s.note,
                input = s.input,
            )
        }

        val partId = PartId(Uuid.random().toString())
        ctx.emitPart(
            Part.Plan(
                id = partId,
                messageId = ctx.messageId,
                sessionId = ctx.sessionId,
                createdAt = clock.now(),
                goalDescription = input.goalDescription,
                steps = numberedSteps,
                approvalStatus = input.approvalStatus,
            ),
        )

        val pending = numberedSteps.count { it.status == PlanStepStatus.PENDING }
        val out = Output(
            partId = partId.value,
            goalDescription = input.goalDescription,
            stepCount = numberedSteps.size,
            approvalStatus = input.approvalStatus,
            pendingStepCount = pending,
        )
        return ToolResult(
            title = "plan: ${input.goalDescription} ($pending/${numberedSteps.size} pending)",
            outputForLlm = renderForLlm(input.goalDescription, numberedSteps, input.approvalStatus),
            data = out,
        )
    }

    internal fun renderForLlm(
        goal: String,
        steps: List<PlanStep>,
        approval: PlanApprovalStatus,
    ): String = buildString {
        append("Plan: ")
        append(goal)
        append(" [")
        append(approvalLabel(approval))
        append(']')
        steps.forEach { s ->
            append('\n')
            append(marker(s.status))
            append(' ')
            append(s.step)
            append(". ")
            append(s.toolName)
            append(" — ")
            append(s.inputSummary)
            s.note?.takeIf { it.isNotBlank() }?.let {
                append("  (")
                append(it)
                append(')')
            }
        }
    }

    private fun marker(status: PlanStepStatus): String = when (status) {
        PlanStepStatus.PENDING -> "[ ]"
        PlanStepStatus.IN_PROGRESS -> "[~]"
        PlanStepStatus.COMPLETED -> "[x]"
        PlanStepStatus.FAILED -> "[!]"
        PlanStepStatus.CANCELLED -> "[-]"
    }

    private fun approvalLabel(status: PlanApprovalStatus): String = when (status) {
        PlanApprovalStatus.PENDING_APPROVAL -> "awaiting approval"
        PlanApprovalStatus.APPROVED -> "approved"
        PlanApprovalStatus.APPROVED_WITH_EDITS -> "approved (edited)"
        PlanApprovalStatus.REJECTED -> "rejected"
    }

    companion object {
        /** Default permission rule — included by `DefaultPermissionRuleset`. */
        val ALLOW_RULE = PermissionRule(
            permission = "draft_plan",
            pattern = "*",
            action = PermissionAction.ALLOW,
        )

        private val HELP_TEXT = """
            Record a structured pre-commit plan (tool dispatches the agent intends to run).
            Emits a `Part.Plan` for user review; does NOT dispatch — execution is a
            follow-up pass after user approval.

            Use when: request spans 3+ consequential calls (AIGC, timeline mutations,
            cross-project copies), or any single wrong step would be expensive/destructive,
            or the user asks "what are you going to do?". Prefer `todowrite` for
            free-text scratchpads — `draft_plan` is structured (toolName + inputSummary
            per step) because the user needs to see the actual dispatch.

            Workflow: (1) call with approvalStatus=pending_approval and wait. (2) on
            approve → re-emit with approved / approved_with_edits, then dispatch in order.
            (3) per step: re-emit with status=in_progress → completed (or failed+note).
            (4) on reject → re-emit with rejected and drop the batch.

            Each call fully replaces the previous Part.Plan. Step numbering is
            server-assigned from list order — don't supply `step`.
        """.trimIndent()
    }
}
