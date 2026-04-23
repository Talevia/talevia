package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.domain.render.ClipRenderCacheEntry
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.platform.VideoEngine
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.datetime.Clock
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
 * Project-state maintenance sweeps — consolidated form that replaces
 * `PruneLockfileTool` + `GcLockfileTool` + `GcClipRenderCacheTool`
 * (`debt-consolidate-project-lockfile-maintenance`, 2026-04-23).
 *
 * Three sweep flavours, all under one LLM-facing tool:
 *
 * - `action="prune-lockfile"` — drop lockfile rows whose `assetId` is no
 *   longer in `project.assets` (orphan sweep; no policy knobs). Input:
 *   `projectId`, `dryRun`.
 * - `action="gc-lockfile"` — policy-based GC of the lockfile. Input:
 *   `projectId`, `dryRun`, optional `maxAgeDays`, optional
 *   `keepLatestPerTool`, optional `preserveLiveAssets` (default `true`).
 *   Policies AND; pinned rows always survive; live-asset guard
 *   rescues rows whose asset is still referenced (unless
 *   `preserveLiveAssets=false`). Both-null is a no-op with a pointer
 *   to `prune-lockfile`.
 * - `action="gc-render-cache"` — policy-based GC of the per-clip
 *   mezzanine cache (`Project.clipRenderCache`). Input: `projectId`,
 *   `dryRun`, optional `maxAgeDays`, optional `keepLastN`. Each pruned
 *   row also deletes its `.mp4` mezzanine file via
 *   `VideoEngine.deleteMezzanine`. Both-null is a no-op. No
 *   preserveLiveAssets equivalent — mezzanine cache is pure-cache
 *   (pruned entry = cache miss on next export, never broken state).
 *
 * All three actions share permission `project.write` (none of them
 * destroy anything the user can't regenerate — orphan rows are
 * already dead, GC drops what policy selects, mezzanine cache rebuilds
 * from source), and all three default `dryRun=false` with a uniform
 * preview path. Policies / shape differences live in the per-action
 * execute bodies.
 *
 * Output carries a per-action sub-list populated on its branch; unused
 * branches stay empty rather than nullable. Shared headers
 * (`totalEntries`, `prunedCount`, `keptCount`, `dryRun`,
 * `policiesApplied`) work for all three actions.
 */
class ProjectMaintenanceActionTool(
    private val projects: ProjectStore,
    private val engine: VideoEngine,
    private val clock: Clock = Clock.System,
) : Tool<ProjectMaintenanceActionTool.Input, ProjectMaintenanceActionTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** `"prune-lockfile"`, `"gc-lockfile"`, or `"gc-render-cache"`. */
        val action: String,
        /** Report without mutating. Default false. Applies to all actions. */
        val dryRun: Boolean = false,
        /** `gc-lockfile` + `gc-render-cache`: drop entries strictly older than `now - maxAgeDays`. `>= 0`. */
        val maxAgeDays: Int? = null,
        /** `gc-lockfile` only: keep the N most recent entries per `toolId`. `>= 0`. */
        val keepLatestPerTool: Int? = null,
        /** `gc-lockfile` only: rescue rows whose `assetId` is still in `project.assets`. Default true. */
        val preserveLiveAssets: Boolean = true,
        /** `gc-render-cache` only: keep the N most recent entries overall. `>= 0`. */
        val keepLastN: Int? = null,
    )

    /** `prune-lockfile` row. */
    @Serializable data class PrunedOrphanLockfileRow(
        val inputHash: String,
        val toolId: String,
        val assetId: String,
    )

    /** `gc-lockfile` row. */
    @Serializable data class PrunedGcLockfileRow(
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        val createdAtEpochMs: Long,
        /** One of `"age"`, `"count"`, `"age+count"`. */
        val reason: String,
    )

    /** `gc-render-cache` row. */
    @Serializable data class PrunedRenderCacheRow(
        val fingerprint: String,
        val mezzaninePath: String,
        val createdAtEpochMs: Long,
        /** One of `"age"`, `"count"`, `"age+count"`. */
        val reason: String,
        /** True iff the on-disk mp4 was actually deleted. False on dry-run or no-op engines. */
        val fileDeleted: Boolean,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        val totalEntries: Int,
        val prunedCount: Int,
        val keptCount: Int,
        val dryRun: Boolean,
        /** Only populated on `action="prune-lockfile"`. */
        val prunedOrphanLockfileRows: List<PrunedOrphanLockfileRow> = emptyList(),
        /** Only populated on `action="gc-lockfile"`. */
        val prunedGcLockfileRows: List<PrunedGcLockfileRow> = emptyList(),
        /** Only populated on `action="gc-render-cache"`. */
        val prunedRenderCacheRows: List<PrunedRenderCacheRow> = emptyList(),
        /** `gc-lockfile`-only: entries rescued because their asset is still in project.assets. */
        val keptByLiveAssetGuardCount: Int = 0,
        /** `gc-lockfile`-only: entries rescued because `pinned=true`. */
        val keptByPinCount: Int = 0,
        /** Which policies were applied — e.g. `["age", "count", "pinGuard", "liveAssetGuard"]`. */
        val policiesApplied: List<String> = emptyList(),
    )

    override val id: String = "project_maintenance_action"
    override val helpText: String =
        "Project-state maintenance sweeps in one tool: `action=\"prune-lockfile\"` drops " +
            "lockfile rows whose assetId is no longer in project.assets (orphan sweep); " +
            "`action=\"gc-lockfile\"` runs policy-based GC on the lockfile (optional " +
            "maxAgeDays + keepLatestPerTool, both AND; pinned rows always rescued; " +
            "preserveLiveAssets=true rescues rows whose asset is still referenced); " +
            "`action=\"gc-render-cache\"` runs policy-based GC on the per-clip mezzanine " +
            "cache (optional maxAgeDays + keepLastN, both AND) and deletes the underlying " +
            ".mp4 files via the video engine. All actions support dryRun=true for preview. " +
            "Both-null policy args on the gc-* actions are a no-op."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "`prune-lockfile`, `gc-lockfile`, or `gc-render-cache`.",
                )
                put(
                    "enum",
                    JsonArray(
                        listOf(
                            JsonPrimitive("prune-lockfile"),
                            JsonPrimitive("gc-lockfile"),
                            JsonPrimitive("gc-render-cache"),
                        ),
                    ),
                )
            }
            putJsonObject("dryRun") {
                put("type", "boolean")
                put("description", "Report without mutating. Default false. Applies to all actions.")
            }
            putJsonObject("maxAgeDays") {
                put("type", "integer")
                put("minimum", 0)
                put(
                    "description",
                    "gc-lockfile + gc-render-cache: drop entries strictly older than now - maxAgeDays.",
                )
            }
            putJsonObject("keepLatestPerTool") {
                put("type", "integer")
                put("minimum", 0)
                put(
                    "description",
                    "gc-lockfile only: keep the N most-recent entries per toolId; drop the rest.",
                )
            }
            putJsonObject("preserveLiveAssets") {
                put("type", "boolean")
                put(
                    "description",
                    "gc-lockfile only: rescue rows whose assetId is still in project.assets. Default true.",
                )
            }
            putJsonObject("keepLastN") {
                put("type", "integer")
                put("minimum", 0)
                put(
                    "description",
                    "gc-render-cache only: keep the N most-recent entries overall.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("action"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        return when (input.action) {
            "prune-lockfile" -> executePrune(pid, input)
            "gc-lockfile" -> executeGcLockfile(pid, input)
            "gc-render-cache" -> executeGcRenderCache(pid, input)
            else -> error(
                "unknown action '${input.action}'; accepted: prune-lockfile, gc-lockfile, gc-render-cache",
            )
        }
    }

    private suspend fun executePrune(pid: ProjectId, input: Input): ToolResult<Output> {
        val project = projects.get(pid) ?: error("project ${pid.value} not found")
        val liveAssetIds: Set<AssetId> = project.assets.asSequence().map { it.id }.toSet()
        val entries = project.lockfile.entries
        val kept = entries.filter { it.assetId in liveAssetIds }
        val pruned = entries.filter { it.assetId !in liveAssetIds }

        if (pruned.isNotEmpty() && !input.dryRun) {
            projects.mutate(pid) { p ->
                p.copy(lockfile = p.lockfile.copy(entries = p.lockfile.entries.filter { it.assetId in liveAssetIds }))
            }
        }

        val prunedRows = pruned.map {
            PrunedOrphanLockfileRow(inputHash = it.inputHash, toolId = it.toolId, assetId = it.assetId.value)
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
            data = Output(
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

    private suspend fun executeGcLockfile(pid: ProjectId, input: Input): ToolResult<Output> {
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
                    "project_maintenance_action(action=prune-lockfile) for an orphan sweep, or pass a policy argument.",
                data = Output(
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
        val ageCutoffMs: Long? = input.maxAgeDays?.let { nowMs - it.toLong() * MILLIS_PER_DAY }

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
                p.copy(lockfile = p.lockfile.copy(entries = p.lockfile.entries.filter { it !in prunedSet }))
            }
        }

        val prunedRows = pruned.map { (e, reason) ->
            PrunedGcLockfileRow(
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
            data = Output(
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

    private suspend fun executeGcRenderCache(pid: ProjectId, input: Input): ToolResult<Output> {
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
                data = Output(
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
        val ageCutoffMs: Long? = input.maxAgeDays?.let { nowMs - it.toLong() * MILLIS_PER_DAY }

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

        val prunedRows = mutableListOf<PrunedRenderCacheRow>()
        for ((entry, reason) in selectedForDrop) {
            val deleted = if (input.dryRun) {
                false
            } else {
                runCatching { engine.deleteMezzanine(entry.mezzaninePath) }.getOrDefault(false)
            }
            prunedRows += PrunedRenderCacheRow(
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
            data = Output(
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

    private companion object {
        const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L
    }
}
