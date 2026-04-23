package io.talevia.core.tool.builtin.source.query

import io.talevia.core.JsonConfig
import io.talevia.core.domain.Project
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.consistency.asBrandPalette
import io.talevia.core.domain.source.consistency.asCharacterRef
import io.talevia.core.domain.source.consistency.asStyleBible
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `select=nodes` — enumerate [SourceNode]s with optional kind / kindPrefix /
 * id / contentSubstring filters. Replaces both `list_source_nodes` and
 * `search_source_nodes`; when [SourceQueryTool.Input.contentSubstring] is set
 * each matching row carries snippet + matchOffset.
 */
internal fun runNodesQuery(
    project: Project,
    input: SourceQueryTool.Input,
): ToolResult<SourceQueryTool.Output> {
    val sortKey = input.sortBy?.trim()?.lowercase()?.ifBlank { null } ?: SORT_BY_ID
    require(sortKey in VALID_SORT_KEYS) {
        "Invalid sortBy=\"${input.sortBy}\". Valid values: ${VALID_SORT_KEYS.joinToString(", ")}."
    }
    val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)
    val offset = (input.offset ?: 0).coerceAtLeast(0)
    val caseSensitive = input.caseSensitive ?: false
    val includeBody = input.includeBody ?: false

    val all = project.source.nodes
    val filtered = all.asSequence()
        .filter { input.id == null || it.id.value == input.id }
        .filter { input.kind == null || it.kind == input.kind }
        .filter { input.kindPrefix == null || it.kind.startsWith(input.kindPrefix) }
        .toList()

    // Apply contentSubstring filter separately so matching rows can carry snippet + offset.
    val needle = input.contentSubstring?.takeIf { it.isNotEmpty() }
    data class Hit(val node: SourceNode, val snippet: String?, val offset: Int?)
    val afterContent: List<Hit> = if (needle == null) {
        filtered.map { Hit(it, null, null) }
    } else {
        val searchNeedle = if (caseSensitive) needle else needle.lowercase()
        filtered.mapNotNull { node ->
            val serialized = JsonConfig.default.encodeToString(JsonObject.serializer(), node.bodyAsObject())
            val haystack = if (caseSensitive) serialized else serialized.lowercase()
            val idx = haystack.indexOf(searchNeedle)
            if (idx < 0) null else Hit(node, serialized.snippetAround(idx, searchNeedle.length), idx)
        }
    }

    val sorted = when (sortKey) {
        SORT_BY_ID -> afterContent.sortedBy { it.node.id.value }
        SORT_BY_KIND -> afterContent.sortedWith(compareBy({ it.node.kind }, { it.node.id.value }))
        SORT_BY_REVISION_DESC -> afterContent.sortedWith(
            compareByDescending<Hit> { it.node.revision }.thenBy { it.node.id.value },
        )
        else -> error("unreachable")
    }

    val page = sorted.drop(offset).take(limit)
    val rows = page.map { hit -> hit.node.toRow(includeBody = includeBody, snippet = hit.snippet, offset = hit.offset) }
    val jsonRows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(NodeRow.serializer()),
        rows,
    )

    val tail = if (rows.isEmpty()) "no matches"
    else rows.joinToString("\n") { "- ${it.id} (${it.kind}): ${it.summary}" }
    val capNote = if (sorted.size > rows.size) {
        " (showing ${rows.size} of ${sorted.size}; raise limit or use offset to see more)"
    } else {
        ""
    }
    val scopeParts = buildList {
        input.kind?.let { add("kind=$it") }
        input.kindPrefix?.let { add("kindPrefix=$it") }
        input.id?.let { add("id=$it") }
        needle?.let { add("contentSubstring='$it'") }
    }
    val scopeLabel = if (scopeParts.isEmpty()) "" else " (${scopeParts.joinToString(", ")})"
    return ToolResult(
        title = "source_query nodes (${rows.size}/${sorted.size})",
        outputForLlm = "Source revision ${project.source.revision}, ${all.size} total node(s), " +
            "${sorted.size} match(es)$scopeLabel, ${rows.size} returned$capNote.\n$tail",
        data = SourceQueryTool.Output(
            select = SourceQueryTool.SELECT_NODES,
            total = sorted.size,
            returned = rows.size,
            rows = jsonRows as kotlinx.serialization.json.JsonArray,
            sourceRevision = project.source.revision,
        ),
    )
}

private fun SourceNode.toRow(
    includeBody: Boolean,
    snippet: String?,
    offset: Int?,
): NodeRow = NodeRow(
    id = id.value,
    kind = kind,
    revision = revision,
    contentHash = contentHash,
    parentIds = parents.map { it.nodeId.value },
    summary = humanSummary(),
    body = if (includeBody) body else null,
    snippet = snippet,
    matchOffset = offset,
)

private fun SourceNode.humanSummary(): String {
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

private fun SourceNode.bodyAsObject(): JsonObject = body as? JsonObject ?: JsonObject(emptyMap())

private fun String.snippetAround(matchStart: Int, matchLen: Int): String {
    val from = (matchStart - SNIPPET_RADIUS).coerceAtLeast(0)
    val to = (matchStart + matchLen + SNIPPET_RADIUS).coerceAtMost(length)
    val prefix = if (from > 0) "…" else ""
    val suffix = if (to < length) "…" else ""
    return prefix + substring(from, to) + suffix
}

internal const val SORT_BY_ID = "id"
internal const val SORT_BY_KIND = "kind"
internal const val SORT_BY_REVISION_DESC = "revision-desc"
internal val VALID_SORT_KEYS = setOf(SORT_BY_ID, SORT_BY_KIND, SORT_BY_REVISION_DESC)
private const val DEFAULT_LIMIT = 100
private const val MIN_LIMIT = 1
private const val MAX_LIMIT = 500
private const val SNIPPET_RADIUS = 32
