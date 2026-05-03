package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock

/**
 * Shared by the two time-windowed GC handlers
 * (`GcLockfileHandler` + `GcRenderCacheHandler`). The `maxAgeDays` policy
 * converts to a wall-clock cutoff via `now - maxAgeDays * MAINTENANCE_MILLIS_PER_DAY`.
 */
internal const val MAINTENANCE_MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L

/**
 * `action="gc-lockfile"` handler extracted from `ProjectMaintenanceActionTool`
 * (`debt-split-project-maintenance-action-tool`). Policy-based GC of the
 * lockfile: `maxAgeDays` + `keepLatestPerTool` (both optional, AND-composed),
 * with always-on `pinGuard` (pinned rows survive) and
 * `preserveLiveAssets=true`-gated `liveAssetGuard` (rows whose asset is still
 * referenced survive). Both-null is a no-op that points the caller at
 * `prune-lockfile` for an orphan sweep.
 */
internal suspend fun executeGcLockfile(
    projects: ProjectStore,
    pid: ProjectId,
    input: ProjectMaintenanceActionTool.Input,
    clock: Clock,
): ToolResult<ProjectMaintenanceActionTool.Output> {
    input.maxAgeDays?.let { require(it >= 0) { "maxAgeDays must be >= 0 (was $it)" } }
    input.keepLatestPerTool?.let { require(it >= 0) { "keepLatestPerTool must be >= 0 (was $it)" } }

    val project = projects.get(pid) ?: error("project ${pid.value} not found")
    val entries = project.lockfile.entries
    val liveAssetIds: Set<AssetId> = project.assets.asSequence().map { it.id }.toSet()

    val ageEnabled = input.maxAgeDays != null
    val countEnabled = input.keepLatestPerTool != null

    if (!ageEnabled && !countEnabled) {
        return ToolResult(
            title = if (input.dryRun) "gc lockfile (dry run)" else "gc lockfile",
            outputForLlm = "No GC policy set on project ${pid.value} (both maxAgeDays and " +
                "keepLatestPerTool are null). Nothing to GC. Call " +
                "project_action(kind=\"maintenance\", args={action=\"prune-lockfile\"}) for an orphan sweep, or pass a policy argument.",
            data = ProjectMaintenanceActionTool.Output(
                projectId = pid.value,
                action = "gc-lockfile",
                totalEntries = entries.size,
                prunedCount = 0,
                keptCount = entries.size,
                dryRun = input.dryRun,
            ),
        )
    }

    val nowMs = clock.now().toEpochMilliseconds()
    val ageCutoffMs: Long? = input.maxAgeDays?.let { nowMs - it.toLong() * MAINTENANCE_MILLIS_PER_DAY }

    val droppedByAge: Set<LockfileEntry> = if (ageCutoffMs != null) {
        entries.filter { it.provenance.createdAtEpochMs < ageCutoffMs }.toSet()
    } else {
        emptySet()
    }

    val droppedByCount: Set<LockfileEntry> = if (input.keepLatestPerTool != null) {
        val keep = input.keepLatestPerTool
        val byTool = entries.withIndex().groupBy { (_, e) -> e.toolId }
        val toDrop = mutableSetOf<LockfileEntry>()
        for ((_, indexed) in byTool) {
            val sorted = indexed.sortedWith(
                compareByDescending<IndexedValue<LockfileEntry>> { it.value.provenance.createdAtEpochMs }
                    .thenByDescending { it.index },
            )
            sorted.drop(keep).forEach { toDrop.add(it.value) }
        }
        toDrop
    } else {
        emptySet()
    }

    val selectedForDrop: List<Pair<LockfileEntry, String>> = entries.mapNotNull { e ->
        val byAge = e in droppedByAge
        val byCount = e in droppedByCount
        when {
            byAge && byCount -> e to "age+count"
            byAge -> e to "age"
            byCount -> e to "count"
            else -> null
        }
    }

    val (pinRescued, afterPinGuard) = selectedForDrop.partition { (e, _) -> e.pinned }
    val (liveRescued, pruned) = if (input.preserveLiveAssets) {
        afterPinGuard.partition { (e, _) -> e.assetId in liveAssetIds }
    } else {
        emptyList<Pair<LockfileEntry, String>>() to afterPinGuard
    }

    val prunedSet: Set<LockfileEntry> = pruned.map { it.first }.toSet()
    if (prunedSet.isNotEmpty() && !input.dryRun) {
        projects.mutate(pid) { p ->
            p.copy(lockfile = p.lockfile.filterEntries { it !in prunedSet })
        }
    }

    val prunedRows = pruned.map { (e, reason) ->
        ProjectMaintenanceActionTool.PrunedGcLockfileRow(
            inputHash = e.inputHash,
            toolId = e.toolId,
            assetId = e.assetId.value,
            createdAtEpochMs = e.provenance.createdAtEpochMs,
            reason = reason,
        )
    }

    val policiesApplied = buildList {
        if (ageEnabled) add("age")
        if (countEnabled) add("count")
        add("pinGuard")
        if (input.preserveLiveAssets) add("liveAssetGuard")
    }

    val verb = if (input.dryRun) "Would drop" else "Dropped"
    val pinNote = if (pinRescued.isNotEmpty()) {
        " ${pinRescued.size} row(s) preserved by pinGuard."
    } else {
        ""
    }
    val rescueNote = if (liveRescued.isNotEmpty()) {
        " ${liveRescued.size} additional row(s) preserved by liveAssetGuard."
    } else {
        ""
    }
    val summary = when {
        entries.isEmpty() ->
            "Lockfile on project ${pid.value} is empty. Nothing to GC."
        pruned.isEmpty() ->
            "All ${entries.size} lockfile entries on project ${pid.value} pass the GC policy " +
                "(${policiesApplied.joinToString("+")}). Nothing to drop.$pinNote$rescueNote"
        else ->
            "$verb ${pruned.size} of ${entries.size} lockfile entries on project ${pid.value} " +
                "(policy: ${policiesApplied.joinToString("+")}; reasons: " +
                prunedRows.take(5).joinToString(", ") { "${it.toolId}/${it.assetId}(${it.reason})" } +
                if (prunedRows.size > 5) ", …).$pinNote$rescueNote" else ").$pinNote$rescueNote"
    }

    return ToolResult(
        title = if (input.dryRun) "gc lockfile (dry run)" else "gc lockfile",
        outputForLlm = summary,
        data = ProjectMaintenanceActionTool.Output(
            projectId = pid.value,
            action = "gc-lockfile",
            totalEntries = entries.size,
            prunedCount = pruned.size,
            keptCount = entries.size - pruned.size,
            dryRun = input.dryRun,
            prunedGcLockfileRows = prunedRows,
            keptByLiveAssetGuardCount = liveRescued.size,
            keptByPinCount = pinRescued.size,
            policiesApplied = policiesApplied,
        ),
    )
}
