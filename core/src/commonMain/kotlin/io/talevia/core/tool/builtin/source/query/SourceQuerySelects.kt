package io.talevia.core.tool.builtin.source.query

import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.source.SourceQueryTool
import io.talevia.core.tool.query.QuerySelect
import kotlinx.serialization.KSerializer

/**
 * Per-`execute()` resolved-context object for [SourceQueryTool] —
 * carries the project this select was scoped to plus the global
 * `ProjectStore` (only `select=history` reaches into the store today,
 * but bundling both means future cross-project selects don't have to
 * re-resolve).
 *
 * Built once at the top of `SourceQueryTool.execute()` and threaded
 * into every [QuerySelect.run] so per-select handlers don't re-resolve
 * the project / store on every dispatch.
 */
internal data class SourceQueryDispatchContext(
    val store: ProjectStore,
    val project: Project,
)

/**
 * Type alias for source-side [QuerySelect] — fixes the
 * dispatcher-wide `Input` / `Output` types so each impl just declares
 * its row serializer + run lambda.
 */
internal typealias SourceQuerySelect =
    QuerySelect<SourceQueryTool.Input, SourceQueryTool.Output, SourceQueryDispatchContext>

/**
 * Cycle-154 plugin-shape registry for the 10 single-project selects on
 * `source_query` — `nodes` / `dag_summary` / `dot` / `ascii_tree` /
 * `orphans` / `leaves` / `descendants` / `ancestors` / `history` /
 * `node_detail`. Each entry wraps an existing `runXQuery(...)`
 * function so the migration is shape-only — no behaviour change, no
 * row-serializer change, no LLM-visible diff.
 *
 * `nodes` cross-project scope (`scope="all_projects"`) stays in
 * `SourceQueryTool.execute()` as a special case before this registry
 * dispatches — its handler (`runNodesAllProjectsQuery`) doesn't take a
 * resolved [Project] (it iterates over every project in the store), so
 * the [SourceQueryDispatchContext] shape doesn't fit it. Adding
 * cross-project shape to the QuerySelect interface would constrain
 * every per-tool context type to optionally-null-project, which loses
 * the type-safety this design buys for the common single-project
 * path.
 *
 * Adding a new select after this cycle: drop a new
 * `internal object FooSourceQuerySelect : SourceQuerySelect { ... }`
 * into this file (or its own sibling), append it to
 * [SOURCE_QUERY_SELECTS], add the const + ALL_SELECTS entry on
 * `SourceQueryTool` companion (the const is still the source of
 * truth for filter-validation arms), and you're done — no more
 * editing the dispatcher's `when` arm or `rowSerializerFor`.
 *
 * **Migration path for the other dispatchers** (the bullet's
 * follow-up cycles):
 *  1. `ProjectQueryTool` — same pattern, ~20 selects, dispatch
 *     context = `(ProjectStore, Project)` (most selects already
 *     pre-resolve project; some take an `outputDir` for export
 *     paths — those become a tagged context).
 *  2. `SessionQueryTool` — same pattern, 28 selects, dispatch
 *     context = `(SessionStore, Session, AgentRunStateTracker?,
 *     PermissionHistoryRecorder?, BusEventTraceRecorder?,
 *     ProjectStore?)` — the bigger context tuple matches the
 *     existing nullable-aggregator wires.
 *  3. `ProviderQueryTool` — smallest, ~5 selects, dispatch context
 *     = `(ProviderRegistry, ProviderWarmupStats?,
 *     RateLimitHistoryRecorder?)`.
 *
 * Each future migration is mechanical (wrap existing handler) +
 * verifiable (existing tests pass byte-identical).
 */
internal val SOURCE_QUERY_SELECTS: List<SourceQuerySelect> = listOf(
    NodesSourceQuerySelect,
    DagSummarySourceQuerySelect,
    DotSourceQuerySelect,
    AsciiTreeSourceQuerySelect,
    OrphansSourceQuerySelect,
    LeavesSourceQuerySelect,
    DescendantsSourceQuerySelect,
    AncestorsSourceQuerySelect,
    HistorySourceQuerySelect,
    NodeDetailSourceQuerySelect,
)

/** Indexed lookup for `SourceQueryTool.execute()` / `rowSerializerFor()`. */
internal val SOURCE_QUERY_SELECTS_BY_ID: Map<String, SourceQuerySelect> =
    SOURCE_QUERY_SELECTS.associateBy { it.id }

internal object NodesSourceQuerySelect : SourceQuerySelect {
    override val id: String = SourceQueryTool.SELECT_NODES
    override val rowSerializer: KSerializer<*> = NodeRow.serializer()
    override suspend fun run(
        input: SourceQueryTool.Input,
        ctx: ToolContext,
        dispatchContext: SourceQueryDispatchContext,
    ): ToolResult<SourceQueryTool.Output> = runNodesQuery(dispatchContext.project, input)
}

internal object DagSummarySourceQuerySelect : SourceQuerySelect {
    override val id: String = SourceQueryTool.SELECT_DAG_SUMMARY
    override val rowSerializer: KSerializer<*> = DagSummaryRow.serializer()
    override suspend fun run(
        input: SourceQueryTool.Input,
        ctx: ToolContext,
        dispatchContext: SourceQueryDispatchContext,
    ): ToolResult<SourceQueryTool.Output> = runDagSummaryQuery(dispatchContext.project, input)
}

internal object DotSourceQuerySelect : SourceQuerySelect {
    override val id: String = SourceQueryTool.SELECT_DOT
    override val rowSerializer: KSerializer<*> = DotRow.serializer()
    override suspend fun run(
        input: SourceQueryTool.Input,
        ctx: ToolContext,
        dispatchContext: SourceQueryDispatchContext,
    ): ToolResult<SourceQueryTool.Output> = runDotQuery(dispatchContext.project)
}

internal object AsciiTreeSourceQuerySelect : SourceQuerySelect {
    override val id: String = SourceQueryTool.SELECT_ASCII_TREE
    override val rowSerializer: KSerializer<*> = AsciiTreeRow.serializer()
    override suspend fun run(
        input: SourceQueryTool.Input,
        ctx: ToolContext,
        dispatchContext: SourceQueryDispatchContext,
    ): ToolResult<SourceQueryTool.Output> = runAsciiTreeQuery(dispatchContext.project)
}

internal object OrphansSourceQuerySelect : SourceQuerySelect {
    override val id: String = SourceQueryTool.SELECT_ORPHANS
    override val rowSerializer: KSerializer<*> = OrphanRow.serializer()
    override suspend fun run(
        input: SourceQueryTool.Input,
        ctx: ToolContext,
        dispatchContext: SourceQueryDispatchContext,
    ): ToolResult<SourceQueryTool.Output> = runOrphansQuery(dispatchContext.project)
}

internal object LeavesSourceQuerySelect : SourceQuerySelect {
    override val id: String = SourceQueryTool.SELECT_LEAVES
    override val rowSerializer: KSerializer<*> = LeafRow.serializer()
    override suspend fun run(
        input: SourceQueryTool.Input,
        ctx: ToolContext,
        dispatchContext: SourceQueryDispatchContext,
    ): ToolResult<SourceQueryTool.Output> = runLeavesQuery(dispatchContext.project)
}

internal object DescendantsSourceQuerySelect : SourceQuerySelect {
    override val id: String = SourceQueryTool.SELECT_DESCENDANTS
    override val rowSerializer: KSerializer<*> = NodeRow.serializer()
    override suspend fun run(
        input: SourceQueryTool.Input,
        ctx: ToolContext,
        dispatchContext: SourceQueryDispatchContext,
    ): ToolResult<SourceQueryTool.Output> = runDescendantsQuery(dispatchContext.project, input)
}

internal object AncestorsSourceQuerySelect : SourceQuerySelect {
    override val id: String = SourceQueryTool.SELECT_ANCESTORS
    override val rowSerializer: KSerializer<*> = NodeRow.serializer()
    override suspend fun run(
        input: SourceQueryTool.Input,
        ctx: ToolContext,
        dispatchContext: SourceQueryDispatchContext,
    ): ToolResult<SourceQueryTool.Output> = runAncestorsQuery(dispatchContext.project, input)
}

internal object HistorySourceQuerySelect : SourceQuerySelect {
    override val id: String = SourceQueryTool.SELECT_HISTORY
    override val rowSerializer: KSerializer<*> = BodyRevisionRow.serializer()
    override suspend fun run(
        input: SourceQueryTool.Input,
        ctx: ToolContext,
        dispatchContext: SourceQueryDispatchContext,
    ): ToolResult<SourceQueryTool.Output> = runHistoryQuery(
        dispatchContext.store,
        dispatchContext.project,
        input,
    )
}

internal object NodeDetailSourceQuerySelect : SourceQuerySelect {
    override val id: String = SourceQueryTool.SELECT_NODE_DETAIL
    override val rowSerializer: KSerializer<*> = NodeDetailRow.serializer()
    override suspend fun run(
        input: SourceQueryTool.Input,
        ctx: ToolContext,
        dispatchContext: SourceQueryDispatchContext,
    ): ToolResult<SourceQueryTool.Output> = runNodeDetailQuery(dispatchContext.project, input)
}
