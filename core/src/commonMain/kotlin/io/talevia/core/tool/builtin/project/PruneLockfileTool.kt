package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
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
 * Drop [io.talevia.core.domain.lockfile.LockfileEntry] rows whose [assetId] is no
 * longer present in [io.talevia.core.domain.Project.assets] — the sweep half of
 * the lockfile's append-only ledger (VISION §3.1).
 *
 * The lockfile grows every time an AIGC tool succeeds. When assets leave the
 * project — via [RemoveAssetTool], provider swap, or a manual catalog edit — the
 * rows that produced them become orphans:
 *   1. wasted space in the serialized project blob,
 *   2. noisy [ListLockfileEntriesTool] output (the agent sees "generations" that
 *      no longer have a persisted artifact),
 *   3. false-positive cache hits if a fresh generation happens to produce the
 *      same `inputHash` — [io.talevia.core.domain.lockfile.Lockfile.findByInputHash]
 *      returns the orphan row and callers try to reuse an asset that doesn't exist.
 *
 * Pairs naturally with `list_assets(onlyUnused=true) → remove_asset → prune_lockfile`
 * as the end-of-project cleanup flow.
 *
 * Permission is [PermissionSpec.fixed] `"project.write"` in both modes. Dry-run is
 * a convenience preview, not a distinct security posture — splitting into
 * read/write tiers would only duplicate prompts for one "would-this-delete-anything?"
 * flavor and the payloads are identical.
 */
class PruneLockfileTool(
    private val projects: ProjectStore,
) : Tool<PruneLockfileTool.Input, PruneLockfileTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** When true, report the diff without mutating the store. Defaults to false. */
        val dryRun: Boolean = false,
    )

    @Serializable data class PrunedSummary(
        val inputHash: String,
        val toolId: String,
        val assetId: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val totalEntries: Int,
        val prunedCount: Int,
        val keptCount: Int,
        val prunedEntries: List<PrunedSummary>,
        val dryRun: Boolean,
    )

    override val id: String = "prune_lockfile"
    override val helpText: String =
        "Drop lockfile entries whose assetId is no longer in project.assets. The lockfile grows " +
            "append-only on every AIGC generation; when assets leave the project the rows become " +
            "orphans (wasted space, noisy list_lockfile_entries, false-positive cache hits). " +
            "Pass dryRun=true to preview which rows would drop without mutating. Safe on empty " +
            "lockfiles and idempotent across calls."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("dryRun") {
                put("type", "boolean")
                put("description", "Report which entries would prune without mutating. Default false.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("project ${input.projectId} not found")

        val liveAssetIds: Set<AssetId> = project.assets.asSequence().map { it.id }.toSet()
        val entries = project.lockfile.entries
        val kept = entries.filter { it.assetId in liveAssetIds }
        val pruned = entries.filter { it.assetId !in liveAssetIds }

        if (pruned.isNotEmpty() && !input.dryRun) {
            projects.mutate(pid) { p ->
                p.copy(lockfile = p.lockfile.copy(entries = p.lockfile.entries.filter { it.assetId in liveAssetIds }))
            }
        }

        val prunedSummaries = pruned.map {
            PrunedSummary(inputHash = it.inputHash, toolId = it.toolId, assetId = it.assetId.value)
        }

        val out = Output(
            projectId = pid.value,
            totalEntries = entries.size,
            prunedCount = pruned.size,
            keptCount = kept.size,
            prunedEntries = prunedSummaries,
            dryRun = input.dryRun,
        )

        val verb = if (input.dryRun) "Would drop" else "Dropped"
        val summary = when {
            entries.isEmpty() ->
                "Lockfile on project ${pid.value} is empty. Nothing to prune."
            pruned.isEmpty() ->
                "All ${entries.size} lockfile entries on project ${pid.value} still reference live assets. Nothing to prune."
            else ->
                "$verb ${pruned.size} of ${entries.size} lockfile entries on project ${pid.value} " +
                    "(orphan assetIds: " +
                    prunedSummaries.take(5).joinToString(", ") { "${it.toolId}/${it.assetId}" } +
                    if (prunedSummaries.size > 5) ", …)." else ")."
        }
        return ToolResult(
            title = if (input.dryRun) "prune lockfile (dry run)" else "prune lockfile",
            outputForLlm = summary,
            data = out,
        )
    }
}
