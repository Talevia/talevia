package io.talevia.core.agent

import io.talevia.core.MessageId
import io.talevia.core.session.FinishReason
import io.talevia.core.session.Message
import io.talevia.core.session.Part
import io.talevia.core.session.SessionStore
import io.talevia.core.session.ToolState

/**
 * Stamp the cancelled assistant message's finish + mop up any in-flight
 * Tool parts left in `Pending` or `Running`.
 *
 * Extracted from [Agent]'s private `finalizeCancelled` so the orchestration
 * file stays under the R.5.4 500-LOC threshold. Stamps:
 *  - `FinishReason.CANCELLED` on the assistant message (with the cancel
 *    reason copied to `Message.Assistant.error`).
 *  - `ToolState.Cancelled(input, reason)` on every Tool part of that
 *    message currently in `Pending` or `Running` state. Cycle-62
 *    upgraded this from `Failed("cancelled: <reason>")` to the
 *    dedicated `Cancelled` variant so downstream consumers can
 *    distinguish run-level cancel from tool-level error without
 *    parsing the `Failed.message` prefix.
 *
 * Safe to call when `assistantId == null` (no assistant message was
 * spawned before cancel — nothing to update), or when the message is
 * unknown / already-finished (no-op on both). The two store writes are
 * each wrapped in `runCatching` so a transient persistence error never
 * blocks the cancel path itself.
 */
internal suspend fun finalizeCancelled(
    store: SessionStore,
    assistantId: MessageId?,
    reason: String?,
) {
    val mid = assistantId ?: return
    val existing = runCatching { store.getMessage(mid) }.getOrNull() as? Message.Assistant ?: return
    // Avoid overwriting a finish that already landed (race with streamTurn).
    if (existing.finish != null) return
    val cancelled = existing.copy(
        finish = FinishReason.CANCELLED,
        error = reason ?: "cancelled",
    )
    runCatching { store.updateMessage(cancelled) }

    // Any Tool part the LLM started streaming but didn't complete before
    // the cancel lands here stamped Pending or Running. Without this pass
    // the part would stay "in progress" forever in the session log —
    // misleading any post-mortem query (run_failure, listMessagesWithParts)
    // about what actually happened. Stamp them with the dedicated
    // [ToolState.Cancelled] variant so consumers can distinguish run-
    // level cancel from tool-level failure without parsing message
    // prefixes (cycle-62 upgraded from `Failed("cancelled: <reason>")`).
    val msg = reason ?: "cancelled"
    runCatching {
        val parts = store.listSessionParts(existing.sessionId, includeCompacted = true)
        for (part in parts) {
            if (part !is Part.Tool || part.messageId != mid) continue
            val state = part.state
            val input = when (state) {
                is ToolState.Running -> state.input
                ToolState.Pending,
                is ToolState.Completed,
                is ToolState.Failed,
                is ToolState.Cancelled,
                -> null
            }
            if (state !is ToolState.Pending && state !is ToolState.Running) continue
            val updated = part.copy(
                state = ToolState.Cancelled(input = input, message = msg),
            )
            runCatching { store.upsertPart(updated) }
        }
    }
}
