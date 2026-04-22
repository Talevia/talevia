package io.talevia.core.tool.builtin.project.query

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=lockfile_entries` — AIGC lockfile entries on a project,
 * most-recent first. Replaces the pre-consolidation
 * `list_lockfile_entries` tool. Filters:
 *  - [ProjectQueryTool.Input.toolId] (producing tool)
 *  - [ProjectQueryTool.Input.onlyPinned] (hero shots)
 *  - [ProjectQueryTool.Input.sourceNodeId] (entries bound to one source node —
 *    "this character's generation history")
 *  - [ProjectQueryTool.Input.sinceEpochMs] (created at or after this timestamp)
 */
internal fun runLockfileEntriesQuery(
    project: Project,
    input: ProjectQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val all = project.lockfile.entries
    val byToolId = if (input.toolId.isNullOrBlank()) all else all.filter { it.toolId == input.toolId }
    val byPin = if (input.onlyPinned == true) byToolId.filter { it.pinned } else byToolId
    val bySource = input.sourceNodeId?.takeIf { it.isNotBlank() }?.let { nodeId ->
        val target = SourceNodeId(nodeId)
        byPin.filter { target in it.sourceBinding }
    } ?: byPin
    val since = input.sinceEpochMs
    val filtered = if (since != null) {
        bySource.filter { it.provenance.createdAtEpochMs >= since }
    } else {
        bySource
    }
    // entries is append-only / insertion-ordered; reverse so most-recent first.
    val recent = filtered.asReversed().drop(offset).take(limit)

    val rows = recent.map { e ->
        ProjectQueryTool.LockfileEntryRow(
            inputHash = e.inputHash,
            toolId = e.toolId,
            assetId = e.assetId.value,
            providerId = e.provenance.providerId,
            modelId = e.provenance.modelId,
            seed = e.provenance.seed,
            createdAtEpochMs = e.provenance.createdAtEpochMs,
            sourceBindingIds = e.sourceBinding.map { it.value }.sorted(),
            pinned = e.pinned,
        )
    }
    val jsonRows = encodeRows(ListSerializer(ProjectQueryTool.LockfileEntryRow.serializer()), rows)
    val scopeParts = buildList {
        input.toolId?.takeIf { it.isNotBlank() }?.let { add("toolId=$it") }
        if (input.onlyPinned == true) add("pinned")
        input.sourceNodeId?.takeIf { it.isNotBlank() }?.let { add("sourceNodeId=$it") }
        since?.let { add("sinceEpochMs=$it") }
    }
    val body = if (rows.isEmpty()) {
        val scope = if (scopeParts.isEmpty()) "" else " (${scopeParts.joinToString(", ")})"
        "No lockfile entries on project ${project.id.value}$scope."
    } else {
        val scope = if (scopeParts.isEmpty()) "" else " ${scopeParts.joinToString(", ")},"
        "${rows.size} of ${filtered.size} entries$scope most-recent first: " +
            rows.take(5).joinToString("; ") { "${it.toolId}/${it.assetId} (model=${it.modelId})" } +
            if (rows.size > 5) "; …" else ""
    }
    return ToolResult(
        title = "project_query lockfile_entries (${rows.size}/${filtered.size})",
        outputForLlm = body,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_LOCKFILE_ENTRIES,
            total = filtered.size,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
