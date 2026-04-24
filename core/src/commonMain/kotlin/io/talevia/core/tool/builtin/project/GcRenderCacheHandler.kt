package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.render.ClipRenderCacheEntry
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock

/**
 * `action="gc-render-cache"` handler extracted from `ProjectMaintenanceActionTool`
 * (`debt-split-project-maintenance-action-tool`). Policy-based GC of the
 * per-clip mezzanine cache: `maxAgeDays` + `keepLastN` (both optional,
 * AND-composed). Each pruned row also deletes its `.mp4` on disk via
 * `VideoEngine.deleteMezzanine`. No `preserveLiveAssets` equivalent — the
 * mezzanine cache is pure-cache (pruned = next-export cache miss, never
 * broken state). Both-null is a no-op that points the caller at passing a
 * policy argument.
 */
internal suspend fun executeGcRenderCache(
    projects: ProjectStore,
    engine: VideoEngine,
    pid: ProjectId,
    input: ProjectMaintenanceActionTool.Input,
    clock: Clock,
): ToolResult<ProjectMaintenanceActionTool.Output> {
    input.maxAgeDays?.let { require(it >= 0) { "maxAgeDays must be >= 0 (was $it)" } }
    input.keepLastN?.let { require(it >= 0) { "keepLastN must be >= 0 (was $it)" } }

    val project = projects.get(pid) ?: error("project ${pid.value} not found")
    val entries = project.clipRenderCache.entries
    val ageEnabled = input.maxAgeDays != null
    val countEnabled = input.keepLastN != null

    if (!ageEnabled && !countEnabled) {
        return ToolResult(
            title = if (input.dryRun) "gc clip cache (dry run)" else "gc clip cache",
            outputForLlm = "No GC policy set on project ${pid.value} (both maxAgeDays and " +
                "keepLastN are null). Nothing to GC. Pass a policy argument to actually prune.",
            data = ProjectMaintenanceActionTool.Output(
                projectId = pid.value,
                action = "gc-render-cache",
                totalEntries = entries.size,
                prunedCount = 0,
                keptCount = entries.size,
                dryRun = input.dryRun,
            ),
        )
    }

    val nowMs = clock.now().toEpochMilliseconds()
    val ageCutoffMs: Long? = input.maxAgeDays?.let { nowMs - it.toLong() * MAINTENANCE_MILLIS_PER_DAY }

    val droppedByAge: Set<ClipRenderCacheEntry> = if (ageCutoffMs != null) {
        entries.filter { it.createdAtEpochMs < ageCutoffMs }.toSet()
    } else {
        emptySet()
    }

    val droppedByCount: Set<ClipRenderCacheEntry> = if (input.keepLastN != null) {
        val keep = input.keepLastN
        val sorted = entries.withIndex().sortedWith(
            compareByDescending<IndexedValue<ClipRenderCacheEntry>> { it.value.createdAtEpochMs }
                .thenByDescending { it.index },
        )
        sorted.drop(keep).map { it.value }.toSet()
    } else {
        emptySet()
    }

    val selectedForDrop: List<Pair<ClipRenderCacheEntry, String>> = entries.mapNotNull { e ->
        val byAge = e in droppedByAge
        val byCount = e in droppedByCount
        when {
            byAge && byCount -> e to "age+count"
            byAge -> e to "age"
            byCount -> e to "count"
            else -> null
        }
    }

    val prunedRows = mutableListOf<ProjectMaintenanceActionTool.PrunedRenderCacheRow>()
    for ((entry, reason) in selectedForDrop) {
        val deleted = if (input.dryRun) {
            false
        } else {
            runCatching { engine.deleteMezzanine(entry.mezzaninePath) }.getOrDefault(false)
        }
        prunedRows += ProjectMaintenanceActionTool.PrunedRenderCacheRow(
            fingerprint = entry.fingerprint,
            mezzaninePath = entry.mezzaninePath,
            createdAtEpochMs = entry.createdAtEpochMs,
            reason = reason,
            fileDeleted = deleted,
        )
    }

    if (selectedForDrop.isNotEmpty() && !input.dryRun) {
        val keepFps = entries
            .map { it.fingerprint }
            .toMutableSet()
            .apply { removeAll(selectedForDrop.map { it.first.fingerprint }.toSet()) }
        projects.mutate(pid) { p ->
            p.copy(clipRenderCache = p.clipRenderCache.retainByFingerprint(keepFps))
        }
    }

    val policiesApplied = buildList {
        if (ageEnabled) add("age")
        if (countEnabled) add("count")
    }

    val verb = if (input.dryRun) "Would drop" else "Dropped"
    val summary = when {
        entries.isEmpty() ->
            "Clip render cache on project ${pid.value} is empty. Nothing to GC."
        selectedForDrop.isEmpty() ->
            "All ${entries.size} clip cache entries on project ${pid.value} pass the GC " +
                "policy (${policiesApplied.joinToString("+")}). Nothing to drop."
        else -> {
            val fileDeletedCount = prunedRows.count { it.fileDeleted }
            "$verb ${selectedForDrop.size} of ${entries.size} clip cache entries on project " +
                "${pid.value} (policy: ${policiesApplied.joinToString("+")}; " +
                "$fileDeletedCount mezzanine mp4(s) deleted on disk)."
        }
    }

    return ToolResult(
        title = if (input.dryRun) "gc clip cache (dry run)" else "gc clip cache",
        outputForLlm = summary,
        data = ProjectMaintenanceActionTool.Output(
            projectId = pid.value,
            action = "gc-render-cache",
            totalEntries = entries.size,
            prunedCount = selectedForDrop.size,
            keptCount = entries.size - selectedForDrop.size,
            dryRun = input.dryRun,
            prunedRenderCacheRows = prunedRows,
            policiesApplied = policiesApplied,
        ),
    )
}
