package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject

@Serializable data class LockfileEntryProvenance(
    val providerId: String,
    val modelId: String,
    val modelVersion: String? = null,
    val seed: Long,
    val createdAtEpochMs: Long,
)

@Serializable data class LockfileEntryDriftedNode(
    val nodeId: String,
    val snapshotContentHash: String,
    /** Null when the bound node has since been deleted from the project. */
    val currentContentHash: String? = null,
)

@Serializable data class LockfileEntryClipRef(
    val clipId: String,
    val trackId: String,
    val clipType: String,
)

/**
 * `select=lockfile_entry` — single-row drill-down replacing the
 * deleted `describe_lockfile_entry` tool. Looks up by **either**
 * `inputHash` (forward lookup, the canonical cache key) **or**
 * `assetId` (reverse lookup, "which generation produced this asset?"
 * via [io.talevia.core.domain.lockfile.Lockfile.byAssetId]). Exactly
 * one of the two must be supplied — both-set or neither-set fails
 * loud. Returns full provenance, source-binding snapshot / drift
 * state, baseInputs, clip references on the current timeline.
 */
@Serializable data class LockfileEntryDetailRow(
    val inputHash: String,
    val toolId: String,
    val assetId: String,
    val pinned: Boolean,
    val provenance: LockfileEntryProvenance,
    val sourceBindingIds: List<String> = emptyList(),
    val sourceContentHashes: Map<String, String> = emptyMap(),
    val baseInputs: JsonObject,
    val baseInputsEmpty: Boolean,
    val currentlyStale: Boolean,
    val driftedNodes: List<LockfileEntryDriftedNode> = emptyList(),
    val clipReferences: List<LockfileEntryClipRef> = emptyList(),
    /**
     * The fully-expanded prompt the AIGC tool sent to the provider,
     * after consistency-fold (character_ref / style_bible etc.
     * prepended). Null for tools without a prompt concept (upscale,
     * synthesize_speech) and for pre-cycle-7 legacy entries.
     */
    val resolvedPrompt: String? = null,
    /**
     * Session message id whose tool call produced this entry. Lets
     * the audit path trace "which prompt generated this image?"
     * without grepping session parts. Null for pre-provenance-cycle
     * legacy entries. VISION §5.2.
     */
    val originatingMessageId: String? = null,
)

internal fun runLockfileEntryDetailQuery(
    project: Project,
    input: ProjectQueryTool.Input,
): ToolResult<ProjectQueryTool.Output> {
    val rawHash = input.inputHash
    val rawAssetId = input.assetId
    if (rawHash != null && rawAssetId != null) {
        error(
            "select='${ProjectQueryTool.SELECT_LOCKFILE_ENTRY}' takes exactly one of " +
                "`inputHash` (forward lookup) / `assetId` (reverse lookup); both were " +
                "supplied. Drop the one you don't need.",
        )
    }
    val entry = when {
        rawHash != null -> project.lockfile.findByInputHash(rawHash)
            ?: error(
                "Lockfile entry with inputHash '$rawHash' not found in project ${project.id.value}. " +
                    "Call project_query(select=lockfile_entries) to see valid hashes.",
            )
        rawAssetId != null -> project.lockfile.byAssetId[io.talevia.core.AssetId(rawAssetId)]
            ?: error(
                "No lockfile entry produced asset '$rawAssetId' in project ${project.id.value}. " +
                    "Either the asset was imported (not generated) or pre-dates lockfile recording. " +
                    "Call project_query(select=lockfile_entries) to enumerate AIGC-produced assets.",
            )
        else -> error(
            "select='${ProjectQueryTool.SELECT_LOCKFILE_ENTRY}' requires one of `inputHash` " +
                "(forward lookup, the canonical cache key) or `assetId` (reverse lookup, " +
                "\"which generation produced this asset?\"). Call " +
                "project_query(select=lockfile_entries) to discover valid hashes.",
        )
    }
    val inputHash = entry.inputHash

    val currentHashesById = project.source.nodes.associate { it.id.value to it.contentHash }
    val driftedNodes = entry.sourceContentHashes.mapNotNull { (nodeId, snapshot) ->
        val current = currentHashesById[nodeId.value]
        if (current == null || current != snapshot) {
            LockfileEntryDriftedNode(
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
                    LockfileEntryClipRef(
                        clipId = clip.id.value,
                        trackId = track.id.value,
                        clipType = kind,
                    ),
                )
            }
        }
    }

    val row = LockfileEntryDetailRow(
        inputHash = entry.inputHash,
        toolId = entry.toolId,
        assetId = entry.assetId.value,
        pinned = entry.pinned,
        provenance = LockfileEntryProvenance(
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
        originatingMessageId = entry.originatingMessageId?.value,
    )
    val rows = encodeRows(
        ListSerializer(LockfileEntryDetailRow.serializer()),
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
