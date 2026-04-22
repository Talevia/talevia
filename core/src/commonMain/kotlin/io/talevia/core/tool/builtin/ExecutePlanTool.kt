package io.talevia.core.tool.builtin

import io.talevia.core.PartId
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.Part
import io.talevia.core.session.PlanApprovalStatus
import io.talevia.core.session.PlanStep
import io.talevia.core.session.PlanStepStatus
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolRegistry
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * VISION §5.4 expert path — the missing bottom half of the
 * `draft_plan` → review → execute loop. Once an agent has drafted a
 * plan, the user has approved it (`approvalStatus` flipped to
 * [PlanApprovalStatus.APPROVED] or [PlanApprovalStatus.APPROVED_WITH_EDITS]),
 * and plan steps carry executable [PlanStep.input] payloads, this tool
 * iterates the steps in order and dispatches each one through the
 * [ToolRegistry] without a round-trip back to the LLM.
 *
 * **Execution order.** Strictly sequential — steps dispatch in list
 * order. No parallelism (preserves causal ordering for timeline ops
 * that actually depend on prior state). First failure halts remaining
 * steps; cancelled steps are reported, not dispatched.
 *
 * **Halt conditions.**
 *  - First step failure → remaining steps marked `CANCELLED` with a
 *    "halted after step N" note.
 *  - Step input missing → step skipped (not halted); downstream steps
 *    still run. Skipped steps stay `PENDING` with a note.
 *  - Approval not granted → tool refuses before dispatching anything.
 *  - `dryRun=true` → no dispatch; Output carries the would-be
 *    execution plan, Plan part is not mutated.
 *
 * **Plan state updates.** Non-dryRun runs re-emit the `Part.Plan` with
 * each step's `status` flipped live (`PENDING → IN_PROGRESS → COMPLETED /
 * FAILED / CANCELLED`) so streaming UIs show progress. The Plan's
 * partId and messageId stay the same — upsert replaces the part in
 * place rather than appending a new one.
 *
 * **Permission.** `session.write` — same tier as `rename_session`.
 * Per-step permission is inherited from the outer `execute_plan` grant;
 * this matches `compare_aigc_candidates` / `replay_lockfile` precedent
 * where a higher-level tool dispatches its target tools without
 * re-permission per step.
 */
class ExecutePlanTool(
    private val registry: ToolRegistry,
    private val sessions: SessionStore,
    private val clock: Clock = Clock.System,
) : Tool<ExecutePlanTool.Input, ExecutePlanTool.Output> {

    @Serializable data class Input(
        /**
         * The `partId` of the `Part.Plan` to execute — returned by
         * `draft_plan` as `Output.partId` when the plan was drafted.
         */
        val planId: String,
        /**
         * `true` → report what would be dispatched without actually
         * calling any tool or mutating the Plan part. Useful for the
         * agent / UI to preview the batch before committing.
         */
        val dryRun: Boolean = false,
    )

    @Serializable data class StepReport(
        val step: Int,
        val toolName: String,
        val status: PlanStepStatus,
        /** Non-null when the step was dispatched and produced output text. */
        val outputForLlm: String? = null,
        /** Non-null when the step failed OR was skipped (with skip reason). */
        val error: String? = null,
    )

    @Serializable data class Output(
        val planId: String,
        val totalSteps: Int,
        val executedSteps: Int,
        val skippedSteps: Int,
        /** `null` when all executable steps succeeded; step number when halted. */
        val failedAtStep: Int? = null,
        val steps: List<StepReport>,
        val dryRun: Boolean,
    )

    override val id: String = "execute_plan"
    override val helpText: String =
        "Execute a previously-drafted plan by its planId (Part.Plan partId from draft_plan's Output). " +
            "Dispatches each step with a non-null `input` payload sequentially via the registered tool, " +
            "re-emitting the Plan part as each step's status flips PENDING → IN_PROGRESS → COMPLETED / " +
            "FAILED. First failure halts remaining steps (marked CANCELLED). Steps without an `input` " +
            "payload are skipped with a diagnostic note (agent can fill them in and retry). Plan must " +
            "be APPROVED or APPROVED_WITH_EDITS; unapproved/rejected plans are refused. Use dryRun=true " +
            "to preview the execution order without dispatching anything — Plan part is not mutated " +
            "in dryRun mode."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("session.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("planId") {
                put("type", "string")
                put(
                    "description",
                    "The partId of the Part.Plan to execute — draft_plan's Output.partId.",
                )
            }
            putJsonObject("dryRun") {
                put("type", "boolean")
                put(
                    "description",
                    "true → return the execution plan without dispatching or mutating the Plan.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("planId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val planPartId = PartId(input.planId)
        val planPart = sessions.getPart(planPartId)
            ?: error(
                "No Part found with id '${input.planId}'. Call draft_plan first and use its Output.partId.",
            )
        require(planPart is Part.Plan) {
            "Part '${input.planId}' is a ${planPart::class.simpleName}, not Part.Plan. " +
                "execute_plan only works on draft_plan outputs."
        }
        require(
            planPart.approvalStatus == PlanApprovalStatus.APPROVED ||
                planPart.approvalStatus == PlanApprovalStatus.APPROVED_WITH_EDITS,
        ) {
            "Plan '${input.planId}' has approvalStatus=${planPart.approvalStatus.name.lowercase()}. " +
                "Re-emit the plan with approvalStatus=approved (or approved_with_edits) before executing."
        }

        val reports = mutableListOf<StepReport>()
        var executed = 0
        var skipped = 0
        var failedAtStep: Int? = null

        val liveSteps = planPart.steps.toMutableList()

        for (index in liveSteps.indices) {
            val original = liveSteps[index]
            // Post-halt: mark remaining steps cancelled (non-dryRun only).
            if (failedAtStep != null) {
                val cancelled = original.copy(
                    status = PlanStepStatus.CANCELLED,
                    note = "halted after step $failedAtStep failed",
                )
                liveSteps[index] = cancelled
                reports += StepReport(
                    step = cancelled.step,
                    toolName = cancelled.toolName,
                    status = PlanStepStatus.CANCELLED,
                    error = "halted after step $failedAtStep",
                )
                continue
            }
            // Already-completed steps (agent re-running a partially-applied plan): leave alone.
            if (original.status == PlanStepStatus.COMPLETED) {
                reports += StepReport(
                    step = original.step,
                    toolName = original.toolName,
                    status = PlanStepStatus.COMPLETED,
                    outputForLlm = "already completed — skipped",
                )
                continue
            }
            // Step without executable payload: skip, note, continue with next step.
            val payload = original.input
            if (payload == null) {
                skipped++
                reports += StepReport(
                    step = original.step,
                    toolName = original.toolName,
                    status = original.status,
                    error = "no executable input payload on PlanStep; skipped",
                )
                continue
            }
            val tool = registry[original.toolName]
            if (tool == null) {
                skipped++
                reports += StepReport(
                    step = original.step,
                    toolName = original.toolName,
                    status = original.status,
                    error = "tool '${original.toolName}' not registered in this container; skipped",
                )
                continue
            }

            if (input.dryRun) {
                reports += StepReport(
                    step = original.step,
                    toolName = original.toolName,
                    status = PlanStepStatus.PENDING,
                    outputForLlm = "dryRun: would dispatch ${original.toolName}",
                )
                continue
            }

            // --- Live dispatch path: emit IN_PROGRESS, dispatch, emit terminal state.
            liveSteps[index] = original.copy(status = PlanStepStatus.IN_PROGRESS, note = null)
            emitPlan(ctx, planPart, liveSteps)
            val outcome = runCatching { tool.dispatch(payload, ctx) }
            outcome.fold(
                onSuccess = { res ->
                    liveSteps[index] = original.copy(status = PlanStepStatus.COMPLETED, note = null)
                    emitPlan(ctx, planPart, liveSteps)
                    executed++
                    reports += StepReport(
                        step = original.step,
                        toolName = original.toolName,
                        status = PlanStepStatus.COMPLETED,
                        outputForLlm = res.outputForLlm,
                    )
                },
                onFailure = { t ->
                    val msg = t.message ?: t::class.simpleName ?: "unknown"
                    liveSteps[index] = original.copy(
                        status = PlanStepStatus.FAILED,
                        note = msg,
                    )
                    emitPlan(ctx, planPart, liveSteps)
                    failedAtStep = original.step
                    reports += StepReport(
                        step = original.step,
                        toolName = original.toolName,
                        status = PlanStepStatus.FAILED,
                        error = msg,
                    )
                },
            )
        }

        // Final plan emit: ensures cancellations past the halt point + any
        // in-memory step state updates are reflected in the persisted Plan
        // part even if no live-dispatch emission covered those steps. No-op
        // in dryRun (we never mutated liveSteps).
        if (!input.dryRun) {
            emitPlan(ctx, planPart, liveSteps)
        }

        val out = Output(
            planId = input.planId,
            totalSteps = planPart.steps.size,
            executedSteps = executed,
            skippedSteps = skipped,
            failedAtStep = failedAtStep,
            steps = reports,
            dryRun = input.dryRun,
        )
        val summary = buildString {
            if (input.dryRun) {
                append("dryRun execute_plan '${input.planId}': ")
            } else {
                append("execute_plan '${input.planId}': ")
            }
            append("$executed/${planPart.steps.size} executed")
            if (skipped > 0) append(", $skipped skipped")
            if (failedAtStep != null) append("; halted at step $failedAtStep")
        }
        return ToolResult(
            title = if (input.dryRun) "execute_plan (dryRun)" else "execute_plan",
            outputForLlm = summary,
            data = out,
        )
    }

    private suspend fun emitPlan(
        ctx: ToolContext,
        original: Part.Plan,
        updatedSteps: List<PlanStep>,
    ) {
        ctx.emitPart(
            original.copy(
                steps = updatedSteps,
                // Preserve the plan's original messageId / partId so this upsert
                // replaces the existing Part rather than creating a new one.
                createdAt = clock.now(),
            ),
        )
    }
}
