package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=snapshots` — enumerate saved snapshots on a project, newest-first.
 * Replaces the deleted `list_project_snapshots` tool
 * (debt-fold-list-project-snapshots cycle). Filters:
 *  - [ProjectQueryTool.Input.maxAgeDays] — drop snapshots captured strictly
 *    earlier than `clock.now() - maxAgeDays` (inclusive cutoff).
 *  - [ProjectQueryTool.Input.limit] — post-filter cap (default 50, clamped
 *    1..500 for this select to mirror the old tool; the outer 1..500 clamp
 *    applies, so this select's effective bounds are identical).
 *
 * Row shape [ProjectQueryTool.SnapshotRow] is a compact summary (id / label /
 * captured-at / clip+track+asset counts) — the full captured Project payload
 * is not surfaced (blows up the LLM output and isn't needed to plan a
 * restore; `restore_project_snapshot` still takes the full payload route).
 */
internal fun runSnapshotsQuery(
    project: Project,
    input: ProjectQueryTool.Input,
    clock: Clock,
): ToolResult<ProjectQueryTool.Output> {
    input.maxAgeDays?.let {
        require(it >= 0) { "maxAgeDays must be >= 0 (got $it)" }
    }

    val cutoffEpochMs = input.maxAgeDays?.let { days ->
        clock.now().toEpochMilliseconds() - days.toLong() * MS_PER_DAY
    }
    val filtered = project.snapshots
        .asSequence()
        .filter { cutoffEpochMs == null || it.capturedAtEpochMs >= cutoffEpochMs }
        .sortedByDescending { it.capturedAtEpochMs }
        .toList()

    // Apply the same clamp ProjectQueryTool.execute uses — null → 50
    // (snapshot-query default, lower than the generic 100 to match the
    // old list_project_snapshots defaults), then coerce into [1, 500].
    // `offset` exists on Input but was never accepted by the old
    // list_project_snapshots tool; mirror the looser surface here so
    // paging generalises.
    val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)
    val offset = (input.offset ?: 0).coerceAtLeast(0)
    val page = filtered.drop(offset).take(limit)

    val rows = page.map { snap ->
        val captured = snap.project
        ProjectQueryTool.SnapshotRow(
            snapshotId = snap.id.value,
            label = snap.label,
            capturedAtEpochMs = snap.capturedAtEpochMs,
            clipCount = captured.timeline.tracks.sumOf { it.clips.size },
            trackCount = captured.timeline.tracks.size,
            assetCount = captured.assets.size,
        )
    }
    val jsonRows = encodeRows(ListSerializer(ProjectQueryTool.SnapshotRow.serializer()), rows)

    val scopeParts = buildList {
        input.maxAgeDays?.let { add("maxAgeDays=$it") }
    }
    val body = if (rows.isEmpty()) {
        val scope = if (scopeParts.isEmpty()) "" else " (${scopeParts.joinToString(", ")})"
        "No matching snapshots on project ${project.id.value}$scope."
    } else {
        rows.joinToString("; ") {
            "${it.snapshotId} \"${it.label}\" (${it.clipCount} clip(s))"
        }
    }

    return ToolResult(
        title = "project_query snapshots (${rows.size}/${filtered.size})",
        outputForLlm = body,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_SNAPSHOTS,
            total = filtered.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}

private const val DEFAULT_LIMIT: Int = 50
private const val MIN_LIMIT: Int = 1
private const val MAX_LIMIT: Int = 500
private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L
