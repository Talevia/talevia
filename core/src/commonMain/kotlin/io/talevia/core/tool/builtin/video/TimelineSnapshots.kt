package io.talevia.core.tool.builtin.video

import io.talevia.core.PartId
import io.talevia.core.domain.Timeline
import io.talevia.core.session.Part
import io.talevia.core.tool.ToolContext
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Record the post-mutation [Timeline] as a [Part.TimelineSnapshot] in the session.
 *
 * Every timeline-mutating tool (clip_action, apply_filter, add_subtitles,
 * add_transition, revert_timeline) calls this after committing to [ProjectStore].
 * Snapshots are the history stack the `revert_timeline` tool walks — without one
 * per mutation there is nothing to roll back to.
 *
 * Returns the new snapshot's [PartId] so the tool can surface it in `outputForLlm`,
 * giving the LLM a concrete handle to pass to `revert_timeline` later on.
 */
@OptIn(ExperimentalUuidApi::class)
internal suspend fun emitTimelineSnapshot(
    ctx: ToolContext,
    timeline: Timeline,
    clock: Clock = Clock.System,
): PartId {
    val partId = PartId(Uuid.random().toString())
    ctx.emitPart(
        Part.TimelineSnapshot(
            id = partId,
            messageId = ctx.messageId,
            sessionId = ctx.sessionId,
            createdAt = clock.now(),
            timeline = timeline,
            producedByCallId = ctx.callId,
        ),
    )
    return partId
}
