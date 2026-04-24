package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Tiny stateless helpers shared by the six clip-verb handlers split out of
 * [ClipActionTool]. Keeping them at top-level (file-private internal) avoids
 * each handler re-implementing the same pattern match against [Clip] /
 * [Track] and keeps the per-handler files focused on their verb's business
 * logic.
 *
 * **What changed in this file resizes the split ceiling?** New [Clip]
 * subclasses (e.g. 3D / particle clip) cascade into every `when` here —
 * they're the Kotlin-idiomatic cost of a sealed hierarchy, not debt.
 * Independently-growing helpers (a new `applyFadeEnvelope` unrelated to
 * existing ones) belong in their own file; don't pile them in here.
 */

@OptIn(ExperimentalUuidApi::class)
internal fun pickVideoTrack(tracks: List<Track>, requestedId: String?): Track.Video {
    val match = if (requestedId != null) {
        val requested = tracks.firstOrNull { it.id.value == requestedId }
            ?: error("trackId $requestedId not found")
        if (requested !is Track.Video) {
            error("trackId $requestedId is not a video track")
        }
        requested
    } else {
        tracks.firstOrNull { it is Track.Video }
    }
    return match as? Track.Video ?: Track.Video(TrackId(Uuid.random().toString()))
}

@OptIn(ExperimentalUuidApi::class)
internal fun cloneClip(original: Clip, newId: ClipId, newStart: Duration): Clip {
    val r = TimeRange(newStart, original.timeRange.duration)
    return when (original) {
        is Clip.Video -> original.copy(id = newId, timeRange = r)
        is Clip.Audio -> original.copy(id = newId, timeRange = r)
        is Clip.Text -> original.copy(id = newId, timeRange = r)
    }
}

@OptIn(ExperimentalUuidApi::class)
internal fun splitClip(c: Clip, offset: Duration): Pair<Clip, Clip> {
    val leftId = ClipId(Uuid.random().toString())
    val rightId = ClipId(Uuid.random().toString())
    val lt = TimeRange(c.timeRange.start, offset)
    val rt = TimeRange(c.timeRange.start + offset, c.timeRange.duration - offset)
    return when (c) {
        is Clip.Video -> {
            val ls = TimeRange(c.sourceRange.start, offset)
            val rs = TimeRange(c.sourceRange.start + offset, c.sourceRange.duration - offset)
            c.copy(id = leftId, timeRange = lt, sourceRange = ls) to c.copy(id = rightId, timeRange = rt, sourceRange = rs)
        }
        is Clip.Audio -> {
            val ls = TimeRange(c.sourceRange.start, offset)
            val rs = TimeRange(c.sourceRange.start + offset, c.sourceRange.duration - offset)
            c.copy(id = leftId, timeRange = lt, sourceRange = ls) to c.copy(id = rightId, timeRange = rt, sourceRange = rs)
        }
        is Clip.Text -> c.copy(id = leftId, timeRange = lt) to c.copy(id = rightId, timeRange = rt)
    }
}

internal fun trackKindOf(track: Track): String = when (track) {
    is Track.Video -> "video"; is Track.Audio -> "audio"; is Track.Subtitle -> "subtitle"; is Track.Effect -> "effect"
}

internal fun clipKindOf(clip: Clip): String = when (clip) {
    is Clip.Video -> "video"; is Clip.Audio -> "audio"; is Clip.Text -> "text"
}

internal fun isKindCompatible(clip: Clip, track: Track): Boolean = when (clip) {
    is Clip.Video -> track is Track.Video
    is Clip.Audio -> track is Track.Audio
    is Clip.Text -> track is Track.Subtitle
}

internal fun withClips(track: Track, clips: List<Clip>): Track = when (track) {
    is Track.Video -> track.copy(clips = clips)
    is Track.Audio -> track.copy(clips = clips)
    is Track.Subtitle -> track.copy(clips = clips)
    is Track.Effect -> track.copy(clips = clips)
}

internal fun withTimeRange(clip: Clip, range: TimeRange): Clip = when (clip) {
    is Clip.Video -> clip.copy(timeRange = range)
    is Clip.Audio -> clip.copy(timeRange = range)
    is Clip.Text -> clip.copy(timeRange = range)
}

internal fun withTrim(c: Clip, timelineRange: TimeRange, sourceRange: TimeRange): Clip = when (c) {
    is Clip.Video -> c.copy(timeRange = timelineRange, sourceRange = sourceRange)
    is Clip.Audio -> c.copy(timeRange = timelineRange, sourceRange = sourceRange)
    is Clip.Text -> error("action=trim cannot trim a text clip")
}

internal fun replaceClip(track: Track, replacement: Clip): Track = withClips(
    track,
    track.clips.map { if (it.id == replacement.id) replacement else it }.sortedBy { it.timeRange.start },
)

internal fun Clip.shiftStart(delta: Duration): Clip =
    withTimeRange(this, timeRange.copy(start = timeRange.start + delta))

internal fun recomputeClipBoundDuration(tracks: List<Track>): Duration =
    tracks.flatMap { it.clips }.maxOfOrNull { it.timeRange.end } ?: Duration.ZERO

/**
 * Shared per-verb input-shape guard. Rejects payload fields that don't
 * belong to the selected action — fails fast and loud so a typo in
 * `clipIds` vs `splitItems` surfaces instead of silently ignoring the
 * extra field and doing nothing.
 */
internal fun rejectForeignClipActionFields(action: String, input: ClipActionTool.Input) {
    val foreign = buildList {
        if (action != "add" && input.addItems != null) add("addItems")
        if (action != "remove" && input.clipIds != null) add("clipIds")
        if (action != "duplicate" && input.duplicateItems != null) add("duplicateItems")
        if (action != "move" && input.moveItems != null) add("moveItems")
        if (action != "split" && input.splitItems != null) add("splitItems")
        if (action != "trim" && input.trimItems != null) add("trimItems")
        if (action != "replace" && input.replaceItems != null) add("replaceItems")
        if (action != "fade" && input.fadeItems != null) add("fadeItems")
    }
    require(foreign.isEmpty()) {
        "action=$action rejects ${foreign.joinToString(" / ")} — use this action's own payload field"
    }
}
