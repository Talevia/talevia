package io.talevia.core.tool.builtin.aigc

import io.talevia.core.ProjectId
import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.tool.ToolContext

/**
 * VISION §5.2 — pre-flight spend-cap check every AIGC tool calls before
 * reaching its provider. Compares the session's cumulative AIGC spend
 * (attributed via `LockfileEntry.sessionId` + `costCents`) against the
 * user-configured cap on [ToolContext.spendCapCents]. When cumulative ≥
 * cap, raises an `aigc.budget` permission ASK so the user can either
 * allow the call or abort the run.
 *
 * Three explicit no-op arms:
 *  - `ctx.spendCapCents == null` — user never set a cap. Silent
 *    pass-through, matching pre-cap behaviour for existing sessions.
 *  - `projectId == null` — the tool was dispatched outside any bound
 *    project (test harnesses, legacy call sites). No lockfile to read,
 *    no spend to compare against, so the guard is trivially satisfied.
 *  - Project row missing — the session is bound but the project was
 *    deleted. Same reasoning as `SpendQuery` (silent bail rather than
 *    throw) — the user's "don't exceed cap" intent can't be violated
 *    when there's no history to count.
 *
 * Raises on denial (`PermissionDecision.Reject`) so the tool's normal
 * error path kicks in and the LLM gets a clean "budget denied" message
 * it can relay to the user. Granted decisions return normally — the
 * tool proceeds with its provider call. A `PermissionDecision.Always`
 * persists an allow rule for `aigc.budget`, effectively disabling the
 * cap for the rest of the session; that is the user's explicit
 * override and we trust it.
 *
 * Not a tool — a plain helper called from inside AIGC tools' `execute`.
 * Keeps the 5-tool AIGC surface unchanged while gating every provider
 * call through one code path.
 */
internal object AigcBudgetGuard {

    /** 0.8x cap, expressed as integer fraction so we don't bring `Double` into the comparison. */
    private const val WARNING_THRESHOLD_NUM = 4L
    private const val WARNING_THRESHOLD_DEN = 5L

    /**
     * @param toolId Passed through to the permission request metadata so
     *   the UI can show *which* AIGC tool tripped the gate.
     * @param projectStore Nullable; guard returns early when absent,
     *   mirroring the way AIGC tools skip lockfile recording when no
     *   store is wired.
     * @param projectId Nullable; see class-level doc for rationale.
     */
    suspend fun enforce(
        toolId: String,
        projectStore: ProjectStore?,
        projectId: ProjectId?,
        ctx: ToolContext,
    ) {
        val cap = ctx.spendCapCents ?: return
        val store = projectStore ?: return
        val pid = projectId ?: return
        val project = store.get(pid) ?: return
        val spentCents = project.lockfile
            .stream()
            .filter { it.sessionId == ctx.sessionId.value }
            .sumOf { it.costCents ?: 0L }
        if (spentCents < cap) {
            // Soft warning at 80% — see BusEvent.SpendCapApproaching kdoc.
            // Fires every qualifying call; subscribers debounce.
            if (spentCents >= cap * WARNING_THRESHOLD_NUM / WARNING_THRESHOLD_DEN) {
                ctx.publishEvent(
                    BusEvent.SpendCapApproaching(
                        sessionId = ctx.sessionId,
                        capCents = cap,
                        currentCents = spentCents,
                        ratio = spentCents.toDouble() / cap.toDouble(),
                        scope = "aigc",
                        toolId = toolId,
                    ),
                )
            }
            return
        }

        val decision = ctx.askPermission(
            PermissionRequest(
                sessionId = ctx.sessionId,
                permission = "aigc.budget",
                pattern = "exceeded",
                metadata = mapOf(
                    "toolId" to toolId,
                    "capCents" to cap.toString(),
                    "currentCents" to spentCents.toString(),
                    "projectId" to pid.value,
                ),
            ),
        )
        if (!decision.granted) {
            error(
                "Session spend cap of ${cap}¢ (≈\$${cap / 100.0}) reached — current session " +
                    "spend is ${spentCents}¢. User denied continuation of $toolId. Raise the " +
                    "cap via session_action(action=set_spend_cap) or clear it (capCents=null) to proceed.",
            )
        }
    }
}
