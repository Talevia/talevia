package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.domain.Project
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray

/**
 * `select=dot` — render the project's Source DAG as a single Graphviz DOT
 * document. Expert path: user pipes the result into `dot -Tsvg`
 * externally to eyeball "why is this character_ref not flowing to that clip".
 *
 * Returns exactly one row: `{dot: String}`. No layout is computed inside Core
 * — Graphviz isn't a KMP dependency — and no filters apply (the whole graph
 * is the thing worth seeing; subset inspection has `select=nodes`).
 *
 * Node labels carry id + kind; nodes with no downstream clip bindings are
 * styled dashed so orphans are visible in the rendered SVG without forcing
 * the caller to diff against [SourceQueryTool.DagSummaryRow.orphanedNodeIds].
 */
internal fun runDotQuery(
    project: Project,
): ToolResult<SourceQueryTool.Output> {
    val dot = buildDot(project)
    val nodeCount = project.source.nodes.size
    val edgeCount = project.source.nodes.sumOf { it.parents.size }
    val row = SourceQueryTool.DotRow(dot = dot, nodeCount = nodeCount, edgeCount = edgeCount)
    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(SourceQueryTool.DotRow.serializer()),
        listOf(row),
    ) as JsonArray

    return ToolResult(
        title = "source_query dot ($nodeCount node${if (nodeCount == 1) "" else "s"}, " +
            "$edgeCount edge${if (edgeCount == 1) "" else "s"})",
        outputForLlm = "Source DAG for '${project.id.value}' as Graphviz DOT " +
            "($nodeCount nodes, $edgeCount edges). Pipe the `dot` field into " +
            "`dot -Tsvg` externally to render.",
        data = SourceQueryTool.Output(
            select = SourceQueryTool.SELECT_DOT,
            total = 1,
            returned = 1,
            rows = jsonRows,
            sourceRevision = project.source.revision,
        ),
    )
}

private fun buildDot(project: Project): String {
    val nodes = project.source.nodes.sortedBy { it.id.value }
    val orphanSet = nodes
        .filter { project.clipsBoundTo(it.id).isEmpty() }
        .mapTo(HashSet()) { it.id }

    val sb = StringBuilder()
    sb.append("digraph SourceDAG {\n")
    sb.append("  rankdir=LR;\n")
    sb.append("  node [shape=box, fontname=\"Helvetica\"];\n")
    for (node in nodes) {
        val id = node.id.value.dotQuote()
        val label = "${node.id.value}\\n${node.kind}".dotLabelQuote()
        val style = if (node.id in orphanSet) ", style=dashed, color=gray50" else ""
        sb.append("  ").append(id).append(" [label=").append(label).append(style).append("];\n")
    }
    // Edges from parent to child so the rendered graph reads upstream → downstream.
    for (node in nodes) {
        val childId = node.id.value.dotQuote()
        for (parent in node.parents) {
            val parentId = parent.nodeId.value.dotQuote()
            sb.append("  ").append(parentId).append(" -> ").append(childId).append(";\n")
        }
    }
    sb.append("}\n")
    return sb.toString()
}

/** Wrap in quotes and escape `\` / `"` for a DOT ID. */
private fun String.dotQuote(): String {
    val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

/** Same as [dotQuote] but preserves the literal `\n` break used in node labels. */
private fun String.dotLabelQuote(): String {
    // Escape first, then restore the `\n` break we deliberately inserted.
    val escaped = replace("\\", "\\\\").replace("\"", "\\\"")
    val withBreak = escaped.replace("\\\\n", "\\n")
    return "\"$withBreak\""
}
