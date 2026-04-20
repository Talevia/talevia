package io.talevia.core.session

import io.talevia.core.MessageId
import io.talevia.core.PartId
import io.talevia.core.ProjectId
import io.talevia.core.SessionId
import io.talevia.core.bus.BusEvent
import io.talevia.core.bus.EventBus
import io.talevia.core.domain.ProjectStore

/**
 * Rewind a chat session to an earlier anchor message.
 *
 * Unlike OpenCode's soft revert (`session/revert.ts` — tracks a snapshot + diff
 * overlay that can be un-reverted), this is a **hard revert**: every message
 * strictly after the anchor is deleted, and the project timeline is rolled back
 * to the most recent [Part.TimelineSnapshot] at-or-before the anchor.
 *
 * Rationale for hard over soft: OpenCode's unrevert exists because its revert
 * can span filesystem edits (that diff is worth keeping as a reversible patch).
 * Our analogous state — the canonical timeline — already has explicit snapshot
 * parts at every mutating tool boundary, so the cheapest and clearest primitive
 * is "drop the messages, restore the snapshot". The complexity of unrevert
 * (storing a second snapshot of what the revert replaced) buys little over a
 * user just typing the follow-up prompt again, and costs extra invariants
 * (what does unrevert mean if the user appended more messages after revert?).
 *
 * Concurrency: does NOT assert the session is idle. The caller (CLI slash
 * command today, probably a tool surface later) is responsible for cancelling
 * any in-flight `Agent.run` first. A best-effort check could be layered on top
 * by watching the agent's run-state, but keeping the core primitive permissive
 * matches our other session-mutation APIs (`deleteSession`, `fork`).
 */
class SessionRevert(
    private val sessions: SessionStore,
    private val projects: ProjectStore,
    private val bus: EventBus,
) {
    data class Result(
        val anchorMessageId: MessageId,
        val deletedMessages: Int,
        val appliedSnapshotPartId: PartId?,
        val restoredClipCount: Int,
        val restoredTrackCount: Int,
    )

    /**
     * Rewind [sessionId] to just-after [anchorMessageId].
     *
     * Throws `IllegalArgumentException` if the anchor is unknown or belongs to
     * a different session — callers have already scoped by session in every
     * realistic flow, so a mismatch is a bug, not a normal branch.
     *
     * If no [Part.TimelineSnapshot] exists at-or-before the anchor (e.g. a
     * fresh session that never mutated the timeline), the timeline is left
     * untouched and [Result.appliedSnapshotPartId] is null.
     */
    suspend fun revertToMessage(
        sessionId: SessionId,
        anchorMessageId: MessageId,
        projectId: ProjectId,
    ): Result {
        val anchor = sessions.getMessage(anchorMessageId)
            ?: throw IllegalArgumentException("Message $anchorMessageId not found")
        require(anchor.sessionId == sessionId) {
            "Message $anchorMessageId belongs to session ${anchor.sessionId}, not $sessionId"
        }

        val snapshot = latestSnapshotAtOrBefore(sessionId, anchor)
        val deleted = sessions.deleteMessagesAfter(sessionId, anchorMessageId)

        var clipCount = 0
        var trackCount = 0
        if (snapshot != null) {
            val project = projects.mutate(projectId) { p -> p.copy(timeline = snapshot.timeline) }
            clipCount = project.timeline.tracks.sumOf { it.clips.size }
            trackCount = project.timeline.tracks.size
        }

        bus.publish(
            BusEvent.SessionReverted(
                sessionId = sessionId,
                projectId = projectId,
                anchorMessageId = anchorMessageId,
                deletedMessages = deleted,
                appliedSnapshotPartId = snapshot?.id,
            ),
        )
        return Result(
            anchorMessageId = anchorMessageId,
            deletedMessages = deleted,
            appliedSnapshotPartId = snapshot?.id,
            restoredClipCount = clipCount,
            restoredTrackCount = trackCount,
        )
    }

    /**
     * Walk parts from oldest to newest, take the last [Part.TimelineSnapshot]
     * whose message is at-or-before the anchor in the session's `(createdAt, id)`
     * ordering. `listSessionParts` returns parts in insertion order (see
     * `Parts.sq: selectBySession ORDER BY time_created ASC, id ASC`), which
     * matches the same ordering `listMessages` uses — so a snapshot part from
     * message M is "before" the anchor iff M is before the anchor in that list.
     */
    private suspend fun latestSnapshotAtOrBefore(
        sessionId: SessionId,
        anchor: Message,
    ): Part.TimelineSnapshot? {
        val messages = sessions.listMessages(sessionId)
        val anchorIdx = messages.indexOfFirst { it.id == anchor.id }
        if (anchorIdx < 0) return null
        val eligibleIds = messages.subList(0, anchorIdx + 1).map { it.id }.toHashSet()

        return sessions.listSessionParts(sessionId, includeCompacted = true)
            .asSequence()
            .filterIsInstance<Part.TimelineSnapshot>()
            .filter { it.messageId in eligibleIds }
            .lastOrNull()
    }
}
