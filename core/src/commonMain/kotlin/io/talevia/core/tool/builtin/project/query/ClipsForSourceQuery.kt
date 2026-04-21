package io.talevia.core.tool.builtin.project.query

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=clips_for_source` — every clip whose `sourceBinding` intersects
 * the transitive-downstream closure of a given source node id. Replaces
 * the pre-consolidation `list_clips_for_source` tool. Requires
 * [ProjectQueryTool.Input.sourceNodeId]; unknown id throws with a
 * `source_query(select=nodes)` hint.
 */
internal fun runClipsForSourceQuery(
    project: Project,
    input: ProjectQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val sourceNodeIdString = input.sourceNodeId
        ?: error("select='clips_for_source' requires the 'sourceNodeId' filter field.")
    val nodeId = SourceNodeId(sourceNodeIdString)
    if (nodeId !in project.source.byId) {
        error(
            "Source node $sourceNodeIdString not found in project ${project.id.value} — " +
                "call source_query(select=nodes) to discover valid ids.",
        )
    }
    val reports = project.clipsBoundTo(nodeId).map { r ->
        ProjectQueryTool.ClipForSourceRow(
            clipId = r.clipId.value,
            trackId = r.trackId.value,
            assetId = r.assetId?.value,
            directlyBound = r.directlyBound,
            boundVia = r.boundVia.map { it.value },
        )
    }
    val page = reports.drop(offset).take(limit)
    val jsonRows = encodeRows(ListSerializer(ProjectQueryTool.ClipForSourceRow.serializer()), page)
    val body = if (reports.isEmpty()) {
        "No clips bind source node $sourceNodeIdString (directly or transitively)."
    } else {
        val head = page.take(5).joinToString("; ") {
            "${it.clipId}${if (it.directlyBound) "" else " (via ${it.boundVia.joinToString(",")})"}"
        }
        val tail = if (page.size > 5) "; …" else ""
        "${reports.size} clip(s) bind source node $sourceNodeIdString: $head$tail"
    }
    return ToolResult(
        title = "project_query clips_for_source (${page.size}/${reports.size})",
        outputForLlm = body,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_CLIPS_FOR_SOURCE,
            total = reports.size,
            returned = page.size,
            rows = jsonRows,
        ),
    )
}
