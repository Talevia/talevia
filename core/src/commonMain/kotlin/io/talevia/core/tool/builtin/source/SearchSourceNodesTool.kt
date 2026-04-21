package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Full-text search across source-node bodies — VISION §5.4 专家路径 ergonomic
 * for navigating larger DAGs. `list_source_nodes` filters by id / kind /
 * kindPrefix and `describe_source_node` drills into one; neither answers
 * "find every node that mentions 'neon'" when the user is scanning a 40-node
 * narrative project for motif references or a marketing project for every
 * mention of a specific product SKU.
 *
 * Serializes each node's [SourceNode.body] through the canonical
 * [kotlinx.serialization.json.Json] so the search substrate is a stable
 * string (field order preserved, number formatting consistent across
 * re-encodes). Case-insensitive substring match by default because almost
 * every real-world query is case-forgiving ("neon" should find "Neon"); the
 * caller can pass `caseSensitive = true` for brand-name / ISO-code lookups
 * where a capitalization hit is the signal.
 *
 * Returns a per-match snippet around the first hit (±32 chars), clipped with
 * `…` markers, so the LLM can assess relevance without dragging every body
 * into context. The `body` field is *not* returned — callers who need the
 * full body follow up with `describe_source_node` on the specific id.
 *
 * Optional `kind` narrows the match set to one kind (e.g. only
 * `narrative.scene` nodes). Optional `limit` caps the result count (default
 * 20). Read-only; permission `source.read`.
 */
class SearchSourceNodesTool(
    private val projects: ProjectStore,
) : Tool<SearchSourceNodesTool.Input, SearchSourceNodesTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** Non-blank substring to search for in each node's serialized body. */
        val query: String,
        /** Optional exact-kind filter (e.g. narrative.scene, ad.variant_request). */
        val kind: String? = null,
        /** Case-sensitive match? Default false. */
        val caseSensitive: Boolean = false,
        /** Cap on returned hits. Default 20, max 200. */
        val limit: Int? = null,
    )

    @Serializable data class Match(
        val id: String,
        val kind: String,
        val parentIds: List<String>,
        /** Excerpt around the first hit, ±[SNIPPET_RADIUS] chars, clipped with "…". */
        val snippet: String,
        /** Character offset of the match within the serialized body. */
        val matchOffset: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val query: String,
        val caseSensitive: Boolean,
        val totalNodes: Int,
        val returnedMatches: Int,
        val matches: List<Match>,
    )

    override val id: String = "search_source_nodes"
    override val helpText: String =
        "Full-text substring search across source-node bodies. Case-insensitive by default. " +
            "Returns a ±32-char snippet around the first hit per node, plus the node's id / kind / " +
            "parents. Use before editing when you need to find every node that mentions a character " +
            "name / motif / SKU — faster than walking list_source_nodes + describe_source_node in a loop."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("query") {
                put("type", "string")
                put("description", "Non-blank substring. Matched against each node's JSON-serialized body.")
            }
            putJsonObject("kind") {
                put("type", "string")
                put("description", "Optional exact-kind filter (e.g. narrative.scene, ad.variant_request).")
            }
            putJsonObject("caseSensitive") {
                put("type", "boolean")
                put("description", "Case-sensitive match? Default false.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Cap on returned hits. Default 20, max 200.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("query"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.query.isNotBlank()) { "query must not be blank" }
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val allNodes = project.source.nodes
        val scoped = if (input.kind.isNullOrBlank()) allNodes else allNodes.filter { it.kind == input.kind }

        val cap = (input.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val needle = if (input.caseSensitive) input.query else input.query.lowercase()

        val matches = mutableListOf<Match>()
        for (node in scoped) {
            val serialized = JsonConfig.default.encodeToString(JsonObject.serializer(), node.bodyAsObject())
            val haystack = if (input.caseSensitive) serialized else serialized.lowercase()
            val idx = haystack.indexOf(needle)
            if (idx < 0) continue
            matches += Match(
                id = node.id.value,
                kind = node.kind,
                parentIds = node.parents.map { it.nodeId.value },
                snippet = serialized.snippet(idx, needle.length),
                matchOffset = idx,
            )
            if (matches.size >= cap) break
        }

        val out = Output(
            projectId = pid.value,
            query = input.query,
            caseSensitive = input.caseSensitive,
            totalNodes = allNodes.size,
            returnedMatches = matches.size,
            matches = matches,
        )
        val summary = if (matches.isEmpty()) {
            val scope = input.kind?.let { " kind=$it" } ?: ""
            "No source node on project ${pid.value}$scope contains '${input.query}'."
        } else {
            "${matches.size} match(es) for '${input.query}' on ${pid.value}: " +
                matches.take(5).joinToString("; ") { "${it.id} (${it.kind})" } +
                if (matches.size > 5) "; …" else ""
        }
        return ToolResult(
            title = "search source nodes '${input.query}'",
            outputForLlm = summary,
            data = out,
        )
    }

    private fun SourceNode.bodyAsObject(): JsonObject = body as? JsonObject ?: JsonObject(emptyMap())

    private fun String.snippet(matchStart: Int, matchLen: Int): String {
        val from = (matchStart - SNIPPET_RADIUS).coerceAtLeast(0)
        val to = (matchStart + matchLen + SNIPPET_RADIUS).coerceAtMost(length)
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < length) "…" else ""
        return prefix + substring(from, to) + suffix
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MAX_LIMIT = 200
        private const val SNIPPET_RADIUS = 32
    }
}
