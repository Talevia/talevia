package io.talevia.core.tool.builtin.project.diff

import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.Track

/**
 * Shared timeline-diff math between [io.talevia.core.tool.builtin.project.DiffProjectsTool]
 * and `project_query(select=timeline_diff)` (in
 * [io.talevia.core.tool.builtin.project.query.TimelineDiffQuery]).
 *
 * Both tools used to copy ~40 lines of set-diff math + `changedClipFields` +
 * `kindString` helpers. This module is the one live copy; each tool adapts
 * the neutral [TimelineDiffRaw] output into its own serialized `@Serializable`
 * row types, so the two tools' public output shapes stay exactly as they
 * were before the extraction — only internal wiring moves.
 *
 * Scope:
 * - Track add / remove matched by `Track.id`; kind stringified via [kindString].
 * - Clip add / remove / change matched by `Clip.id` (cross-track moves are
 *   "change" with a `track` field, not remove+add).
 * - Per-list cap at [maxDetail] items; raw totals remain exact at each list's
 *   size BEFORE the cap is applied — callers compute the exact total by
 *   summing the raw lists, then call [cap] on each list.
 *
 * Intentionally internal (module-visibility): neither tool's output surface
 * should leak [TimelineDiffRaw] to the LLM or to downstream callers — the
 * stable public types live on each tool. If a third site needs this math,
 * revisit the visibility then, not now (§3a-1: don't grow abstractions
 * without a concrete third caller).
 */
internal data class RawTrackRef(val trackId: String, val kind: String)

internal data class RawClipRef(val clipId: String, val trackId: String, val kind: String)

internal data class RawClipChange(
    val clipId: String,
    val trackId: String,
    val changedFields: List<String>,
)

internal data class TimelineDiffRaw(
    val tracksAdded: List<RawTrackRef>,
    val tracksRemoved: List<RawTrackRef>,
    val clipsAdded: List<RawClipRef>,
    val clipsRemoved: List<RawClipRef>,
    val clipsChanged: List<RawClipChange>,
) {
    /** Exact total across all five sections, pre-cap. */
    val totalChanges: Int get() = tracksAdded.size + tracksRemoved.size +
        clipsAdded.size + clipsRemoved.size + clipsChanged.size
}

/** Per-list cap callers typically apply. Kept here so both sites share a constant. */
internal const val TIMELINE_DIFF_MAX_DETAIL: Int = 50

internal fun <T> List<T>.capTimelineDiff(max: Int = TIMELINE_DIFF_MAX_DETAIL): List<T> =
    if (size <= max) this else take(max)

/**
 * Compute the raw diff. Callers adapt into their own row types.
 *
 * The result's list sizes are the *exact* pre-cap counts — to emit the
 * capped detail lists that the tools' outputs want, the caller should
 * `tracksAdded.capTimelineDiff()` etc. `totalChanges` stays exact
 * regardless.
 */
internal fun computeTimelineDiffRaw(from: Project, to: Project): TimelineDiffRaw {
    val fromTracks = from.timeline.tracks.associateBy { it.id.value }
    val toTracks = to.timeline.tracks.associateBy { it.id.value }

    val tracksAdded = (toTracks.keys - fromTracks.keys).map { id ->
        RawTrackRef(id, toTracks.getValue(id).kindString())
    }
    val tracksRemoved = (fromTracks.keys - toTracks.keys).map { id ->
        RawTrackRef(id, fromTracks.getValue(id).kindString())
    }

    val fromClips = buildMap {
        from.timeline.tracks.forEach { t ->
            t.clips.forEach { c -> put(c.id.value, t.id.value to c) }
        }
    }
    val toClips = buildMap {
        to.timeline.tracks.forEach { t ->
            t.clips.forEach { c -> put(c.id.value, t.id.value to c) }
        }
    }

    val clipsAdded = (toClips.keys - fromClips.keys).map { id ->
        val (trackId, clip) = toClips.getValue(id)
        RawClipRef(id, trackId, clip.kindString())
    }
    val clipsRemoved = (fromClips.keys - toClips.keys).map { id ->
        val (trackId, clip) = fromClips.getValue(id)
        RawClipRef(id, trackId, clip.kindString())
    }
    val clipsChanged = (fromClips.keys intersect toClips.keys).mapNotNull { id ->
        val (fromTrackId, fromClip) = fromClips.getValue(id)
        val (toTrackId, toClip) = toClips.getValue(id)
        val fields = changedClipFields(fromTrackId, fromClip, toTrackId, toClip)
        if (fields.isEmpty()) null else RawClipChange(id, toTrackId, fields)
    }

    return TimelineDiffRaw(tracksAdded, tracksRemoved, clipsAdded, clipsRemoved, clipsChanged)
}

private fun changedClipFields(fromTrack: String, from: Clip, toTrack: String, to: Clip): List<String> {
    val fields = mutableListOf<String>()
    if (fromTrack != toTrack) fields += "track"
    if (from::class != to::class) {
        // Different concrete clip subtype (e.g. video → text). Treat as one
        // catch-all "kind" change rather than enumerating subtype fields.
        fields += "kind"
        return fields
    }
    if (from.timeRange != to.timeRange) fields += "timeRange"
    if (from.sourceRange != to.sourceRange) fields += "sourceRange"
    if (from.transforms != to.transforms) fields += "transforms"
    if (from.sourceBinding != to.sourceBinding) fields += "sourceBinding"
    when (from) {
        is Clip.Video -> {
            val t = to as Clip.Video
            if (from.assetId != t.assetId) fields += "assetId"
            if (from.filters != t.filters) fields += "filters"
        }
        is Clip.Audio -> {
            val t = to as Clip.Audio
            if (from.assetId != t.assetId) fields += "assetId"
            if (from.volume != t.volume) fields += "volume"
        }
        is Clip.Text -> {
            val t = to as Clip.Text
            if (from.text != t.text) fields += "text"
            if (from.style != t.style) fields += "style"
        }
    }
    return fields
}

private fun Track.kindString(): String = when (this) {
    is Track.Video -> "video"
    is Track.Audio -> "audio"
    is Track.Subtitle -> "subtitle"
    is Track.Effect -> "effect"
}

private fun Clip.kindString(): String = when (this) {
    is Clip.Video -> "video"
    is Clip.Audio -> "audio"
    is Clip.Text -> "text"
}
