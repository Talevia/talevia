package io.talevia.core.tool.builtin.project.fork

import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.Resolution
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
import io.talevia.core.tool.builtin.project.ForkProjectTool
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Result of applying a [ForkProjectTool.VariantSpec] to a freshly-forked
 * project: the reshaped project plus the counts of clips dropped / truncated
 * along the way (surfaced on `ForkProjectTool.Output` so the agent can
 * tell the user "I cut 3 clips to fit the 30s cap").
 */
internal data class VariantReshape(
    val project: Project,
    val clipsDropped: Int,
    val clipsTruncated: Int,
)

/**
 * Pure-data post-fork reshape. Splitting this out of `ForkProjectTool.execute`
 * keeps the dispatcher short and makes it obvious that the reshape does NOT
 * touch the source DAG, lockfile, or render cache — render cache especially
 * must stay: reshape invalidates memoised exports, but that's handled
 * naturally because `Timeline.resolution` is part of the export cache key
 * (see `RenderCache` logic in `ExportProjectTool`).
 *
 * Extracted from `ForkProjectTool` in the `debt-split-fork-project-tool`
 * cycle to drop the main file back under the long-file threshold.
 */
internal fun applyVariantSpec(project: Project, spec: ForkProjectTool.VariantSpec): VariantReshape {
    var current = project
    if (spec.aspectRatio != null) {
        val target = resolveAspectPreset(spec.aspectRatio)
        current = current.copy(
            timeline = current.timeline.copy(resolution = target),
            outputProfile = current.outputProfile.copy(resolution = target),
        )
    }
    var dropped = 0
    var truncated = 0
    if (spec.durationSecondsMax != null) {
        require(spec.durationSecondsMax > 0.0) {
            "variantSpec.durationSecondsMax must be > 0 (got ${spec.durationSecondsMax})"
        }
        val cap: Duration = spec.durationSecondsMax.seconds
        val trimmedTracks = current.timeline.tracks.map { track ->
            val (newClips, d, t) = trimTrackClips(track.clips, cap)
            dropped += d
            truncated += t
            withTrackClips(track, newClips)
        }
        val cappedDuration = minOf(current.timeline.duration, cap)
        current = current.copy(
            timeline = current.timeline.copy(
                tracks = trimmedTracks,
                duration = cappedDuration,
            ),
        )
    }
    return VariantReshape(current, dropped, truncated)
}

private fun resolveAspectPreset(aspect: String): Resolution = when (aspect.trim().lowercase()) {
    "16:9" -> Resolution(1920, 1080)
    "9:16" -> Resolution(1080, 1920)
    "1:1" -> Resolution(1080, 1080)
    "4:5" -> Resolution(1080, 1350)
    "21:9" -> Resolution(2520, 1080)
    else -> error(
        "variantSpec.aspectRatio '$aspect' unknown; accepted presets: 16:9, 9:16, 1:1, 4:5, 21:9",
    )
}

private fun trimTrackClips(clips: List<Clip>, cap: Duration): Triple<List<Clip>, Int, Int> {
    var dropped = 0
    var truncated = 0
    val out = mutableListOf<Clip>()
    for (clip in clips) {
        val start = clip.timeRange.start
        val end = clip.timeRange.end
        if (start >= cap) {
            dropped += 1
            continue
        }
        if (end <= cap) {
            out += clip
            continue
        }
        // Straddler — truncate timeline range AND source range (same delta).
        val newDuration = cap - start
        val truncatedClip = applyDurationTrim(clip, newDuration)
        out += truncatedClip
        truncated += 1
    }
    return Triple(out, dropped, truncated)
}

private fun applyDurationTrim(clip: Clip, newDuration: Duration): Clip {
    val newTimelineRange = TimeRange(clip.timeRange.start, newDuration)
    return when (clip) {
        is Clip.Video -> clip.copy(
            timeRange = newTimelineRange,
            sourceRange = TimeRange(clip.sourceRange.start, newDuration),
        )
        is Clip.Audio -> clip.copy(
            timeRange = newTimelineRange,
            sourceRange = TimeRange(clip.sourceRange.start, newDuration),
        )
        is Clip.Text -> clip.copy(timeRange = newTimelineRange)
    }
}

private fun withTrackClips(track: Track, clips: List<Clip>): Track = when (track) {
    is Track.Video -> track.copy(clips = clips)
    is Track.Audio -> track.copy(clips = clips)
    is Track.Subtitle -> track.copy(clips = clips)
    is Track.Effect -> track.copy(clips = clips)
}
