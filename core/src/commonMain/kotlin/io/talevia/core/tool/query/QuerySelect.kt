package io.talevia.core.tool.query

import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer

/**
 * A single-`select` plugin for a [QueryDispatcher].
 *
 * Each concrete dispatcher (`source_query` / `project_query` /
 * `session_query` / `provider_query`) accepts an `Input.select`
 * discriminator to choose between many query shapes — `nodes` /
 * `dag_summary` / `dot` / `ancestors` / etc. on the source side, ~28
 * variants on the session side. Pre-cycle 154 each new select required
 * editing the dispatcher in 3-4 places (the `when` arm in
 * `execute()`, the matching arm in `rowSerializerFor()`, the
 * `ALL_SELECTS` set, and frequently `rejectIncompatibleFilters` too).
 *
 * This interface gives each select its own object that bundles the
 * three things the dispatcher needs at runtime — `id`, `rowSerializer`,
 * and `run` — so adding a select means dropping a new object into a
 * registry instead of touching the central dispatcher's switch arms.
 *
 * `I` is the dispatcher-wide Input type (the same `Input` that the
 * tool's `inputSerializer` decodes); each select reads only the
 * fields relevant to it. `O` is the dispatcher's Output type — the
 * select's `run` produces a complete `ToolResult<O>` so it can carry
 * its own `outputForLlm` summary plus whatever metadata fields the
 * Output carries (`total` / `returned` / `select` / etc.). `C` is the
 * dispatcher-specific context object built once per `execute()` call
 * (e.g. the resolved `Project` + `ProjectStore` for source; the
 * resolved `Session` + `SessionStore` for session) and threaded into
 * every select's `run` so per-select handlers don't re-resolve.
 *
 * Cycle 154 introduces the interface and migrates SourceQueryTool's
 * 10 selects as proof-of-concept; the migration recipe is documented
 * in `git log -S 'QuerySelect' --grep 'debt-unified-dispatcher'` so
 * later cycles can take ProjectQueryTool / SessionQueryTool /
 * ProviderQueryTool one at a time without re-deriving the shape.
 *
 * Note: `rejectIncompatibleFilters` (the input-validation guard
 * that rejects "this filter doesn't apply to that select") stays
 * per-dispatcher because it needs to see ALL selects' field
 * compatibility at once. The dispatch / row-serializer split is what
 * the §3a #12 architecture-tax trigger pinpointed; filter
 * compatibility is a different (and smaller) per-tool concern.
 */
interface QuerySelect<I : Any, O : Any, C : Any> {
    /** Lowercase, canonical select id (e.g. `"dot"`, `"node_detail"`). */
    val id: String

    /**
     * Serializer for the row type the select emits. Always the
     * concrete row serializer (e.g. `DotRow.serializer()`), NOT a
     * `ListSerializer(...)` wrapper — the dispatcher / consumers
     * wrap when they need a list.
     */
    val rowSerializer: KSerializer<*>

    /**
     * Execute the select. [input] is the dispatcher-wide Input
     * (read only the fields relevant to this select); [ctx] is the
     * standard tool context; [dispatchContext] is the dispatcher-
     * specific resolved-context object (typically `Project` /
     * `Session` plus the matching store).
     */
    suspend fun run(
        input: I,
        ctx: ToolContext,
        dispatchContext: C,
    ): ToolResult<O>
}
