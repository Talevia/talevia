package io.talevia.core.tool.builtin.project.query

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=timeline_diff` — single-row timeline-only diff between two project
 * payloads on the same project. Answers "what did my timeline change between
 * v1 and v2?" without the source / lockfile noise `diff_projects` also emits.
 *
 * Same-project only. Cross-project diffs (fork vs parent) stay on
 * `diff_projects`. At least one of `fromSnapshotId` / `toSnapshotId` must
 * reference a snapshot — diffing current-vs-current is always identical
 * and almost always a usage error.
 *
 * Detail lists are capped at [MAX_DETAIL] items so a wholesale rewrite can't
 * blow the response into thousands of tokens; [TimelineDiffRow.totalChanges]
 * remains exact.
 *
 * Duplicates the diff math `DiffProjectsTool.diffTimeline` does. Logged in
 * PAIN_POINTS — next cycle that touches either tool should lift this to a
 * shared helper in `core/tool/builtin/project/diff/`.
 */
@Serializable data class TimelineDiffTrackRef(val trackId: String, val kind: String)

@Serializable data class TimelineDiffClipRef(
    val clipId: String,
    val trackId: String,
    val kind: String,
)

@Serializable data class TimelineDiffClipChange(
    val clipId: String,
    val trackId: String,
    val changedFields: List<String>,
)

@Serializable data class TimelineDiffRow(
    /** Human label for the "from" side, echoed so the caller doesn't have to cross-reference. */
    val fromLabel: String,
    /** Human label for the "to" side. */
    val toLabel: String,
    val tracksAdded: List<TimelineDiffTrackRef> = emptyList(),
    val tracksRemoved: List<TimelineDiffTrackRef> = emptyList(),
    val clipsAdded: List<TimelineDiffClipRef> = emptyList(),
    val clipsRemoved: List<TimelineDiffClipRef> = emptyList(),
    val clipsChanged: List<TimelineDiffClipChange> = emptyList(),
    /** True when no timeline delta was observed. */
    val identical: Boolean,
    /** Exact count (not capped) of tracks+clips added+removed+changed. */
    val totalChanges: Int,
)

internal fun runTimelineDiffQuery(
    project: Project,
    input: ProjectQueryTool.Input,
): ToolResult<ProjectQueryTool.Output> {
    val fromSnap = input.fromSnapshotId
    val toSnap = input.toSnapshotId
    require(fromSnap != null || toSnap != null) {
        "select='${ProjectQueryTool.SELECT_TIMELINE_DIFF}' requires at least one of " +
            "fromSnapshotId / toSnapshotId to reference a snapshot; diffing current-vs-current " +
            "is always identical."
    }

    val (fromProject, fromLabel) = resolveSide(project, fromSnap, "from")
    val (toProject, toLabel) = resolveSide(project, toSnap, "to")

    val diff = computeTimelineDiff(fromProject, toProject, fromLabel, toLabel)
    val rows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(TimelineDiffRow.serializer()),
        listOf(diff),
    )

    val summary = if (diff.identical) {
        "$fromLabel → $toLabel: timeline identical (no tracks/clips added, removed, or changed)."
    } else {
        "$fromLabel → $toLabel: timeline ${diff.totalChanges}Δ " +
            "(+${diff.clipsAdded.size}clip / -${diff.clipsRemoved.size}clip / ~${diff.clipsChanged.size}clip" +
            (if (diff.tracksAdded.isNotEmpty() || diff.tracksRemoved.isNotEmpty()) {
                ", ±${diff.tracksAdded.size + diff.tracksRemoved.size} track"
            } else {
                ""
            }) +
            ")."
    }

    return ToolResult(
        title = "project_query timeline_diff ${project.id.value} ($fromLabel → $toLabel)",
        outputForLlm = summary,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_TIMELINE_DIFF,
            total = 1,
            returned = 1,
            rows = rows as kotlinx.serialization.json.JsonArray,
        ),
    )
}

private fun resolveSide(project: Project, snapshotId: String?, side: String): Pair<Project, String> {
    if (snapshotId == null) return project to "${project.id.value} @current"
    val snap = project.snapshots.firstOrNull { it.id == ProjectSnapshotId(snapshotId) }
        ?: error(
            "Snapshot '$snapshotId' not found on project ${project.id.value} ($side side). " +
                "Call project_query(select=snapshots) to list valid snapshot ids.",
        )
    return snap.project to "${project.id.value} @${snap.label}"
}

private fun computeTimelineDiff(
    from: Project,
    to: Project,
    fromLabel: String,
    toLabel: String,
): TimelineDiffRow {
    val fromTracks = from.timeline.tracks.associateBy { it.id.value }
    val toTracks = to.timeline.tracks.associateBy { it.id.value }

    val tracksAdded = (toTracks.keys - fromTracks.keys)
        .map { id -> TimelineDiffTrackRef(id, toTracks.getValue(id).kindString()) }
    val tracksRemoved = (fromTracks.keys - toTracks.keys)
        .map { id -> TimelineDiffTrackRef(id, fromTracks.getValue(id).kindString()) }

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
        TimelineDiffClipRef(id, trackId, clip.kindString())
    }
    val clipsRemoved = (fromClips.keys - toClips.keys).map { id ->
        val (trackId, clip) = fromClips.getValue(id)
        TimelineDiffClipRef(id, trackId, clip.kindString())
    }
    val clipsChanged = (fromClips.keys intersect toClips.keys).mapNotNull { id ->
        val (fromTrackId, fromClip) = fromClips.getValue(id)
        val (toTrackId, toClip) = toClips.getValue(id)
        val fields = changedClipFields(fromTrackId, fromClip, toTrackId, toClip)
        if (fields.isEmpty()) null else TimelineDiffClipChange(id, toTrackId, fields)
    }

    val totalChanges =
        tracksAdded.size + tracksRemoved.size +
            clipsAdded.size + clipsRemoved.size + clipsChanged.size

    return TimelineDiffRow(
        fromLabel = fromLabel,
        toLabel = toLabel,
        tracksAdded = tracksAdded.cap(),
        tracksRemoved = tracksRemoved.cap(),
        clipsAdded = clipsAdded.cap(),
        clipsRemoved = clipsRemoved.cap(),
        clipsChanged = clipsChanged.cap(),
        identical = totalChanges == 0,
        totalChanges = totalChanges,
    )
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

private fun <T> List<T>.cap(): List<T> = if (size <= MAX_DETAIL) this else take(MAX_DETAIL)

/** Per-list cap on detail items. `totalChanges` in the row remains exact. */
private const val MAX_DETAIL: Int = 50
