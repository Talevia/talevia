package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=lockfile_entry` — single-row drill-down replacing the deleted
 * `describe_lockfile_entry` tool. Looks up by `inputHash` (most-recent
 * match, same semantics as `Lockfile.findByInputHash`). Returns full
 * provenance + source-binding snapshot + drifted-nodes list + baseInputs
 * + live clip references on the current timeline.
 *
 * Consolidated under `project_query` per the
 * `debt-consolidate-project-describe-queries` backlog bullet.
 */
internal fun runLockfileEntryDetailQuery(
    project: Project,
    input: ProjectQueryTool.Input,
): ToolResult<ProjectQueryTool.Output> {
    val inputHash = input.inputHash
        ?: error(
            "select='${ProjectQueryTool.SELECT_LOCKFILE_ENTRY}' requires inputHash. Call " +
                "project_query(select=lockfile_entries) to discover valid hashes.",
        )
    val entry = project.lockfile.findByInputHash(inputHash)
        ?: error(
            "Lockfile entry with inputHash '$inputHash' not found in project ${project.id.value}. " +
                "Call project_query(select=lockfile_entries) to see valid hashes.",
        )

    val currentHashesById = project.source.nodes.associate { it.id.value to it.contentHash }
    val driftedNodes = entry.sourceContentHashes.mapNotNull { (nodeId, snapshot) ->
        val current = currentHashesById[nodeId.value]
        if (current == null || current != snapshot) {
            ProjectQueryTool.LockfileEntryDriftedNode(
                nodeId = nodeId.value,
                snapshotContentHash = snapshot,
                currentContentHash = current,
            )
        } else {
            null
        }
    }
    val stale = driftedNodes.isNotEmpty()

    val clipRefs = buildList {
        for (track in project.timeline.tracks) {
            for (clip in track.clips) {
                val (clipAssetId, kind) = when (clip) {
                    is Clip.Video -> clip.assetId to "video"
                    is Clip.Audio -> clip.assetId to "audio"
                    is Clip.Text -> null to "text"
                }
                if (clipAssetId != entry.assetId) continue
                add(
                    ProjectQueryTool.LockfileEntryClipRef(
                        clipId = clip.id.value,
                        trackId = track.id.value,
                        clipType = kind,
                    ),
                )
            }
        }
    }

    val row = ProjectQueryTool.LockfileEntryDetailRow(
        inputHash = entry.inputHash,
        toolId = entry.toolId,
        assetId = entry.assetId.value,
        pinned = entry.pinned,
        provenance = ProjectQueryTool.LockfileEntryProvenance(
            providerId = entry.provenance.providerId,
            modelId = entry.provenance.modelId,
            modelVersion = entry.provenance.modelVersion,
            seed = entry.provenance.seed,
            createdAtEpochMs = entry.provenance.createdAtEpochMs,
        ),
        sourceBindingIds = entry.sourceBinding.map { it.value }.sorted(),
        sourceContentHashes = entry.sourceContentHashes.mapKeys { it.key.value },
        baseInputs = entry.baseInputs,
        baseInputsEmpty = entry.baseInputs.isEmpty(),
        currentlyStale = stale,
        driftedNodes = driftedNodes,
        clipReferences = clipRefs,
        resolvedPrompt = entry.resolvedPrompt,
    )
    val rows = encodeRows(
        ListSerializer(ProjectQueryTool.LockfileEntryDetailRow.serializer()),
        listOf(row),
    )

    val refNote = when (clipRefs.size) {
        0 -> "no clip references"
        1 -> "1 clip references its asset"
        else -> "${clipRefs.size} clips reference its asset"
    }
    val staleNote = if (stale) " — currently stale (${driftedNodes.size} drifted)" else ""
    val pinNote = if (entry.pinned) " — pinned" else ""
    val legacyNote = if (entry.baseInputs.isEmpty()) " — legacy entry (no baseInputs)" else ""
    return ToolResult(
        title = "project_query lockfile_entry ${entry.toolId}/${entry.assetId.value}",
        outputForLlm = "Entry $inputHash (${entry.toolId}) → asset ${entry.assetId.value}: " +
            "$refNote$staleNote$pinNote$legacyNote.",
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_LOCKFILE_ENTRY,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}
