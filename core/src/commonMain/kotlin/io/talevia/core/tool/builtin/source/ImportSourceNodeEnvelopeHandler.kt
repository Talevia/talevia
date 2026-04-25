package io.talevia.core.tool.builtin.source

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.SourceNodeId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.source.SourceNode
import io.talevia.core.domain.source.SourceRef
import io.talevia.core.domain.source.addNode
import io.talevia.core.domain.source.mutateSource
import kotlinx.serialization.SerializationException

/**
 * Portable-envelope import handler — ingests a JSON envelope produced
 * by [ExportSourceNodeTool] (backup / cross-instance / version control)
 * into the target project. Mirror of [executeLiveImport]; same dedup +
 * collision contract, different traversal source (envelope is pre-
 * ordered by the exporter's contract; no topo-walk needed here).
 *
 * Cycle 136: takes [SourceNodeImportRequest] / returns
 * [SourceNodeImportOutcome] so the standalone `ImportSourceNodeTool`
 * could be folded into `source_node_action(action="import")`.
 */
internal suspend fun executeEnvelopeImport(
    projects: ProjectStore,
    request: SourceNodeImportRequest,
    toPid: ProjectId,
): SourceNodeImportOutcome {
    val decoded: SourceNodeEnvelope = try {
        JsonConfig.default.decodeFromString(SourceNodeEnvelope.serializer(), request.envelope!!)
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

    val rootRename = request.newNodeId?.takeIf { it.isNotBlank() }
    val imported = mutableListOf<SourceNodeImportedNode>()
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
                    imported += SourceNodeImportedNode(
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
                    imported += SourceNodeImportedNode(
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
    return SourceNodeImportOutcome(
        fromProjectId = null,
        toProjectId = toPid.value,
        formatVersion = decoded.formatVersion,
        nodes = imported,
        outputForLlm = "Ingested ${decoded.rootNodeId} (${decoded.formatVersion}) into " +
            "${toPid.value} as ${leaf.importedId}$parentNote.$dedupNote " +
            "Pass importedId=${leaf.importedId} in consistencyBindingIds for AIGC calls.",
        title = "import envelope ${leaf.kind} ${leaf.importedId}",
    )
}
