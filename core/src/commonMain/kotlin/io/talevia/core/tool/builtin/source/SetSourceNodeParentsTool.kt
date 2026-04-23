package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.AutoRegenHint
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.autoRegenHint
import io.talevia.core.domain.source.Source
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.domain.source.replaceNode
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
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
 * Replace a [io.talevia.core.domain.source.SourceNode.parents] list wholesale —
 * a genre-agnostic DAG edit that the `update_*` consistency tools already do
 * for their own kinds but that has no equivalent for hand-authored or imported
 * nodes (narrative.shot, vlog.raw_footage, etc.).
 *
 * VISION §5.5 calls out **cross-shot consistency via the DAG** as the hard
 * problem: a scene referencing a character_ref, a shot inheriting a
 * style_bible, a cohort of shots sharing a world node. The only way today to
 * retroactively bind a scene's parent to a freshly-defined character was to
 * remove + recreate the scene — losing the body, or working around via
 * `import_source_node` from a copy. This tool closes that gap.
 *
 * **Semantics.**
 *  - `parentIds` is a **full replacement**. Empty list clears all parents.
 *    Partial editing (add-one, remove-one) is up to the caller — pass
 *    `source_query(select=nodes)` output, tweak, write back.
 *  - Ids are resolved via the same `resolveParentRefs` helper the
 *    `define_*` tools use: no self-reference, every id exists, duplicates
 *    deduped with insertion-order preserved.
 *  - **Cycles rejected.** Walking transitively, if any proposed parent's
 *    ancestor set contains the node being edited, fail loud. A cycle in
 *    the Source DAG would break stale-propagation and `source_query(select=dag_summary)`
 *    rendering.
 *  - Bumps contentHash via `replaceNode` — stale-propagation (VISION §3.2)
 *    picks it up the usual way.
 *  - No-op updates (new parents == current parents after dedup) still
 *    return a hash-bump for predictability — agents that reset to the
 *    same list see the same `contentHash` back because the hash is
 *    computed from the body, but `revision` bumps. That's intentional:
 *    the operation was requested, the node was touched, snapshots should
 *    reflect it.
 *
 * **Permission.** `source.write` — same tier as the rest of the source-write family.
 */
class SetSourceNodeParentsTool(
    private val projects: ProjectStore,
) : Tool<SetSourceNodeParentsTool.Input, SetSourceNodeParentsTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val nodeId: String,
        /** Full replacement. Empty list clears all parents. */
        val parentIds: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val nodeId: String,
        val previousParentIds: List<String>,
        val newParentIds: List<String>,
        /**
         * VISION §5.5 auto-regen hint: non-null when the project has any
         * stale clips after this parent-list rewrite. See [AutoRegenHint].
         */
        val autoRegenHint: AutoRegenHint? = null,
    )

    override val id: String = "set_source_node_parents"
    override val helpText: String =
        "Replace the parents of a source node wholesale. Works on ANY node kind (narrative.shot, " +
            "vlog.raw_footage, core.consistency.*, ...). Use empty list to clear all parents. " +
            "Cycles and dangling ids are rejected loudly. Bumps contentHash so downstream clips " +
            "go stale — run find_stale_clips after editing."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("source.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("nodeId") {
                put("type", "string")
                put("description", "Id of the node whose parents are being replaced.")
            }
            putJsonObject("parentIds") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put(
                    "description",
                    "New parent id list (full replacement). Empty list clears all parents.",
                )
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("projectId"),
                    JsonPrimitive("nodeId"),
                    JsonPrimitive("parentIds"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val nodeId = SourceNodeId(input.nodeId)

        var previous: List<String> = emptyList()
        var next: List<String> = emptyList()

        val updated = projects.mutateSource(pid) { source ->
            val existing = source.byId[nodeId]
                ?: error(
                    "node ${nodeId.value} not found in project ${input.projectId}; " +
                        "call source_query(select=nodes) to find the id.",
                )
            previous = existing.parents.map { it.nodeId.value }
            val refs = resolveParentRefs(input.parentIds, source, nodeId)
            rejectIfCycle(source, nodeId, refs.map { it.nodeId })
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
            title = "set source parents for ${input.nodeId}",
            outputForLlm = "Replaced parents of ${input.nodeId}: was ${previous}, now ${next}. " +
                "contentHash bumped — run find_stale_clips to see downstream impact.$regenNudge",
            data = Output(
                projectId = input.projectId,
                nodeId = input.nodeId,
                previousParentIds = previous,
                newParentIds = next,
                autoRegenHint = hint,
            ),
        )
    }

    /**
     * Fail loud if any [proposedParents] transitively references [self].
     *
     * Walks the Source DAG upward from each proposed parent; if [self] appears
     * in that ancestor set, the edit would create a cycle. The walk is guarded
     * against existing cycles in the graph via a visited-set, so a
     * pre-existing loop (shouldn't exist — we reject on write — but belt and
     * braces) won't put us in an infinite loop.
     */
    private fun rejectIfCycle(
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
}
