package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.query.BodyRevisionRow
import io.talevia.core.tool.builtin.source.query.DagSummaryRow
import io.talevia.core.tool.builtin.source.query.DotRow
import io.talevia.core.tool.builtin.source.query.NodeRow
import io.talevia.core.tool.builtin.source.query.runAncestorsQuery
import io.talevia.core.tool.builtin.source.query.runDagSummaryQuery
import io.talevia.core.tool.builtin.source.query.runDescendantsQuery
import io.talevia.core.tool.builtin.source.query.runDotQuery
import io.talevia.core.tool.builtin.source.query.runHistoryQuery
import io.talevia.core.tool.builtin.source.query.runNodesAllProjectsQuery
import io.talevia.core.tool.builtin.source.query.runNodesQuery
import io.talevia.core.tool.query.QueryDispatcher
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
 *  - `dot` — the whole source DAG as a Graphviz DOT document. Single-row
 *    `{dot: String}`; caller pipes into `dot -Tsvg` externally to render.
 *    Expert path for "eyeball why this character_ref isn't feeding that clip"
 *    — we don't take a Graphviz dependency, just emit text.
 *
 * `describe_source_node` stays as a separate tool — it's single-entity deep
 * inspection (body + parents + children + bindings), not projection. Same
 * split `project_query` made with `describe_clip` / `describe_lockfile_entry`.
 *
 * Output is uniform: `{select, total, returned, rows}` where `rows` is a
 * [JsonArray] whose shape depends on `select`. Consumers decode with the
 * matching row serializer ([NodeRow.serializer()] / [DagSummaryRow.serializer()]
 * / [DotRow.serializer()]); wire encoding is [JsonConfig.default].
 */
class SourceQueryTool(
    private val projects: ProjectStore,
) : QueryDispatcher<SourceQueryTool.Input, SourceQueryTool.Output>() {

    @Serializable data class Input(
        /** One of [SELECT_NODES] / [SELECT_DAG_SUMMARY] / [SELECT_DOT] /
         *  [SELECT_DESCENDANTS] / [SELECT_ANCESTORS] / [SELECT_HISTORY]
         *  (case-insensitive). */
        val select: String,
        /**
         * Target project. Required when `scope == "project"` (default) or unset.
         * When `scope == "all_projects"` (select=nodes only), **must** be null —
         * the all-projects scope enumerates every project in the store, so a
         * projectId filter contradicts it. Keeping projectId nullable rather
         * than introducing a polymorphic Input shape keeps the schema flat.
         */
        val projectId: String? = null,
        /**
         * Search scope. `null` or `"project"` = current-behaviour single-
         * project query. `"all_projects"` = cross-project search — requires
         * `projectId == null`, `select=nodes`, and surfaces a `projectId`
         * field on every row so the caller can pin-point the hit. Case-
         * insensitive. Other values fail loud.
         */
        val scope: String? = null,
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
        /**
         * Filter by DAG position. `true` returns only nodes with ≥1 parent
         * (children-of-something); `false` returns only roots (no parents);
         * `null` (default) returns all. `select=nodes` only. Rubric §5.1
         * follow-up to `dag_summary.rootNodeIds` — lets a caller iterate
         * roots or leaves as regular `NodeRow`s without a second dispatch.
         */
        val hasParent: Boolean? = null,
        // ---- dag_summary filters ----
        /** Max hotspots to return. `select=dag_summary` only. Default 5. */
        val hotspotLimit: Int? = null,
        // ---- descendants / ancestors filters ----
        /**
         * Source node id to traverse from. Required for `select=descendants`,
         * `select=ancestors`, and `select=history`; rejected for other
         * selects. Descendants / ancestors walk the DAG BFS (cycle-safe);
         * history reads `<bundle>/source-history/<root>.jsonl`. Unknown id
         * fails loud with a `source_query(select=nodes)` hint (descendants /
         * ancestors) or returns an empty set (history — a never-updated node
         * legitimately has no revisions).
         */
        val root: String? = null,
        /**
         * Maximum hop count from [root] to include. `0` = root-only (degenerate
         * but useful for "does this node exist?"). Positive N = up to N hops.
         * Null or negative = unbounded transitive closure (the common case).
         * `select=descendants` / `select=ancestors` only.
         */
        val depth: Int? = null,
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

    // Row types — NodeRow / DagSummaryRow / DotRow / Hotspot — live in
    // `io.talevia.core.tool.builtin.source.query` as top-level
    // @Serializable data classes alongside their handlers. See
    // `QueryDispatcher` KDoc for the top-level-row convention.

    override val id: String = "source_query"
    override val helpText: String =
        "Unified read-only query over a project's Source DAG. Pick one `select`:\n" +
            "  • nodes — rows: {id, kind, revision, contentHash, parentIds, summary, body?, " +
            "snippet?, matchOffset?}. filter: kind, kindPrefix, contentSubstring " +
            "(case-insensitive default), id (exact), hasParent. sortBy: id|kind|revision-desc. " +
            "includeBody=true for full JSON. Default limit 100 (clamped 1..500).\n" +
            "  • dag_summary — {nodeCount, nodesByKind, rootNodeIds, leafNodeIds, maxDepth, " +
            "hotspots, orphanedNodeIds, summaryText}. filter: hotspotLimit (default 5).\n" +
            "  • dot — whole DAG as Graphviz DOT (unbound-downstream nodes dashed). No filters.\n" +
            "  • descendants — BFS downstream from root; rows carry depthFromRoot (0=root). " +
            "requires root. Optional depth cap (null/negative=unbounded). Cycle-safe.\n" +
            "  • ancestors — BFS upstream from root; same shape as descendants.\n" +
            "  • history — past body snapshots overwritten by update_source_node_body, " +
            "newest-first. requires root. Default limit 20. Empty set when the node never " +
            "had its body updated.\n" +
            "Common: projectId (required unless scope='all_projects'), limit, offset (nodes/" +
            "descendants/ancestors/history only; history ignores offset). scope='all_projects' " +
            "(select=nodes only) enumerates every project and tags rows with projectId. Filter-" +
            "on-wrong-select fails loud. describe_source_node handles single-entity deep views."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.read")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("select") {
                put("type", "string")
                put(
                    "description",
                    "What to query: nodes | dag_summary | dot | descendants | ancestors | history " +
                        "(case-insensitive).",
                )
            }
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Target project. Required unless scope='all_projects'. Rejected when " +
                        "scope='all_projects' (the cross-project search enumerates every project).",
                )
            }
            putJsonObject("scope") {
                put("type", "string")
                put(
                    "description",
                    "project (default, single-project query) | all_projects (select=nodes only — " +
                        "enumerates every project in the store; each row carries its owning projectId).",
                )
            }
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
            putJsonObject("hasParent") {
                put("type", "boolean")
                put("description", "true=children, false=roots. select=nodes.")
            }
            putJsonObject("hotspotLimit") {
                put("type", "integer")
                put(
                    "description",
                    "Max hotspots in the dag_summary row. Default 5. select=dag_summary only.",
                )
            }
            putJsonObject("root") {
                put("type", "string")
                put(
                    "description",
                    "Source node id to traverse from. Required for select=descendants / " +
                        "select=ancestors / select=history; rejected elsewhere.",
                )
            }
            putJsonObject("depth") {
                put("type", "integer")
                put(
                    "description",
                    "Max hop count from root (0 = root only, positive = bounded, null or " +
                        "negative = unbounded). select=descendants / select=ancestors only.",
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
        put("required", JsonArray(listOf(JsonPrimitive("select"))))
        put("additionalProperties", false)
    }

    override val selects: Set<String> = ALL_SELECTS

    override fun rowSerializerFor(select: String): KSerializer<*> = when (select) {
        SELECT_NODES, SELECT_DESCENDANTS, SELECT_ANCESTORS -> NodeRow.serializer()
        SELECT_DAG_SUMMARY -> DagSummaryRow.serializer()
        SELECT_DOT -> DotRow.serializer()
        SELECT_HISTORY -> BodyRevisionRow.serializer()
        else -> error("No row serializer registered for select='$select'")
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = canonicalSelect(input.select)
        val scope = (input.scope?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) ?: SCOPE_PROJECT
        if (scope !in ALL_SCOPES) {
            error("scope must be one of ${ALL_SCOPES.joinToString(", ")} (got '${input.scope}')")
        }
        rejectIncompatibleFilters(select, input, scope)

        if (scope == SCOPE_ALL_PROJECTS) {
            // select already validated as SELECT_NODES in rejectIncompatibleFilters
            return runNodesAllProjectsQuery(projects, input)
        }

        val projectIdStr = input.projectId
            ?: error("projectId is required for scope='$SCOPE_PROJECT' (the default). Pass scope='$SCOPE_ALL_PROJECTS' for cross-project search.")
        val pid = ProjectId(projectIdStr)
        val project = projects.get(pid) ?: error("Project $projectIdStr not found")

        return when (select) {
            SELECT_NODES -> runNodesQuery(project, input)
            SELECT_DAG_SUMMARY -> runDagSummaryQuery(project, input)
            SELECT_DOT -> runDotQuery(project)
            SELECT_DESCENDANTS -> runDescendantsQuery(project, input)
            SELECT_ANCESTORS -> runAncestorsQuery(project, input)
            SELECT_HISTORY -> runHistoryQuery(projects, project, input)
            else -> error("unreachable — select validated above: '$select'")
        }
    }

    private fun rejectIncompatibleFilters(select: String, input: Input, scope: String) {
        val isRelativesSelect = select == SELECT_DESCENDANTS || select == SELECT_ANCESTORS
        val isRootAnchoredSelect = isRelativesSelect || select == SELECT_HISTORY
        // history takes limit (for capping revision count) but not offset.
        val isPaginatedSelect = select == SELECT_NODES || isRelativesSelect || select == SELECT_HISTORY
        val misapplied = buildList {
            if (select != SELECT_NODES) {
                if (input.kind != null) add("kind (select=nodes only)")
                if (input.kindPrefix != null) add("kindPrefix (select=nodes only)")
                if (input.contentSubstring != null) add("contentSubstring (select=nodes only)")
                if (input.caseSensitive != null) add("caseSensitive (select=nodes only)")
                if (input.id != null) add("id (select=nodes only)")
                if (input.sortBy != null) add("sortBy (select=nodes only)")
                if (input.hasParent != null) add("hasParent (select=nodes only)")
            }
            // includeBody is useful for both nodes and the relatives selects — someone
            // auditing "what's in my character_ref's downstream" will want full bodies.
            // history always includes the full body (that's its point), so ignore the
            // field on that select rather than rejecting (lenient default).
            if (select != SELECT_NODES && !isRelativesSelect && select != SELECT_HISTORY && input.includeBody != null) {
                add("includeBody (select=nodes / descendants / ancestors only)")
            }
            if (!isPaginatedSelect) {
                if (input.limit != null) add("limit (select=nodes / descendants / ancestors / history only)")
                if (input.offset != null) add("offset (select=nodes / descendants / ancestors only)")
            }
            // history ignores offset explicitly — the JSONL is small, no need for
            // pagination beyond the `limit` cap.
            if (select == SELECT_HISTORY && input.offset != null) {
                add("offset (select=nodes / descendants / ancestors only — history caps via limit, not offset)")
            }
            if (select != SELECT_DAG_SUMMARY && input.hotspotLimit != null) {
                add("hotspotLimit (select=dag_summary only)")
            }
            if (!isRootAnchoredSelect) {
                if (input.root != null) add("root (select=descendants / ancestors / history only)")
                if (input.depth != null) add("depth (select=descendants / ancestors only)")
            }
            if (select == SELECT_HISTORY && input.depth != null) {
                add("depth (select=descendants / ancestors only — history is a flat revision log)")
            }
        }
        if (misapplied.isNotEmpty()) {
            error(
                "The following filter fields do not apply to select='$select': " +
                    misapplied.joinToString(", "),
            )
        }
        if (scope == SCOPE_ALL_PROJECTS) {
            require(select == SELECT_NODES) {
                "scope='$SCOPE_ALL_PROJECTS' is only valid with select=nodes (got select='$select'); " +
                    "cross-project DAG summary / DOT export isn't meaningful — each project has its own DAG."
            }
            require(input.projectId.isNullOrBlank()) {
                "scope='$SCOPE_ALL_PROJECTS' and projectId are mutually exclusive " +
                    "(got projectId='${input.projectId}'); pass scope='$SCOPE_PROJECT' for a single-project query."
            }
        }
    }

    companion object {
        const val SELECT_NODES = "nodes"
        const val SELECT_DAG_SUMMARY = "dag_summary"
        const val SELECT_DOT = "dot"
        const val SELECT_DESCENDANTS = "descendants"
        const val SELECT_ANCESTORS = "ancestors"
        const val SELECT_HISTORY = "history"
        internal val ALL_SELECTS = setOf(
            SELECT_NODES, SELECT_DAG_SUMMARY, SELECT_DOT,
            SELECT_DESCENDANTS, SELECT_ANCESTORS, SELECT_HISTORY,
        )

        const val SCOPE_PROJECT = "project"
        const val SCOPE_ALL_PROJECTS = "all_projects"
        private val ALL_SCOPES = setOf(SCOPE_PROJECT, SCOPE_ALL_PROJECTS)
    }
}
