package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
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
 * Deep read on a single lockfile entry — the counterpart of
 * [ListLockfileEntriesTool] that returns everything the list trims for
 * breadth.
 *
 * The list tool returns the common fields (inputHash, toolId, assetId,
 * provenance highlights, sourceBinding, pinned) across up to 200 entries.
 * For a single-entry debug / audit view the user typically wants more:
 *
 *  - `baseInputs` — the raw pre-fold tool inputs that produced this
 *    generation. The agent needs this to answer "why did `regenerate_stale_clips`
 *    skip this entry?" (legacy/no-baseInputs is a documented skip reason) or
 *    to re-dispatch the exact tool call manually.
 *  - `sourceContentHashes` — the snapshot of each bound source node's hash
 *    at generation time. Compared against the project's current hashes, this
 *    answers "which source edit caused this clip to go stale?" cleanly,
 *    without having to run the whole staleness lane.
 *  - `currentlyStale` — the derived bool: is any bound source node's current
 *    contentHash different from the snapshotted value? Matches the lane
 *    `find_stale_clips` uses so the two tools agree.
 *  - `clipReferences` — which clips on the timeline currently point at this
 *    entry's `assetId`. Zero means the entry is orphaned w.r.t. the timeline
 *    (candidate for `prune_lockfile`). Non-zero says "this entry is live."
 *
 * Lookup keys on `inputHash` (most recent match, same semantics as
 * [io.talevia.core.domain.lockfile.Lockfile.findByInputHash]). Missing
 * hash fails loudly with a nudge at `list_lockfile_entries` so the agent
 * can discover valid hashes.
 *
 * Read-only; permission `project.read`.
 */
class DescribeLockfileEntryTool(
    private val projects: ProjectStore,
) : Tool<DescribeLockfileEntryTool.Input, DescribeLockfileEntryTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val inputHash: String,
    )

    @Serializable data class Provenance(
        val providerId: String,
        val modelId: String,
        val modelVersion: String?,
        val seed: Long,
        val createdAtEpochMs: Long,
    )

    @Serializable data class DriftedNode(
        val nodeId: String,
        val snapshotContentHash: String,
        /** Null when the bound node has since been deleted from the project. */
        val currentContentHash: String?,
    )

    @Serializable data class ClipRef(
        val clipId: String,
        val trackId: String,
        val clipType: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        val pinned: Boolean,
        val provenance: Provenance,
        val sourceBindingIds: List<String>,
        val sourceContentHashes: Map<String, String>,
        val baseInputs: JsonObject,
        val baseInputsEmpty: Boolean,
        /**
         * True iff any bound source node's current contentHash disagrees with
         * its snapshotted value — the exact staleness signal used by
         * `find_stale_clips`. False when there are no snapshots (legacy
         * entries, pre-sourceContentHashes).
         */
        val currentlyStale: Boolean,
        /** The nodes that drifted, with their snapshotted vs current hashes. */
        val driftedNodes: List<DriftedNode>,
        /** Clips on the project's current timeline that reference `assetId`. */
        val clipReferences: List<ClipRef>,
    )

    override val id: String = "describe_lockfile_entry"
    override val helpText: String =
        "Deep read on one lockfile entry: full provenance, sourceBinding, sourceContentHashes, " +
            "baseInputs, pin state, derived staleness (compared to current source hashes), and " +
            "which clips currently reference its asset. Use for debugging regeneration skips " +
            "(\"why didn't regenerate_stale_clips re-dispatch this?\"), auditing a hero shot, " +
            "or tracing which source edit made a clip stale. Look up by inputHash — get one from " +
            "list_lockfile_entries."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("inputHash") {
                put("type", "string")
                put("description", "Lockfile entry identifier from list_lockfile_entries.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("inputHash"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")
        val entry = project.lockfile.findByInputHash(input.inputHash)
            ?: error(
                "Lockfile entry with inputHash '${input.inputHash}' not found in project " +
                    "${input.projectId}. Call list_lockfile_entries to see valid hashes.",
            )

        val currentHashesById = project.source.nodes.associate { it.id.value to it.contentHash }
        val driftedNodes = entry.sourceContentHashes.mapNotNull { (nodeId, snapshot) ->
            val current = currentHashesById[nodeId.value]
            if (current == null || current != snapshot) {
                DriftedNode(
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
                        ClipRef(
                            clipId = clip.id.value,
                            trackId = track.id.value,
                            clipType = kind,
                        ),
                    )
                }
            }
        }

        val out = Output(
            projectId = pid.value,
            inputHash = entry.inputHash,
            toolId = entry.toolId,
            assetId = entry.assetId.value,
            pinned = entry.pinned,
            provenance = Provenance(
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
            title = "describe lockfile entry ${entry.toolId}/${entry.assetId.value}",
            outputForLlm = "Entry ${input.inputHash} (${entry.toolId}) → asset ${entry.assetId.value}: " +
                "$refNote$staleNote$pinNote$legacyNote.",
            data = out,
        )
    }
}
