package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.consistency.asBrandPalette
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `scope=all_projects, select=nodes` — enumerate source nodes across every
 * project in the store. Each row carries its owning `projectId` so the caller
 * can pinpoint hits ("this character_ref lives on project X"). Closes the
 * §5.1 "跨 project 复用" rubric — before this, finding a previous
 * character_ref required manual grep across project dumps.
 *
 * Reuses [SourceQueryTool.Input]'s `kind` / `kindPrefix` / `contentSubstring`
 * / `id` / `includeBody` / `limit` / `offset` semantics unchanged; results are
 * globally sorted by the existing `sortBy` with a deterministic `projectId`
 * tiebreaker so the page is stable across calls. `sourceRevision` on the
 * Output is `0L` — not meaningful across multiple projects; per-project
 * revision lands on each row's `projectId` context instead.
 */
internal suspend fun runNodesAllProjectsQuery(
    projects: ProjectStore,
    input: SourceQueryTool.Input,
): ToolResult<SourceQueryTool.Output> {
    val sortKey = input.sortBy?.trim()?.lowercase()?.ifBlank { null } ?: SORT_BY_ID
    require(sortKey in VALID_SORT_KEYS) {
        "Invalid sortBy=\"${input.sortBy}\". Valid values: ${VALID_SORT_KEYS.joinToString(", ")}."
    }
    val limit = (input.limit ?: DEFAULT_LIMIT_ALL).coerceIn(MIN_LIMIT_ALL, MAX_LIMIT_ALL)
    val offset = (input.offset ?: 0).coerceAtLeast(0)
    val caseSensitive = input.caseSensitive ?: false
    val includeBody = input.includeBody ?: false
    val needle = input.contentSubstring?.takeIf { it.isNotEmpty() }

    val everyProject: List<Project> = projects.list()

    data class Hit(
        val node: SourceNode,
        val projectId: String,
        val snippet: String?,
        val offset: Int?,
    )

    val all = mutableListOf<Hit>()
    for (project in everyProject) {
        val pid = project.id.value
        val filtered = project.source.nodes.asSequence()
            .filter { input.id == null || it.id.value == input.id }
            .filter { input.kind == null || it.kind == input.kind }
            .filter { input.kindPrefix == null || it.kind.startsWith(input.kindPrefix) }
            .toList()

        if (needle == null) {
            filtered.forEach { all += Hit(it, pid, null, null) }
        } else {
            val searchNeedle = if (caseSensitive) needle else needle.lowercase()
            for (node in filtered) {
                val serialized = JsonConfig.default.encodeToString(
                    JsonObject.serializer(),
                    node.bodyAsObject(),
                )
                val haystack = if (caseSensitive) serialized else serialized.lowercase()
                val idx = haystack.indexOf(searchNeedle)
                if (idx >= 0) {
                    all += Hit(
                        node = node,
                        projectId = pid,
                        snippet = serialized.snippetAround(idx, searchNeedle.length),
                        offset = idx,
                    )
                }
            }
        }
    }

    val sorted = when (sortKey) {
        SORT_BY_ID -> all.sortedWith(compareBy({ it.node.id.value }, { it.projectId }))
        SORT_BY_KIND -> all.sortedWith(
            compareBy({ it.node.kind }, { it.node.id.value }, { it.projectId }),
        )
        SORT_BY_REVISION_DESC -> all.sortedWith(
            compareByDescending<Hit> { it.node.revision }
                .thenBy { it.node.id.value }
                .thenBy { it.projectId },
        )
        else -> error("unreachable")
    }

    val page = sorted.drop(offset).take(limit)
    val rows = page.map { hit ->
        NodeRow(
            id = hit.node.id.value,
            kind = hit.node.kind,
            revision = hit.node.revision,
            contentHash = hit.node.contentHash,
            parentIds = hit.node.parents.map { it.nodeId.value },
            summary = hit.node.humanSummaryAllProjects(),
            body = if (includeBody) hit.node.body else null,
            snippet = hit.snippet,
            matchOffset = hit.offset,
            projectId = hit.projectId,
        )
    }
    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(NodeRow.serializer()),
        rows,
    )

    val scopeParts = buildList {
        input.kind?.let { add("kind=$it") }
        input.kindPrefix?.let { add("kindPrefix=$it") }
        input.id?.let { add("id=$it") }
        needle?.let { add("contentSubstring='$it'") }
    }
    val scopeLabel = if (scopeParts.isEmpty()) "" else " (${scopeParts.joinToString(", ")})"
    val capNote = if (sorted.size > rows.size) {
        " (showing ${rows.size} of ${sorted.size}; raise limit or use offset to see more)"
    } else {
        ""
    }
    val tail = if (rows.isEmpty()) "no matches"
    else rows.joinToString("\n") { "- ${it.projectId}/${it.id} (${it.kind}): ${it.summary}" }

    return ToolResult(
        title = "source_query nodes scope=all_projects (${rows.size}/${sorted.size})",
        outputForLlm = "Searched ${everyProject.size} project(s), ${sorted.size} match(es)$scopeLabel, " +
            "${rows.size} returned$capNote.\n$tail",
        data = SourceQueryTool.Output(
            select = SourceQueryTool.SELECT_NODES,
            total = sorted.size,
            returned = rows.size,
            rows = jsonRows as JsonArray,
            sourceRevision = 0L, // per-project — not meaningful across the union; see row.projectId
        ),
    )
}

// Local mirrors of the single-project helpers to keep this file self-contained
// without fighting visibility on the existing private ones.

private fun SourceNode.bodyAsObject(): JsonObject = body as? JsonObject ?: JsonObject(emptyMap())

private fun String.snippetAround(matchStart: Int, matchLen: Int): String {
    val from = (matchStart - SNIPPET_RADIUS_ALL).coerceAtLeast(0)
    val to = (matchStart + matchLen + SNIPPET_RADIUS_ALL).coerceAtMost(length)
    val prefix = if (from > 0) "…" else ""
    val suffix = if (to < length) "…" else ""
    return prefix + substring(from, to) + suffix
}

private fun SourceNode.humanSummaryAllProjects(): String {
    asCharacterRef()?.let { return "name=${it.name}; ${it.visualDescription.take(80)}" }
    asStyleBible()?.let { return "name=${it.name}; ${it.description.take(80)}" }
    asBrandPalette()?.let { return "name=${it.name}; ${it.hexColors.size} color(s)" }
    val obj = body as? JsonObject ?: return "(opaque body)"
    val keys = obj.keys.take(5).joinToString(",")
    val firstString = obj.values
        .filterIsInstance<JsonPrimitive>()
        .firstOrNull { it.isString }
        ?.content
        ?.take(60)
    return if (firstString != null) "keys={$keys}; $firstString" else "keys={$keys}"
}

private const val DEFAULT_LIMIT_ALL = 100
private const val MIN_LIMIT_ALL = 1
private const val MAX_LIMIT_ALL = 500
private const val SNIPPET_RADIUS_ALL = 32
