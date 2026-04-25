package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.mutateSource
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.SerializationException

/**
 * Portable-envelope import handler — ingests a JSON envelope produced
 * by [ExportSourceNodeTool] (backup / cross-instance / version control)
 * into the target project. Mirror of [executeLiveImport]; same dedup +
 * collision contract, different traversal source (envelope is pre-
 * ordered by the exporter's contract; no topo-walk needed here).
 *
 * Extracted from [ImportSourceNodeTool] so the dispatcher class stays
 * focused on (input shape → handler) routing — see the
 * `debt-split-import-source-node-tool` commit body for the axis
 * rationale. Same handler-extract pattern used for
 * `ClipCreateHandlers` / `ClipMutateHandlers`.
 */
internal suspend fun executeEnvelopeImport(
    projects: ProjectStore,
    input: ImportSourceNodeTool.Input,
    toPid: ProjectId,
): ToolResult<ImportSourceNodeTool.Output> {
    val decoded: SourceNodeEnvelope = try {
        JsonConfig.default.decodeFromString(SourceNodeEnvelope.serializer(), input.envelope!!)
    } catch (e: SerializationException) {
        error("Envelope is not valid JSON for the source-export schema: ${e.message}")
    }
    require(decoded.formatVersion == ExportSourceNodeTool.FORMAT_VERSION) {
        "Envelope formatVersion='${decoded.formatVersion}' is not understood by this importer " +
            "(expected ${ExportSourceNodeTool.FORMAT_VERSION}). Re-export from a compatible Talevia build."
    }
    require(decoded.nodes.isNotEmpty()) {
        "Envelope contains no nodes; nothing to import."
    }
    require(decoded.nodes.any { it.id == decoded.rootNodeId }) {
        "Envelope rootNodeId='${decoded.rootNodeId}' not present in its own nodes list — envelope corrupt."
    }

    val rootRename = input.newNodeId?.takeIf { it.isNotBlank() }
    val imported = mutableListOf<ImportSourceNodeTool.ImportedNode>()
    projects.mutateSource(toPid) { source ->
        var working = source
        val remap = mutableMapOf<SourceNodeId, SourceNodeId>()
        for (exported in decoded.nodes) {
            val originalId = SourceNodeId(exported.id)
            val proposedId = if (exported.id == decoded.rootNodeId && rootRename != null) {
                SourceNodeId(rootRename)
            } else {
                originalId
            }
            val remappedParents = exported.parents.map { parentId ->
                val mapped = remap[SourceNodeId(parentId)] ?: SourceNodeId(parentId)
                SourceRef(mapped)
            }
            val candidate = SourceNode.create(
                id = proposedId,
                kind = exported.kind,
                body = exported.body,
                parents = remappedParents,
            )
            val existingByHash = working.nodes.firstOrNull { it.contentHash == candidate.contentHash }
            val existingAtId = working.nodes.firstOrNull { it.id == proposedId }

            when {
                existingByHash != null -> {
                    remap[originalId] = existingByHash.id
                    imported += ImportSourceNodeTool.ImportedNode(
                        originalId = exported.id,
                        importedId = existingByHash.id.value,
                        kind = exported.kind,
                        skippedDuplicate = true,
                    )
                }
                existingAtId != null -> error(
                    "Target project ${toPid.value} already has a node ${proposedId.value} " +
                        "with a different contentHash (kind=${existingAtId.kind}). Pick a fresh " +
                        "newNodeId or source_node_action(action=remove) first.",
                )
                else -> {
                    working = working.addNode(candidate)
                    remap[originalId] = proposedId
                    imported += ImportSourceNodeTool.ImportedNode(
                        originalId = exported.id,
                        importedId = proposedId.value,
                        kind = exported.kind,
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
        title = "import envelope ${leaf.kind} ${leaf.importedId}",
        outputForLlm = "Ingested ${decoded.rootNodeId} (${decoded.formatVersion}) into " +
            "${toPid.value} as ${leaf.importedId}$parentNote.$dedupNote " +
            "Pass importedId=${leaf.importedId} in consistencyBindingIds for AIGC calls.",
        data = ImportSourceNodeTool.Output(
            toProjectId = toPid.value,
            formatVersion = decoded.formatVersion,
            nodes = imported,
        ),
    )
}
