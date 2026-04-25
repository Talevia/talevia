package io.talevia.core.tool.builtin.video.export

import io.talevia.core.bus.BusEvent
import io.talevia.core.domain.Project
import io.talevia.core.permission.PermissionRequest
import io.talevia.core.tool.ToolContext

/**
 * VISION §5.2 / §5.7 — spend-cap fail-fast for `export`.
 *
 * Symmetric to [io.talevia.core.tool.builtin.aigc.AigcBudgetGuard]
 * which gates AIGC tools' provider calls; this gates the render
 * before any engine work happens.
 *
 * The relevant signal isn't session AIGC spend (already paid; new AIGC
 * is what the AigcBudgetGuard already gates) — it's the cost of the
 * AIGC content currently embedded in the timeline being exported.
 * Computed via [buildPerClipCostAttribution]'s totalCostCents.
 *
 * Three explicit no-op arms:
 *  - `ctx.spendCapCents == null` — user never set a cap. Silent
 *    pass-through, matching pre-cap behaviour for existing sessions.
 *  - timeline cost == 0 — no AIGC content to be capped (pure user
 *    footage, or content with no priced provenance). Trivially
 *    satisfied.
 *  - timeline cost < cap — under budget. Render proceeds without
 *    user interaction.
 *
 * On `>= cap`: raises an `aigc.budget` permission ASK with metadata
 * (capCents, currentCents = timeline cost, toolId, projectId,
 * pricedClipCount). Granted ⇒ render proceeds; rejected ⇒ throws
 * before any engine call so the LLM gets a clean "user denied
 * export" error it can relay.
 *
 * Why reuse the `aigc.budget` permission name (not e.g.
 * `media.export.budget`): an existing user "Always allow" rule for
 * AIGC dispatches covers exporting their output too. A user who's
 * said "yes I'm OK with the cap, keep going" doesn't want a separate
 * confirmation per surface; one rule end-to-end matches the standard
 * pattern other tools use (`fs.write` covers many file writes, etc.).
 */
internal suspend fun enforceTimelineSpendCap(project: Project, ctx: ToolContext) {
    val cap = ctx.spendCapCents ?: return
    val (perClip, totalCost) = buildPerClipCostAttribution(project)
    if (totalCost == 0L) return
    if (totalCost < cap) {
        // Soft warning at 80% — see BusEvent.SpendCapApproaching kdoc.
        if (totalCost >= cap * 4L / 5L) {
            ctx.publishEvent(
                BusEvent.SpendCapApproaching(
                    sessionId = ctx.sessionId,
                    capCents = cap,
                    currentCents = totalCost,
                    ratio = totalCost.toDouble() / cap.toDouble(),
                    scope = "export",
                    toolId = "export",
                ),
            )
        }
        return
    }

    val pricedClipCount = perClip.values.count { it != null }
    val decision = ctx.askPermission(
        PermissionRequest(
            sessionId = ctx.sessionId,
            permission = "aigc.budget",
            pattern = "export-exceeded",
            metadata = mapOf(
                "toolId" to "export",
                "capCents" to cap.toString(),
                "currentCents" to totalCost.toString(),
                "projectId" to project.id.value,
                "pricedClipCount" to pricedClipCount.toString(),
            ),
        ),
    )
    if (!decision.granted) {
        error(
            "Export refused: timeline contains ${totalCost}¢ (≈\$${totalCost / 100.0}) of AIGC " +
                "content across $pricedClipCount priced clip(s) — exceeds session spend cap of " +
                "${cap}¢ (≈\$${cap / 100.0}). User denied export. Raise the cap via " +
                "session_action(action=set_spend_cap) or clear it (capCents=null) to proceed.",
        )
    }
}
