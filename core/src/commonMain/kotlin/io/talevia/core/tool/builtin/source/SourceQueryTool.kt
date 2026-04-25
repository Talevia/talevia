package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.query.SOURCE_QUERY_SELECTS_BY_ID
import io.talevia.core.tool.builtin.source.query.SourceQueryDispatchContext
import io.talevia.core.tool.builtin.source.query.runNodesAllProjectsQuery
import io.talevia.core.tool.query.QueryDispatcher
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 *  - `ascii_tree` — same DAG as an indented ASCII tree (box-drawing
 *    `├─` / `└─`). Dependency-free: read it straight in a terminal.
 *    Multi-parent nodes print under each parent with a `(dup)` marker
 *    after the first expansion so the output stays linear.
 *  - `orphans` — every DAG node no clip binds to (same semantics as
 *    [DagSummaryRow.orphanedNodeIds]). Rows: `{id, kind, revision,
 *    parentCount, childCount}`, sorted by id. Dedicated surface so
 *    cleanup workflows skip the `nodes` + O(N) `clips_for_source`
 *    crosschecks needed to derive the same set from `select=nodes`.
 *  - `leaves` — every DAG node with no children (no other node lists
 *    them as parent). Rows: `{id, kind, revision, parentCount}`,
 *    sorted by id. Symmetric companion to `select=nodes&hasParent=false`
 *    (roots) — the downstream tip of a chain. Natural regenerate-after-
 *    edit targets.
 *  - `node_detail` — single-row deep zoom on one node (body, parents
 *    with kinds resolved, direct children, bound clips with `directly`
 *    flag, humanised summary). Requires `id`. Cycle 137 absorbed the
 *    standalone `describe_source_node` tool here, mirroring the earlier
 *    `describe_clip` → `project_query(select=clip_detail)` and
 *    `describe_lockfile_entry` → `project_query(select=lockfile_entry_detail)`
 *    folds — single-entity drill-downs belong on the same dispatcher as
 *    bulk projections.
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
         *  [SELECT_ASCII_TREE] / [SELECT_ORPHANS] / [SELECT_LEAVES] /
         *  [SELECT_DESCENDANTS] / [SELECT_ANCESTORS] / [SELECT_HISTORY] /
         *  [SELECT_NODE_DETAIL] (case-insensitive). */
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
        /**
         * Exact node id. For `select=nodes` it's an optional filter that
         * narrows to ≤1 row; for `select=node_detail` it's required —
         * the node to drill into.
         */
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
            "  • ascii_tree — whole DAG as an indented ASCII tree (box-drawing, orphan / dup " +
            "markers). Dependency-free; reads straight in a terminal. No filters.\n" +
            "  • orphans — rows: {id, kind, revision, parentCount, childCount}. Every node no " +
            "clip binds to (same semantics as dag_summary.orphanedNodeIds). Sorted by id. " +
            "No filters; dedicated to cleanup workflows.\n" +
            "  • leaves — rows: {id, kind, revision, parentCount}. Every node with no children " +
            "(downstream tip of a chain). Sorted by id. No filters; pairs with select=nodes&" +
            "hasParent=false (roots) for the symmetric DAG-tip view.\n" +
            "  • node_detail — single-row deep zoom on one node: {nodeId, kind, revision, " +
            "contentHash, body, parentRefs (with kinds), children, boundClips (with directly " +
            "flag), summary}. requires id. Use before editing a node to see exactly what it " +
            "looks like and what depends on it.\n" +
            "  • descendants — BFS downstream from root; rows carry depthFromRoot (0=root). " +
            "requires root. Optional depth cap (null/negative=unbounded). Cycle-safe.\n" +
            "  • ancestors — BFS upstream from root; same shape as descendants.\n" +
            "  • history — past body snapshots overwritten by update_source_node_body, " +
            "newest-first. requires root. Default limit 20. Empty set when the node never " +
            "had its body updated.\n" +
            "Common: projectId (required unless scope='all_projects'), limit, offset (nodes/" +
            "descendants/ancestors/history only; history ignores offset). scope='all_projects' " +
            "(select=nodes only) enumerates every project and tags rows with projectId. Filter-" +
            "on-wrong-select fails loud."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.read")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = SOURCE_QUERY_INPUT_SCHEMA

    override val selects: Set<String> = ALL_SELECTS

    override fun rowSerializerFor(select: String): KSerializer<*> =
        SOURCE_QUERY_SELECTS_BY_ID[select]?.rowSerializer
            ?: error("No row serializer registered for select='$select'")

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = canonicalSelect(input.select)
        val scope = (input.scope?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }) ?: SCOPE_PROJECT
        if (scope !in ALL_SCOPES) {
            error("scope must be one of ${ALL_SCOPES.joinToString(", ")} (got '${input.scope}')")
        }
        rejectIncompatibleFilters(select, input, scope)

        if (scope == SCOPE_ALL_PROJECTS) {
            // select already validated as SELECT_NODES in rejectIncompatibleFilters.
            // Cross-project scope doesn't fit the per-select registry shape (no
            // single resolved Project to thread into SourceQueryDispatchContext);
            // kept inline as the documented exception (see SourceQuerySelects.kt
            // header for rationale).
            return runNodesAllProjectsQuery(projects, input)
        }

        val projectIdStr = input.projectId
            ?: error("projectId is required for scope='$SCOPE_PROJECT' (the default). Pass scope='$SCOPE_ALL_PROJECTS' for cross-project search.")
        val pid = ProjectId(projectIdStr)
        val project = projects.get(pid) ?: error("Project $projectIdStr not found")

        // Plugin-shape dispatch: each select is a per-id object in
        // `SOURCE_QUERY_SELECTS_BY_ID` carrying its own row serializer
        // + `run` lambda. Adding a new select means dropping a new
        // `object FooSourceQuerySelect : SourceQuerySelect` into the
        // registry — no further edits to this `execute()` body or
        // `rowSerializerFor()` (the const + ALL_SELECTS still need
        // updating for filter-validation, see `SourceQuerySelects.kt`
        // header for the migration recipe).
        val selectImpl = SOURCE_QUERY_SELECTS_BY_ID[select]
            ?: error("unreachable — select validated above: '$select'")
        val dispatchContext = SourceQueryDispatchContext(store = projects, project = project)
        return selectImpl.run(input, ctx, dispatchContext)
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
                // id is also valid for node_detail (required there).
                if (select != SELECT_NODE_DETAIL && input.id != null) {
                    add("id (select=nodes / node_detail only)")
                }
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
        const val SELECT_ASCII_TREE = "ascii_tree"
        const val SELECT_ORPHANS = "orphans"
        const val SELECT_LEAVES = "leaves"
        const val SELECT_DESCENDANTS = "descendants"
        const val SELECT_ANCESTORS = "ancestors"
        const val SELECT_HISTORY = "history"
        const val SELECT_NODE_DETAIL = "node_detail"
        internal val ALL_SELECTS = setOf(
            SELECT_NODES, SELECT_DAG_SUMMARY, SELECT_DOT, SELECT_ASCII_TREE,
            SELECT_ORPHANS, SELECT_LEAVES, SELECT_DESCENDANTS, SELECT_ANCESTORS, SELECT_HISTORY,
            SELECT_NODE_DETAIL,
        )

        const val SCOPE_PROJECT = "project"
        const val SCOPE_ALL_PROJECTS = "all_projects"
        private val ALL_SCOPES = setOf(SCOPE_PROJECT, SCOPE_ALL_PROJECTS)
    }
}
