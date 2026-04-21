package io.talevia.core.tool.builtin.project

import io.talevia.core.AssetId
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.lockfile.LockfileEntry
import io.talevia.core.permission.PermissionSpec
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
 * Policy-based GC for the AIGC lockfile — the companion to
 * [PruneLockfileTool] (which sweeps orphan rows whose `assetId` is no
 * longer in `project.assets`). Where prune answers "is this row still
 * referentially alive?", GC answers "is this row still *useful* as a
 * cache entry?"
 *
 * Two independent policies, both optional:
 *   - **Age.** `maxAgeDays`: drop rows whose
 *     `provenance.createdAtEpochMs` is strictly older than the cutoff.
 *     Equal-to-cutoff rows are kept — the boundary is a keep fence,
 *     not a drop fence, which matches how users say "keep the last
 *     7 days".
 *   - **Count.** `keepLatestPerTool`: for each `toolId` bucket, keep
 *     the `N` most recent entries and drop the rest. `N=0` drops every
 *     entry for every tool id that has any (rare but well-defined).
 *
 * Policies compose as AND — a row must pass both to survive. OR would
 * make the size-cap meaningless: a 10000-row lockfile that kept
 * everything "recent" would still be a 10000-row lockfile. Both-null
 * is a no-op with an informative pointer toward `prune_lockfile`.
 *
 * After age+count selects the drop set, [Input.preserveLiveAssets]
 * (default `true`) rescues any row whose `assetId` is still in
 * `project.assets`. Rationale: GC must never invalidate a real cache
 * hit that's still backing a live asset. A user who edited one clip
 * last month still wants that row so a re-generation with the same
 * inputs hits the cache. Set `false` only when the user explicitly
 * asks for a strict policy sweep that ignores referential liveness.
 *
 * Permission is [PermissionSpec.fixed] `"project.write"` in both modes.
 * Dry-run is a preview convenience, not a separate security tier —
 * same stance as [PruneLockfileTool].
 *
 * Clock is injected for deterministic tests; runtime defaults to
 * [Clock.System], matching [SaveProjectSnapshotTool].
 */
class GcLockfileTool(
    private val projects: ProjectStore,
    private val clock: Clock = Clock.System,
) : Tool<GcLockfileTool.Input, GcLockfileTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** Drop entries whose `provenance.createdAtEpochMs` is strictly older than
         *  `now - maxAgeDays`. Null disables the age policy. Must be `>= 0` when set. */
        val maxAgeDays: Int? = null,
        /** Keep only the N most recent entries per `toolId`; drop the rest.
         *  Null disables the count policy. Must be `>= 0` when set. `0` drops
         *  every entry for every toolId — valid but rarely what you want. */
        val keepLatestPerTool: Int? = null,
        /** When true (default), a row whose `assetId` is still in
         *  `project.assets` is never dropped, even if the age/count
         *  policies selected it. Set false for a strict policy sweep. */
        val preserveLiveAssets: Boolean = true,
        /** When true, report without mutating. Default false. */
        val dryRun: Boolean = false,
    )

    @Serializable data class PrunedSummary(
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        val createdAtEpochMs: Long,
        /** One of "age", "count", or "age+count". */
        val reason: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val totalEntries: Int,
        val prunedCount: Int,
        val keptCount: Int,
        /** Count of entries the age/count policies selected for drop but that
         *  the live-asset guard rescued. Always `0` when
         *  `preserveLiveAssets=false`. */
        val keptByLiveAssetGuardCount: Int,
        /** Count of entries the age/count policies selected for drop but that
         *  the user-pin guard rescued (`pinned=true`). The pin guard runs before
         *  the live-asset guard, so a pinned+live row counts here, not there. */
        val keptByPinCount: Int,
        val prunedEntries: List<PrunedSummary>,
        val dryRun: Boolean,
        /** Subset of {"age", "count", "liveAssetGuard", "pinGuard"} — which policies
         *  were active on this call. Empty when both policies are null. */
        val policiesApplied: List<String>,
    )

    override val id: String = "gc_lockfile"
    override val helpText: String =
        "Policy-based garbage collection for the AIGC lockfile. Companion to " +
            "prune_lockfile: prune drops orphan rows (assetId gone from project.assets); gc " +
            "drops rows by age (maxAgeDays) and/or per-toolId count (keepLatestPerTool). " +
            "Policies AND together: an entry must pass both to survive. Pinned rows " +
            "(set_lockfile_entry_pinned) are always rescued — a pin is user intent and overrides policy. " +
            "preserveLiveAssets=true (default) additionally rescues rows whose asset is still in " +
            "project.assets so live-cache hits aren't invalidated by policy sweeps. Pass " +
            "dryRun=true to preview without mutating. Both policies null is a no-op — use " +
            "prune_lockfile for orphan sweeps."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("maxAgeDays") {
                put("type", "integer")
                put("minimum", 0)
                put("description", "Drop entries strictly older than now - maxAgeDays. Null disables the age policy.")
            }
            putJsonObject("keepLatestPerTool") {
                put("type", "integer")
                put("minimum", 0)
                put("description", "Keep the N most recent entries per toolId; drop the rest. Null disables the count policy.")
            }
            putJsonObject("preserveLiveAssets") {
                put("type", "boolean")
                put("description", "When true (default), spare rows whose assetId is still in project.assets.")
            }
            putJsonObject("dryRun") {
                put("type", "boolean")
                put("description", "Report which entries would prune without mutating. Default false.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        input.maxAgeDays?.let {
            require(it >= 0) { "maxAgeDays must be >= 0 (was $it)" }
        }
        input.keepLatestPerTool?.let {
            require(it >= 0) { "keepLatestPerTool must be >= 0 (was $it)" }
        }

        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("project ${input.projectId} not found")

        val entries = project.lockfile.entries
        val liveAssetIds: Set<AssetId> = project.assets.asSequence().map { it.id }.toSet()

        val ageEnabled = input.maxAgeDays != null
        val countEnabled = input.keepLatestPerTool != null

        // No policy → hand the agent a pointer at the sibling tool.
        if (!ageEnabled && !countEnabled) {
            val out = Output(
                projectId = pid.value,
                totalEntries = entries.size,
                prunedCount = 0,
                keptCount = entries.size,
                keptByLiveAssetGuardCount = 0,
                keptByPinCount = 0,
                prunedEntries = emptyList(),
                dryRun = input.dryRun,
                policiesApplied = emptyList(),
            )
            return ToolResult(
                title = if (input.dryRun) "gc lockfile (dry run)" else "gc lockfile",
                outputForLlm = "No GC policy set on project ${pid.value} (both maxAgeDays and " +
                    "keepLatestPerTool are null). Nothing to GC. Call prune_lockfile for an orphan " +
                    "sweep, or pass a policy argument.",
                data = out,
            )
        }

        // Age policy: drop strictly older than (now - maxAgeDays). Equal = keep.
        val nowMs = clock.now().toEpochMilliseconds()
        val ageCutoffMs: Long? = input.maxAgeDays?.let {
            nowMs - it.toLong() * MILLIS_PER_DAY
        }

        val droppedByAge: Set<LockfileEntry> = if (ageCutoffMs != null) {
            entries.filter { it.provenance.createdAtEpochMs < ageCutoffMs }.toSet()
        } else {
            emptySet()
        }

        // Count policy: per-toolId, keep the N most-recent (by createdAtEpochMs,
        // tiebreak by original index so the choice is deterministic).
        val droppedByCount: Set<LockfileEntry> = if (input.keepLatestPerTool != null) {
            val keep = input.keepLatestPerTool
            val byTool = entries.withIndex().groupBy { (_, e) -> e.toolId }
            val toDrop = mutableSetOf<LockfileEntry>()
            for ((_, indexed) in byTool) {
                // Sort descending by createdAtEpochMs, ties broken by higher original index
                // so the last-inserted row wins a tiebreak (append-order = recency proxy).
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

        // Selected-for-drop is the union of each enabled policy. That matches AND
        // semantics on "kept" — kept iff passes all enabled policies.
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

        // Pin guard runs before the live-asset guard so pinned rows are always
        // counted under [keptByPinCount], not double-attributed when they also
        // happen to have a live asset. VISION §3.1 — a pin is user intent, it
        // takes priority over any policy.
        val (pinRescued, afterPinGuard) = selectedForDrop.partition { (e, _) -> e.pinned }

        // Live-asset guard rescues entries whose asset is still referenced.
        val (liveRescued, pruned) = if (input.preserveLiveAssets) {
            afterPinGuard.partition { (e, _) -> e.assetId in liveAssetIds }
        } else {
            emptyList<Pair<LockfileEntry, String>>() to afterPinGuard
        }

        val prunedSet: Set<LockfileEntry> = pruned.map { it.first }.toSet()

        if (prunedSet.isNotEmpty() && !input.dryRun) {
            projects.mutate(pid) { p ->
                p.copy(
                    lockfile = p.lockfile.copy(
                        entries = p.lockfile.entries.filter { it !in prunedSet },
                    ),
                )
            }
        }

        val prunedSummaries = pruned.map { (e, reason) ->
            PrunedSummary(
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

        val out = Output(
            projectId = pid.value,
            totalEntries = entries.size,
            prunedCount = pruned.size,
            keptCount = entries.size - pruned.size,
            keptByLiveAssetGuardCount = liveRescued.size,
            keptByPinCount = pinRescued.size,
            prunedEntries = prunedSummaries,
            dryRun = input.dryRun,
            policiesApplied = policiesApplied,
        )

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
                    prunedSummaries.take(5).joinToString(", ") { "${it.toolId}/${it.assetId}(${it.reason})" } +
                    if (prunedSummaries.size > 5) ", …).$pinNote$rescueNote" else ").$pinNote$rescueNote"
        }
        return ToolResult(
            title = if (input.dryRun) "gc lockfile (dry run)" else "gc lockfile",
            outputForLlm = summary,
            data = out,
        )
    }

    private companion object {
        const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L
    }
}
