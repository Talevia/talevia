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
 * file stays under the R.5.4 500-LOC threshold. Every byte of behaviour is
 * preserved — same `FinishReason.CANCELLED` stamp on the assistant
 * message, same `ToolState.Failed(input, "cancelled: <reason>")` stamp on
 * in-flight tool parts, same non-fatal `runCatching` around both store
 * calls.
 *
 * **Why Failed instead of a new Cancelled variant?** The `ToolState`
 * sealed hierarchy has 47 exhaustive `when` call sites across providers,
 * serialisers, UI, and tests. A fifth variant would touch every one for
 * a signal that the `"cancelled: "` message prefix captures cleanly. See
 * the cycle-50 cancel-stamp commit body for the full rationale.
 *
 * Safe to call when `assistantId == null` (no assistant message was
 * spawned before cancel — nothing to update), or when the message is
 * unknown / already-finished (no-op on both).
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
    // about what actually happened. Stamp them Failed with a "cancelled"
    // message so the audit trail matches the message-level finish.
    val msg = reason ?: "cancelled"
    runCatching {
        val parts = store.listSessionParts(existing.sessionId, includeCompacted = true)
        for (part in parts) {
            if (part !is Part.Tool || part.messageId != mid) continue
            val state = part.state
            val input = when (state) {
                is ToolState.Running -> state.input
                ToolState.Pending, is ToolState.Completed, is ToolState.Failed -> null
            }
            if (state !is ToolState.Pending && state !is ToolState.Running) continue
            val updated = part.copy(
                state = ToolState.Failed(input = input, message = "cancelled: $msg"),
            )
            runCatching { store.upsertPart(updated) }
        }
    }
}
