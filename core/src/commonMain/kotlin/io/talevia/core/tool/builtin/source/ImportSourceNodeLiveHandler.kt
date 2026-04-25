package io.talevia.core.tool.builtin.source

import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.tool.ToolResult

/**
 * Live cross-project import handler — copies a [SourceNode] (and any
 * transitive parents) from another open project in the same Talevia
 * instance into the target. Mirror of [executeEnvelopeImport]; same dedup
 * + collision contract, different traversal source.
 *
 * Extracted from [ImportSourceNodeTool] so the dispatcher class stays
 * focused on (input shape → handler) routing — see the
 * `debt-split-import-source-node-tool` commit body for the axis
 * rationale. Same handler-extract pattern used for
 * `ClipCreateHandlers` / `ClipMutateHandlers`.
 *
 * Behaviour byte-identical to the pre-split inline path: same content-
 * addressed dedup (`existingByHash`), same collision-on-id rejection,
 * same self-import guard, same topological parent-walk via
 * [topoCollectForLiveImport].
 */
internal suspend fun executeLiveImport(
    projects: ProjectStore,
    input: ImportSourceNodeTool.Input,
    toPid: ProjectId,
): ToolResult<ImportSourceNodeTool.Output> {
    val fromProjectIdStr = input.fromProjectId!!
    val fromNodeIdStr = input.fromNodeId!!
    require(fromProjectIdStr != toPid.value) {
        "fromProjectId and toProjectId are the same ($fromProjectIdStr); " +
            "use source_node_action(action=add) with a fresh nodeId for within-project copies."
    }
    val fromPid = ProjectId(fromProjectIdStr)
    val fromProject = projects.get(fromPid)
        ?: error("Source project $fromProjectIdStr not found")

    val leafId = SourceNodeId(fromNodeIdStr)
    val ordered = topoCollectForLiveImport(fromProject.source.nodes.associateBy { it.id }, leafId)
    val renamed = input.newNodeId?.takeIf { it.isNotBlank() }?.let { SourceNodeId(it) }

    val imported = mutableListOf<ImportSourceNodeTool.ImportedNode>()
    projects.mutateSource(toPid) { source ->
        var working = source
        val remap = mutableMapOf<SourceNodeId, SourceNodeId>()
        for (node in ordered) {
            val proposedId = if (node.id == leafId && renamed != null) renamed else node.id
            val remappedParents = node.parents.map { ref ->
                SourceRef(remap[ref.nodeId] ?: ref.nodeId)
            }
            val effectiveHash = if (remappedParents == node.parents) {
                node.contentHash
            } else {
                SourceNode.create(node.id, node.kind, node.body, remappedParents).contentHash
            }
            val existingByHash = working.nodes.firstOrNull { it.contentHash == effectiveHash }
            val existingAtId = working.nodes.firstOrNull { it.id == proposedId }

            when {
                existingByHash != null -> {
                    remap[node.id] = existingByHash.id
                    imported += ImportSourceNodeTool.ImportedNode(
                        originalId = node.id.value,
                        importedId = existingByHash.id.value,
                        kind = node.kind,
                        skippedDuplicate = true,
                    )
                }
                existingAtId != null -> error(
                    "Target project ${toPid.value} already has a node ${proposedId.value} " +
                        "with a different contentHash (kind=${existingAtId.kind}). Pick a fresh " +
                        "newNodeId or source_node_action(action=remove) first.",
                )
                else -> {
                    working = working.addNode(
                        SourceNode.create(
                            id = proposedId,
                            kind = node.kind,
                            body = node.body,
                            parents = remappedParents,
                        ),
                    )
                    remap[node.id] = proposedId
                    imported += ImportSourceNodeTool.ImportedNode(
                        originalId = node.id.value,
                        importedId = proposedId.value,
                        kind = node.kind,
                        skippedDuplicate = false,
                    )
                }
            }
        }
        working
    }

    val leaf = imported.last()
    val parentNote = if (imported.size > 1) " (with ${imported.size - 1} parent node(s))" else ""
    val dedupNote = imported.count { it.skippedDuplicate }.takeIf { it > 0 }
        ?.let { " — $it already-present node(s) reused" }
        .orEmpty()
    return ToolResult(
        title = "import ${leaf.kind} ${leaf.importedId}",
        outputForLlm = "Imported $fromNodeIdStr from $fromProjectIdStr into " +
            "${toPid.value} as ${leaf.importedId}$parentNote.$dedupNote " +
            "Pass importedId=${leaf.importedId} in consistencyBindingIds for AIGC calls on the target project.",
        data = ImportSourceNodeTool.Output(
            fromProjectId = fromProjectIdStr,
            toProjectId = toPid.value,
            nodes = imported,
        ),
    )
}

/**
 * Collect [leafId] and every transitive parent in topological (parents-
 * first) order. Throws if the leaf or any referenced parent is missing
 * — incomplete imports would leave dangling [SourceRef]s on the target
 * project.
 *
 * Internal to the live-import path because the envelope path comes
 * pre-ordered by the exporter's contract. ExportSourceNodeTool keeps a
 * private copy of the same traversal — they're independent because the
 * export side has slightly different "missing parent" semantics.
 */
internal fun topoCollectForLiveImport(
    byId: Map<SourceNodeId, SourceNode>,
    leafId: SourceNodeId,
): List<SourceNode> {
    val visited = mutableSetOf<SourceNodeId>()
    val result = mutableListOf<SourceNode>()
    fun visit(id: SourceNodeId) {
        if (id in visited) return
        visited += id
        val node = byId[id] ?: error(
            "Source node ${id.value} not found on the source project (referenced from import chain).",
        )
        for (parent in node.parents) visit(parent.nodeId)
        result += node
    }
    visit(leafId)
    return result
}
