package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlin.time.DurationUnit

/**
 * Audio-specific mutate-verb handlers extracted from [ClipMutateHandlers].
 * Sibling file holding actions that only apply to [Clip.Audio] clips —
 * today just `fade`, but the split is deliberate so future audio-level
 * verbs (`level` / `pan` / `duck`) have a natural home that stays out
 * of the larger video-mutate file.
 *
 * Kept at module level to match [ClipMutateHandlers]' convention of
 * top-level `execute*` functions. Each handler takes the minimal
 * dependencies explicitly (`store, pid, input, ctx`) — no reliance on
 * the tool instance.
 *
 * Extracted cycle-51 per `debt-split-clip-mutate-handlers-preempt-now`:
 * the video-mutate file was crossing the R.5.4 500-LOC threshold on the
 * next verb addition and the audio-vs-video axis was already documented
 * in the parent file's KDoc as the intended split point.
 */

internal suspend fun executeClipFade(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val items = input.fadeItems ?: error("action=fade requires `fadeItems`")
    rejectForeignClipActionFields("fade", input)
    require(items.isNotEmpty()) { "fadeItems must not be empty" }
    items.forEachIndexed { idx, item ->
        require(item.fadeInSeconds != null || item.fadeOutSeconds != null) {
            "fadeItems[$idx] (${item.clipId}): at least one of fadeInSeconds / fadeOutSeconds required"
        }
        item.fadeInSeconds?.let {
            require(it.isFinite() && it >= 0f) {
                "fadeItems[$idx] (${item.clipId}): fadeInSeconds must be finite and >= 0 (got $it)"
            }
        }
        item.fadeOutSeconds?.let {
            require(it.isFinite() && it >= 0f) {
                "fadeItems[$idx] (${item.clipId}): fadeOutSeconds must be finite and >= 0 (got $it)"
            }
        }
    }

    val results = mutableListOf<ClipActionTool.FadeResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val hit = tracks.firstNotNullOfOrNull { track ->
                track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
            } ?: error("fadeItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
            val (track, clip) = hit
            val audio = clip as? Clip.Audio ?: error(
                "fadeItems[$idx]: action=fade only applies to audio clips; clip ${item.clipId} " +
                    "is a ${clip::class.simpleName}.",
            )
            val oldIn = audio.fadeInSeconds
            val oldOut = audio.fadeOutSeconds
            val newIn = item.fadeInSeconds ?: oldIn
            val newOut = item.fadeOutSeconds ?: oldOut
            val clipDurationSeconds = audio.timeRange.duration.toDouble(DurationUnit.SECONDS).toFloat()
            require(newIn + newOut <= clipDurationSeconds + 1e-3f) {
                "fadeItems[$idx] (${item.clipId}): fadeIn ($newIn) + fadeOut ($newOut) would exceed " +
                    "clip duration ($clipDurationSeconds); fades would overlap."
            }
            val rebuilt = track.clips.map { c ->
                if (c.id == audio.id) audio.copy(fadeInSeconds = newIn, fadeOutSeconds = newOut) else c
            }
            val newTrack: Track = withClips(track, rebuilt)
            tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
            results += ClipActionTool.FadeResult(
                clipId = item.clipId,
                trackId = track.id.value,
                oldFadeInSeconds = oldIn,
                newFadeInSeconds = newIn,
                oldFadeOutSeconds = oldOut,
                newFadeOutSeconds = newOut,
            )
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "fade × ${results.size}",
        outputForLlm = "Set fades on ${results.size} audio clip(s). Snapshot: ${snapshotId.value}",
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "fade",
            snapshotId = snapshotId.value,
            faded = results,
        ),
    )
}
