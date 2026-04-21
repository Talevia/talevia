package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.query.runDagSummaryQuery
import io.talevia.core.tool.builtin.source.query.runNodesQuery
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Unified read-only query primitive over a project's Source DAG — source-lane
 * counterpart of [io.talevia.core.tool.builtin.project.ProjectQueryTool] and
 * [io.talevia.core.tool.builtin.session.SessionQueryTool].
 *
 * [Input.select] discriminates what to return:
 *  - `nodes` — enumerate source nodes with filters. Replaces `list_source_nodes`
 *    and `search_source_nodes`; the `contentSubstring` filter covers the old
 *    full-text search path (case-insensitive by default; emits snippet + match
 *    offset on each matching [NodeRow]).
 *  - `dag_summary` — bird's-eye structural summary: per-kind counts, roots,
 *    leaves, max depth, hotspots, orphans. Replaces `describe_source_dag`.
 *    Always returns exactly one [DagSummaryRow].
 *
 * `describe_source_node` stays as a separate tool — it's single-entity deep
 * inspection (body + parents + children + bindings), not projection. Same
 * split `project_query` made with `describe_clip` / `describe_lockfile_entry`.
 *
 * Output is uniform: `{select, total, returned, rows}` where `rows` is a
 * [JsonArray] whose shape depends on `select`. Consumers decode with the
 * matching row serializer (`SourceQueryTool.NodeRow.serializer()` /
 * `DagSummaryRow.serializer()`); wire encoding is [JsonConfig.default].
 */
class SourceQueryTool(
    private val projects: ProjectStore,
) : Tool<SourceQueryTool.Input, SourceQueryTool.Output> {

    @Serializable data class Input(
        /** One of [SELECT_NODES] / [SELECT_DAG_SUMMARY] (case-insensitive). */
        val select: String,
        val projectId: String,
        // ---- nodes filters ----
        /** Exact kind filter (e.g. `"core.consistency.character_ref"`). `select=nodes` only. */
        val kind: String? = null,
        /** Kind prefix filter (e.g. `"core.consistency."`). `select=nodes` only. */
        val kindPrefix: String? = null,
        /** Substring match against each node's JSON-serialized body. `select=nodes` only. */
        val contentSubstring: String? = null,
        /** Case-sensitive match for [contentSubstring]. Default false. `select=nodes` only. */
        val caseSensitive: Boolean? = null,
        /** Exact node id (e.g. `"char.mei"`). `select=nodes` only — returns ≤1 row. */
        val id: String? = null,
        /** Include each node's full JSON body. `select=nodes` only. Default false. */
        val includeBody: Boolean? = null,
        /** `"id"` | `"kind"` | `"revision-desc"`. `select=nodes` only. Default `"id"`. */
        val sortBy: String? = null,
        // ---- dag_summary filters ----
        /** Max hotspots to return. `select=dag_summary` only. Default 5. */
        val hotspotLimit: Int? = null,
        // ---- common ----
        /** Post-filter cap. `select=nodes` only. Default 100, clamped `[1, 500]`. */
        val limit: Int? = null,
        /** Skip the first N rows. `select=nodes` only. Default 0. */
        val offset: Int? = null,
    )

    @Serializable data class Output(
        /** Echo of the (normalised) select used to produce [rows]. */
        val select: String,
        /** Sum of matches after filters, before offset/limit. For `dag_summary` it's always 1. */
        val total: Int,
        /** Count of rows in [rows]. */
        val returned: Int,
        /** Select-specific row objects, serialised via [JsonConfig.default]. */
        val rows: JsonArray,
        /** Echo the current source revision so callers can detect drift between queries. */
        val sourceRevision: Long,
    )

    // ---- row data classes (nested public — tests + UI decode via .serializer()) ----

    @Serializable data class NodeRow(
        val id: String,
        val kind: String,
        val revision: Long,
        val contentHash: String,
        val parentIds: List<String>,
        /** Short human-readable summary (name + clip-description for typed nodes, key list for opaque). */
        val summary: String,
        /** Full JSON body — populated only when [Input.includeBody] is true. */
        val body: JsonElement? = null,
        /** Excerpt around the first `contentSubstring` hit. Populated only when that filter is set. */
        val snippet: String? = null,
        /** Character offset of the `contentSubstring` match. Populated only when that filter is set. */
        val matchOffset: Int? = null,
    )

    @Serializable data class Hotspot(
        val nodeId: String,
        val kind: String,
        val directClipCount: Int,
        val transitiveClipCount: Int,
    )

    @Serializable data class DagSummaryRow(
        val nodeCount: Int,
        val nodesByKind: Map<String, Int>,
        val rootNodeIds: List<String>,
        val leafNodeIds: List<String>,
        val maxDepth: Int,
        val hotspots: List<Hotspot>,
        val orphanedNodeIds: List<String>,
        val summaryText: String,
    )

    override val id: String = "source_query"
    override val helpText: String =
        "Unified read-only query over a project's Source DAG (replaces list_source_nodes / " +
            "search_source_nodes / describe_source_dag). Pick one `select`:\n" +
            "  • nodes — rows: {id, kind, revision, contentHash, parentIds, summary, body?, " +
            "snippet?, matchOffset?}. filter: kind, kindPrefix, contentSubstring (case-insensitive " +
            "by default; set caseSensitive=true for brand / code lookups), id (exact). sortBy: id " +
            "(default) | kind | revision-desc. includeBody=true for the full JSON. Default limit 100 " +
            "(clamped 1..500).\n" +
            "  • dag_summary — one row: {nodeCount, nodesByKind, rootNodeIds, leafNodeIds, " +
            "maxDepth, hotspots (ranked by downstream-clip count), orphanedNodeIds, summaryText}. " +
            "filter: hotspotLimit (default 5). No limit/offset — always one row.\n" +
            "Common: projectId (required), limit, offset (select=nodes only). Setting a filter that " +
            "doesn't apply to the chosen select fails loud. describe_source_node stays as a separate " +
            "tool for single-entity deep views."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("select") {
                put("type", "string")
                put("description", "What to query: nodes | dag_summary (case-insensitive).")
            }
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("kind") {
                put("type", "string")
                put(
                    "description",
                    "Exact kind filter (e.g. core.consistency.character_ref). select=nodes only.",
                )
            }
            putJsonObject("kindPrefix") {
                put("type", "string")
                put(
                    "description",
                    "Kind prefix filter (e.g. core.consistency.). select=nodes only.",
                )
            }
            putJsonObject("contentSubstring") {
                put("type", "string")
                put(
                    "description",
                    "Substring match against each node's JSON-serialized body. " +
                        "Matching rows carry snippet + matchOffset. select=nodes only.",
                )
            }
            putJsonObject("caseSensitive") {
                put("type", "boolean")
                put(
                    "description",
                    "Case-sensitive match for contentSubstring. Default false. select=nodes only.",
                )
            }
            putJsonObject("id") {
                put("type", "string")
                put(
                    "description",
                    "Exact node id — returns ≤1 row. select=nodes only. Use describe_source_node " +
                        "for full body + parent/children relations.",
                )
            }
            putJsonObject("includeBody") {
                put("type", "boolean")
                put(
                    "description",
                    "Include each node's full JSON body in the result. Default false. select=nodes only.",
                )
            }
            putJsonObject("sortBy") {
                put("type", "string")
                put(
                    "description",
                    "Sort key — id (default), kind, revision-desc. select=nodes only.",
                )
            }
            putJsonObject("hotspotLimit") {
                put("type", "integer")
                put(
                    "description",
                    "Max hotspots in the dag_summary row. Default 5. select=dag_summary only.",
                )
            }
            putJsonObject("limit") {
                put("type", "integer")
                put(
                    "description",
                    "Cap on returned rows (default 100, clamped 1..500). select=nodes only.",
                )
            }
            putJsonObject("offset") {
                put("type", "integer")
                put("description", "Skip N rows after filter+sort. select=nodes only. Default 0.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("select"), JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = input.select.trim().lowercase()
        if (select !in ALL_SELECTS) {
            error("select must be one of ${ALL_SELECTS.joinToString(", ")} (got '${input.select}')")
        }
        rejectIncompatibleFilters(select, input)

        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")

        return when (select) {
            SELECT_NODES -> runNodesQuery(project, input)
            SELECT_DAG_SUMMARY -> runDagSummaryQuery(project, input)
            else -> error("unreachable — select validated above: '$select'")
        }
    }

    private fun rejectIncompatibleFilters(select: String, input: Input) {
        val misapplied = buildList {
            if (select != SELECT_NODES) {
                if (input.kind != null) add("kind (select=nodes only)")
                if (input.kindPrefix != null) add("kindPrefix (select=nodes only)")
                if (input.contentSubstring != null) add("contentSubstring (select=nodes only)")
                if (input.caseSensitive != null) add("caseSensitive (select=nodes only)")
                if (input.id != null) add("id (select=nodes only)")
                if (input.includeBody != null) add("includeBody (select=nodes only)")
                if (input.sortBy != null) add("sortBy (select=nodes only)")
                if (input.limit != null) add("limit (select=nodes only)")
                if (input.offset != null) add("offset (select=nodes only)")
            }
            if (select != SELECT_DAG_SUMMARY && input.hotspotLimit != null) {
                add("hotspotLimit (select=dag_summary only)")
            }
        }
        if (misapplied.isNotEmpty()) {
            error(
                "The following filter fields do not apply to select='$select': " +
                    misapplied.joinToString(", "),
            )
        }
    }

    companion object {
        const val SELECT_NODES = "nodes"
        const val SELECT_DAG_SUMMARY = "dag_summary"
        private val ALL_SELECTS = setOf(SELECT_NODES, SELECT_DAG_SUMMARY)
    }
}
