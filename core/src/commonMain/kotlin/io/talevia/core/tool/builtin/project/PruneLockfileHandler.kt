package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.tool.ToolResult

/**
 * `action="prune-lockfile"` handler extracted from `ProjectMaintenanceActionTool`
 * (`debt-split-project-maintenance-action-tool`). Orphan sweep: drops lockfile
 * rows whose `assetId` is no longer in `project.assets`. No policy knobs — the
 * orphan classification is pure.
 *
 * Lives as a top-level internal function in the same package as the tool so it
 * can reference `ProjectMaintenanceActionTool.Input / Output /
 * PrunedOrphanLockfileRow` by short name without importing the enclosing class
 * everywhere. The split's axis is "new maintenance action = new handler file"
 * — the main tool becomes a pure dispatcher + shape definitions, and future
 * actions drop in as additional handler files instead of puffing up the
 * dispatcher.
 */
internal suspend fun executePruneLockfile(
    projects: ProjectStore,
    pid: ProjectId,
    input: ProjectMaintenanceActionTool.Input,
): ToolResult<ProjectMaintenanceActionTool.Output> {
    val project = projects.get(pid) ?: error("project ${pid.value} not found")
    val liveAssetIds: Set<AssetId> = project.assets.asSequence().map { it.id }.toSet()
    val entries = project.lockfile.entries
    val kept = entries.filter { it.assetId in liveAssetIds }
    val pruned = entries.filter { it.assetId !in liveAssetIds }

    if (pruned.isNotEmpty() && !input.dryRun) {
        projects.mutate(pid) { p ->
            p.copy(lockfile = p.lockfile.filterEntries { it.assetId in liveAssetIds })
        }
    }

    val prunedRows = pruned.map {
        ProjectMaintenanceActionTool.PrunedOrphanLockfileRow(
            inputHash = it.inputHash,
            toolId = it.toolId,
            assetId = it.assetId.value,
        )
    }

    val verb = if (input.dryRun) "Would drop" else "Dropped"
    val summary = when {
        entries.isEmpty() ->
            "Lockfile on project ${pid.value} is empty. Nothing to prune."
        pruned.isEmpty() ->
            "All ${entries.size} lockfile entries on project ${pid.value} still reference live assets. Nothing to prune."
        else ->
            "$verb ${pruned.size} of ${entries.size} lockfile entries on project ${pid.value} " +
                "(orphan assetIds: " +
                prunedRows.take(5).joinToString(", ") { "${it.toolId}/${it.assetId}" } +
                if (prunedRows.size > 5) ", …)." else ")."
    }

    return ToolResult(
        title = if (input.dryRun) "prune lockfile (dry run)" else "prune lockfile",
        outputForLlm = summary,
        data = ProjectMaintenanceActionTool.Output(
            projectId = pid.value,
            action = "prune-lockfile",
            totalEntries = entries.size,
            prunedCount = pruned.size,
            keptCount = kept.size,
            dryRun = input.dryRun,
            prunedOrphanLockfileRows = prunedRows,
        ),
    )
}
