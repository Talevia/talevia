package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.autoRegenHint
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.tool.ToolResult

/**
 * `source_node_action(action="set_parents")` handler — replace a source
 * node's [io.talevia.core.domain.source.SourceNode.parents] list
 * wholesale. Behaviour preserved verbatim from the legacy
 * [io.talevia.core.tool.builtin.source.SetSourceNodeParentsTool]:
 *
 * - `parentIds` is a full replacement; empty list clears all parents.
 * - Cycles are rejected — walking transitively, if any proposed
 *   parent's ancestor set contains the node being edited, fail loud.
 * - Dangling ids are rejected via the shared [resolveParentRefs]
 *   helper that the `define_*` tools use.
 * - Bumps `contentHash` via `replaceNode`; stale-propagation picks it
 *   up the usual way.
 */
internal suspend fun executeSourceSetParents(
    projects: ProjectStore,
    input: SourceNodeActionTool.Input,
): ToolResult<SourceNodeActionTool.Output> {
    val rawNodeId = input.nodeId?.takeIf { it.isNotBlank() }
        ?: error("action=set_parents requires `nodeId`")
    val parentIdsInput = input.parentIds
        ?: error("action=set_parents requires `parentIds` (empty list to clear).")
    val pid = ProjectId(input.projectId)
    val nodeId = SourceNodeId(rawNodeId)

    var previous: List<String> = emptyList()
    var next: List<String> = emptyList()

    val updated = projects.mutateSource(pid) { source ->
        val existing = source.byId[nodeId]
            ?: error(
                "node ${nodeId.value} not found in project ${input.projectId}; " +
                    "call source_query(select=nodes) to find the id.",
            )
        previous = existing.parents.map { it.nodeId.value }
        val refs = resolveParentRefs(parentIdsInput, source, nodeId)
        rejectIfSetParentsCycle(source, nodeId, refs.map { it.nodeId })
        next = refs.map { it.nodeId.value }
        source.replaceNode(nodeId) { node -> node.copy(parents = refs) }
    }

    val hint = updated.autoRegenHint()
    val regenNudge = if (hint != null) {
        " autoRegenHint: ${hint.staleClipCount} stale clip(s) — suggested next: ${hint.suggestedTool}."
    } else {
        ""
    }
    return ToolResult(
        title = "set source parents for ${nodeId.value}",
        outputForLlm = "Replaced parents of ${nodeId.value}: was $previous, now $next. " +
            "contentHash bumped — run find_stale_clips to see downstream impact.$regenNudge",
        data = SourceNodeActionTool.Output(
            projectId = input.projectId,
            action = "set_parents",
            parentsSet = listOf(
                SourceNodeActionTool.SetParentsResult(
                    nodeId = nodeId.value,
                    previousParentIds = previous,
                    newParentIds = next,
                ),
            ),
            autoRegenHint = hint,
        ),
    )
}

/**
 * Fail loud if any [proposedParents] transitively references [self].
 *
 * Walks the Source DAG upward from each proposed parent; if [self]
 * appears in that ancestor set, the edit would create a cycle. The walk
 * is guarded against existing cycles in the graph via a visited-set, so
 * a pre-existing loop won't put us in an infinite loop. Lifted verbatim
 * from the legacy `SetSourceNodeParentsTool.rejectIfCycle` (renamed to
 * avoid colliding with the existing source-DAG cycle helpers in
 * `domain.source`).
 */
private fun rejectIfSetParentsCycle(
    source: Source,
    self: SourceNodeId,
    proposedParents: List<SourceNodeId>,
) {
    if (proposedParents.isEmpty()) return
    val byId = source.byId
    val visited = mutableSetOf<SourceNodeId>()
    val stack: ArrayDeque<SourceNodeId> = ArrayDeque(proposedParents)
    while (stack.isNotEmpty()) {
        val current = stack.removeFirst()
        if (!visited.add(current)) continue
        if (current == self) {
            error(
                "setting parents ${proposedParents.map { it.value }} on ${self.value} would " +
                    "introduce a cycle — ${current.value} transitively references ${self.value}.",
            )
        }
        val node = byId[current] ?: continue
        for (ancestor in node.parents) stack.addLast(ancestor.nodeId)
    }
}
