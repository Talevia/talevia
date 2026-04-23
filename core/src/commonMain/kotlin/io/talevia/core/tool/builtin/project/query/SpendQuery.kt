package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=spend` — single-row aggregate of AIGC spend across the project's
 * lockfile. [totalCostCents] sums on every entry whose stamped `costCents`
 * is non-null; entries with `null` cost (no pricing rule) are counted in
 * [unknownCostEntries] and NOT rolled into [totalCostCents] — silent
 * zero-coalescing would misrepresent spend as cheaper than it is. [byTool]
 * / [bySession] break the total down so dashboards can answer "which tool
 * / session ate the budget".
 */
@Serializable data class SpendSummaryRow(
    val projectId: String,
    val totalCostCents: Long,
    val entryCount: Int,
    val knownCostEntries: Int,
    val unknownCostEntries: Int,
    val byTool: Map<String, Long> = emptyMap(),
    val bySession: Map<String, Long> = emptyMap(),
    val unknownByTool: Map<String, Int> = emptyMap(),
)

internal fun runSpendQuery(
    project: Project,
): ToolResult<ProjectQueryTool.Output> {
    val entries = project.lockfile.entries

    var totalCents = 0L
    var knownEntries = 0
    var unknownEntries = 0
    val byTool = mutableMapOf<String, Long>()
    val bySession = mutableMapOf<String, Long>()
    val unknownByTool = mutableMapOf<String, Int>()

    for (entry in entries) {
        val cents = entry.costCents
        if (cents == null) {
            unknownEntries++
            unknownByTool[entry.toolId] = (unknownByTool[entry.toolId] ?: 0) + 1
            continue
        }
        knownEntries++
        totalCents += cents
        byTool[entry.toolId] = (byTool[entry.toolId] ?: 0L) + cents
        val sid = entry.sessionId
        if (sid != null) {
            bySession[sid] = (bySession[sid] ?: 0L) + cents
        }
    }

    val row = SpendSummaryRow(
        projectId = project.id.value,
        totalCostCents = totalCents,
        entryCount = entries.size,
        knownCostEntries = knownEntries,
        unknownCostEntries = unknownEntries,
        byTool = byTool.sortedByKey(),
        bySession = bySession.sortedByKey(),
        unknownByTool = unknownByTool.sortedByKey(),
    )
    val rows = encodeRows(
        ListSerializer(SpendSummaryRow.serializer()),
        listOf(row),
    )
    val topTool = byTool.entries.maxByOrNull { it.value }
    val dollars = (totalCents / 100.0).toString().take(10)
    val unknownTail =
        if (unknownEntries == 0) ""
        else ", $unknownEntries unknown-cost entr" + (if (unknownEntries == 1) "y" else "ies")
    val topTail =
        if (topTool == null) ""
        else ", top tool ${topTool.key} (${topTool.value}¢)"
    val summary = "Project ${project.id.value} has spent ~\$$dollars across " +
        "$knownEntries priced entr${if (knownEntries == 1) "y" else "ies"}$unknownTail$topTail."
    return ToolResult(
        title = "project_query spend (${totalCents}¢)",
        outputForLlm = summary,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_SPEND,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}

private fun <V> Map<String, V>.sortedByKey(): Map<String, V> {
    if (isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, V>(size)
    for (k in keys.sorted()) out[k] = getValue(k)
    return out
}
