package io.talevia.core.tool.builtin.project.query

import io.talevia.core.SourceNodeId
import io.talevia.core.domain.Project
import io.talevia.core.domain.clipsBoundTo
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `select=consistency_propagation` — VISION §5.5 audit: did the bound
 * consistency source node (character_ref / style_bible / brand_palette /
 * …) actually reach the prompts of its bound clips' AIGC generations?
 *
 * For each clip in the transitive-downstream closure of
 * [ProjectQueryTool.Input.sourceNodeId], looks up the AIGC lockfile
 * entry (via the clip's asset id, if any) and checks whether the
 * node's body string values appear as substrings (case-insensitive)
 * in the entry's `baseInputs.prompt`. Non-AIGC clips (e.g. text clips
 * without an asset), and clips whose asset lacks a lockfile entry,
 * are still reported with `aigcEntryFound=false` so the auditor sees
 * the full surface.
 *
 * Keyword extraction is intentionally simple: top-level string values
 * in the node's body JSON. Long values and digits are kept as-is;
 * blank strings and primitives that aren't strings are skipped. The
 * caller (a linter, an LLM, a UI) can compose further heuristics;
 * this primitive exposes the raw hit-map.
 */
internal fun runConsistencyPropagationQuery(
    project: Project,
    input: ProjectQueryTool.Input,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val sourceNodeIdString = input.sourceNodeId
        ?: error("select='consistency_propagation' requires the 'sourceNodeId' filter field.")
    val nodeId = SourceNodeId(sourceNodeIdString)
    val node = project.source.byId[nodeId]
        ?: error(
            "Source node $sourceNodeIdString not found in project ${project.id.value} — " +
                "call source_query(select=nodes) to discover valid ids.",
        )

    val keywords = extractKeywords(node.body)
    val reports = project.clipsBoundTo(nodeId).map { r ->
        val aid = r.assetId
        val entry = aid?.let { project.lockfile.findByAssetId(it) }
        val prompt = entry?.baseInputs?.get("prompt")?.let { (it as? JsonPrimitive)?.content }
        val matched = if (prompt == null) {
            emptyList()
        } else {
            val needle = prompt.lowercase()
            keywords.filter { kw -> kw.lowercase() in needle }
        }
        ProjectQueryTool.ConsistencyPropagationRow(
            clipId = r.clipId.value,
            trackId = r.trackId.value,
            assetId = aid?.value,
            directlyBound = r.directlyBound,
            boundVia = r.boundVia.map { it.value },
            aigcEntryFound = entry != null,
            lockfileInputHash = entry?.inputHash,
            aigcToolId = entry?.toolId,
            keywordsInBody = keywords,
            keywordsMatchedInPrompt = matched,
            promptContainsKeywords = matched.isNotEmpty(),
        )
    }
    val page = reports.drop(offset).take(limit)
    val rows = encodeRows(ListSerializer(ProjectQueryTool.ConsistencyPropagationRow.serializer()), page)
    val propagated = reports.count { it.promptContainsKeywords }
    val covered = reports.count { it.aigcEntryFound }
    val summary = buildString {
        append("Source node $sourceNodeIdString (${node.kind}) keywords: ")
        append(keywords.joinToString(", ").ifEmpty { "none" })
        append(". ")
        append("${reports.size} bound clip(s), ")
        append("$covered with AIGC lockfile entry, ")
        append("$propagated with prompt containing ≥1 keyword.")
    }
    return ToolResult(
        title = "project_query consistency_propagation ${nodeId.value}",
        outputForLlm = summary,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_CONSISTENCY_PROPAGATION,
            total = reports.size,
            returned = page.size,
            rows = rows,
        ),
    )
}

/**
 * Flatten top-level string values from [body] into a de-duplicated,
 * order-preserving list. Nested objects / arrays are skipped for now —
 * real consistency nodes (character_ref / style_bible / brand_palette)
 * keep their load-bearing name / description / visualDescription fields
 * at the top level, so this covers the realistic cases without the
 * combinatorial blow-up of deep traversal. Blank strings are dropped.
 */
private fun extractKeywords(body: JsonElement): List<String> {
    if (body !is JsonObject) return emptyList()
    val out = linkedSetOf<String>()
    for ((_, value) in body) {
        if (value !is JsonPrimitive || !value.isString) continue
        val s = value.content
        if (s.isBlank()) continue
        out += s
    }
    // Extension point: if nested arrays of strings start mattering
    // (e.g. style_bible.aesthetics: ["cinematic", "warm"]), walk them
    // inside the `for` loop above.
    return out.toList()
}
