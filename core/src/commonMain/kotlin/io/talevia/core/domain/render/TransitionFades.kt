package io.talevia.core.domain.render

import io.talevia.core.ClipId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Timeline
import io.talevia.core.domain.Track
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Head / tail fade envelope a video clip inherits from neighbouring transition
 * clips on an Effect track. Lifted from [io.talevia.platform.ffmpeg.FfmpegVideoEngine]'s
 * private `ClipFades` so the same envelope can be computed in Core (needed by the
 * per-clip mezzanine cache's fingerprint — a change to a neighbouring transition
 * must invalidate this clip's mezzanine).
 *
 * Semantics: `headFade` ramps in-from-black at the clip's start; `tailFade`
 * ramps out-to-black at the clip's end. Either or both can be null (the default).
 * Durations are half the transition clip's duration — transitions are centered
 * on the boundary between their two neighbours.
 */
@Serializable
data class TransitionFades(
    val headFade: Duration? = null,
    val tailFade: Duration? = null,
)

/**
 * Walk [this] timeline's Effect tracks and map each affected video clip's id to
 * its fade envelope. Moved from the FFmpeg engine so the per-clip render cache
 * in Core can fingerprint neighbour-aware fade context without depending on a
 * platform module.
 *
 * V1 renders every transition name (fade, dissolve, slide, wipe, …) as a
 * dip-to-black fade — this matches the cross-engine parity floor and what
 * Media3 / AVFoundation can render without custom shaders. A true crossfade
 * would require overlapping clips, which [io.talevia.core.tool.builtin.video.AddTransitionTool]
 * does not produce (clips stay sequential; the transition sits on a separate
 * Effect track and only encodes the boundary).
 */
fun Timeline.transitionFadesPerClip(videoClips: List<Clip.Video>): Map<ClipId, TransitionFades> {
    val transitions = tracks
        .filterIsInstance<Track.Effect>()
        .flatMap { it.clips.filterIsInstance<Clip.Video>() }
        .filter { it.assetId.value.startsWith("transition:") }
    if (transitions.isEmpty()) return emptyMap()
    val accum = mutableMapOf<ClipId, TransitionFades>()
    for (trans in transitions) {
        val halfDur = trans.timeRange.duration / 2
        val boundary = trans.timeRange.start + halfDur
        val fromClip = videoClips.firstOrNull { it.timeRange.end == boundary }
        val toClip = videoClips.firstOrNull { it.timeRange.start == boundary }
        if (fromClip != null) {
            val prior = accum[fromClip.id] ?: TransitionFades()
            accum[fromClip.id] = prior.copy(tailFade = halfDur)
        }
        if (toClip != null) {
            val prior = accum[toClip.id] ?: TransitionFades()
            accum[toClip.id] = prior.copy(headFade = halfDur)
        }
    }
    return accum
}
