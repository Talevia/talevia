package io.talevia.core.tool.action

import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * A single-`action` plugin for an action-dispatched tool — the action-side
 * mirror of [io.talevia.core.tool.query.QuerySelect].
 *
 * The 8 `*ActionTool` dispatchers (FilterActionTool, ClipActionTool,
 * TimelineActionTool, SessionActionTool, ProjectLifecycleActionTool,
 * SourceNodeActionTool, ClipActionTool, ProjectMaintenanceActionTool,
 * ProjectPinActionTool, ProjectSnapshotActionTool) all encode their verbs
 * inline as `when (input.action) { "x" -> executeX(...) ; ... }` arm-trees.
 * Adding a verb edits the central dispatcher in 2-4 places: the `when`
 * arm, the schema enum, sometimes a new optional Input field, sometimes a
 * helpText fragment.
 *
 * This interface gives each verb its own object that bundles the two
 * runtime concerns — `id` + `run` — so adding a verb means dropping a new
 * object into a registry instead of touching the central dispatcher's
 * switch arms.
 *
 * Action verbs do NOT have a `rowSerializer` (vs [QuerySelect]) because
 * actions return a single tool-shaped Output, not a list of tabular rows.
 *
 * `I` is the dispatcher-wide Input type (the same `Input` that the tool's
 * `inputSerializer` decodes); each verb reads only the fields relevant to
 * it. `O` is the dispatcher's Output type — the verb's `run` produces a
 * complete `ToolResult<O>` so it can carry its own `outputForLlm` summary
 * plus per-verb metadata (`appliedClipIds` / `removed` / `lutResults` /
 * etc.). `C` is the dispatcher-specific context object built once per
 * `execute()` call (e.g. the `ProjectStore` + resolved `ProjectId` for
 * filter / clip / track verbs; the `SessionStore` + resolved `Session`
 * for session verbs) and threaded into every verb's `run` so per-verb
 * handlers don't re-resolve.
 *
 * Cycle 161 introduces the interface and migrates [FilterActionTool]'s
 * 3 verbs as proof-of-concept; the migration recipe is documented in the
 * commit body so later cycles can take ClipActionTool / TimelineActionTool /
 * SessionActionTool / etc. one at a time without re-deriving the shape.
 *
 * Note: input-validation guards that need to see ALL verbs at once
 * (e.g. mutually-exclusive selectors that one verb requires and another
 * forbids) stay per-dispatcher because they're a different concern. The
 * dispatch / per-verb handler split is what the §3a #12 architecture-tax
 * trigger pinpointed.
 */
interface ActionVerb<I : Any, O : Any, C : Any> {
    /** Lowercase, canonical action id (e.g. `"apply"`, `"remove"`, `"apply_lut"`). */
    val id: String

    /**
     * Execute the verb. [input] is the dispatcher-wide Input (read only
     * the fields relevant to this verb); [ctx] is the standard tool
     * context; [dispatchContext] is the dispatcher-specific resolved-
     * context object (typically the matching store plus a resolved
     * `ProjectId` / `Project` / `Session`).
     */
    suspend fun run(
        input: I,
        ctx: ToolContext,
        dispatchContext: C,
    ): ToolResult<O>
}
