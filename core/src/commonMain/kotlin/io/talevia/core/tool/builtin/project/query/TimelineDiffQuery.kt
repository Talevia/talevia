package io.talevia.core.tool.builtin.project.query

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import io.talevia.core.tool.builtin.project.diff.capTimelineDiff
import io.talevia.core.tool.builtin.project.diff.computeTimelineDiffRaw
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
 * Detail lists are capped at [io.talevia.core.tool.builtin.project.diff.TIMELINE_DIFF_MAX_DETAIL]
 * so a wholesale rewrite can't blow the response into thousands of tokens;
 * [TimelineDiffRow.totalChanges] remains exact.
 *
 * Math is shared with `DiffProjectsTool.diffTimeline` via
 * [computeTimelineDiffRaw] — change one, both stay in sync.
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

    val raw = computeTimelineDiffRaw(fromProject, toProject)
    val diff = TimelineDiffRow(
        fromLabel = fromLabel,
        toLabel = toLabel,
        tracksAdded = raw.tracksAdded.map { TimelineDiffTrackRef(it.trackId, it.kind) }.capTimelineDiff(),
        tracksRemoved = raw.tracksRemoved.map { TimelineDiffTrackRef(it.trackId, it.kind) }.capTimelineDiff(),
        clipsAdded = raw.clipsAdded.map { TimelineDiffClipRef(it.clipId, it.trackId, it.kind) }.capTimelineDiff(),
        clipsRemoved = raw.clipsRemoved.map { TimelineDiffClipRef(it.clipId, it.trackId, it.kind) }.capTimelineDiff(),
        clipsChanged = raw.clipsChanged.map { TimelineDiffClipChange(it.clipId, it.trackId, it.changedFields) }.capTimelineDiff(),
        identical = raw.totalChanges == 0,
        totalChanges = raw.totalChanges,
    )
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
