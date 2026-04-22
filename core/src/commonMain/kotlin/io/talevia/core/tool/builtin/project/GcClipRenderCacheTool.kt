package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
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
 * Policy-based GC for [io.talevia.core.domain.render.ClipRenderCache] — the
 * per-clip mezzanine cache that powers the per-clip incremental export path
 * (VISION §3.2 "只重编译必要的部分"). The cache is append-only by construction:
 * every cache-miss render appends a row (`ClipRenderCacheEntry`) pointing at
 * an on-disk `.mp4`, and there is no eviction elsewhere. Long-lived projects
 * accumulate orphan mezzanines each time the source drifts, so disk usage
 * grows without bound until the user deletes the `.talevia-render-cache`
 * directory by hand.
 *
 * This tool applies explicit policies and, for each pruned entry, asks the
 * injected [VideoEngine] to drop the mezzanine file on disk. Shape mirrors
 * [GcLockfileTool]:
 *   - **Age.** `maxAgeDays`: drop rows whose `createdAtEpochMs` is strictly
 *     older than `now - maxAgeDays`. Equal-to-cutoff rows are kept — the
 *     boundary is a "keep fence", matching how users say "keep the last 7
 *     days".
 *   - **Count.** `keepLastN`: keep the N most-recent entries (by
 *     `createdAtEpochMs`, tiebreak by later append index so append-order
 *     breaks ties deterministically) and drop the rest.
 *
 * Policies AND together: a row must pass both enabled policies to survive.
 * Both-null is a no-op with a helpText pointer. The AND semantics matches
 * [GcLockfileTool] and prevents a 10000-row cache that "keeps everything
 * recent" from being useless as a size cap.
 *
 * No `preserveLiveAssets` equivalent — unlike the lockfile, the clip
 * mezzanine cache is **pure cache**: a pruned row just triggers a re-render
 * on the next export. Nothing else references `mezzaninePath`. That's why
 * this tool is `project.write` rather than `project.destructive` — loss of
 * a cache entry is recoverable.
 *
 * Mezzanine file deletion goes through [VideoEngine.deleteMezzanine] — the
 * FFmpeg engine deletes the on-disk mp4; non-perClip engines (Media3,
 * AVFoundation today) return `false` because they never wrote a mezzanine
 * in the first place. On those engines the tool still prunes the table
 * rows and reports `fileDeleted=false` per entry — lossless for correctness,
 * just means no on-disk bytes were actually reclaimed.
 *
 * Clock is injected for deterministic tests; runtime defaults to
 * [Clock.System], matching [GcLockfileTool].
 */
class GcClipRenderCacheTool(
    private val projects: ProjectStore,
    private val engine: VideoEngine,
    private val clock: Clock = Clock.System,
) : Tool<GcClipRenderCacheTool.Input, GcClipRenderCacheTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** Drop entries whose `createdAtEpochMs` is strictly older than
         *  `now - maxAgeDays`. Null disables the age policy. Must be `>= 0` when set. */
        val maxAgeDays: Int? = null,
        /** Keep only the N most-recent entries; drop the rest. Null disables
         *  the count policy. Must be `>= 0` when set. `0` drops every entry. */
        val keepLastN: Int? = null,
        /** When true, report which entries would prune + which files would be
         *  deleted, but don't mutate. Default false. */
        val dryRun: Boolean = false,
    )

    @Serializable data class PrunedSummary(
        val fingerprint: String,
        val mezzaninePath: String,
        val createdAtEpochMs: Long,
        /** One of "age", "count", or "age+count". */
        val reason: String,
        /** True iff the on-disk mp4 was actually deleted by the engine.
         *  False when the file was already gone, when the engine's
         *  `deleteMezzanine` is a no-op (non-perClip engines), or when
         *  `dryRun=true`. */
        val fileDeleted: Boolean,
    )

    @Serializable data class Output(
        val projectId: String,
        val totalEntries: Int,
        val prunedCount: Int,
        val keptCount: Int,
        val prunedEntries: List<PrunedSummary>,
        val dryRun: Boolean,
        /** Subset of {"age", "count"} — which policies were active on this call.
         *  Empty when both policies are null. */
        val policiesApplied: List<String>,
    )

    override val id: String = "gc_clip_render_cache"
    override val helpText: String =
        "Policy-based garbage collection for the per-clip mezzanine cache " +
            "(Project.clipRenderCache). Drops entries by age (maxAgeDays) and/or " +
            "count (keepLastN), and deletes the underlying .mp4 mezzanine files on " +
            "disk via the VideoEngine. Policies AND together: an entry must pass " +
            "both to survive. Both-null is a no-op. Safe to call repeatedly — a " +
            "pruned entry just becomes a cache miss on the next export (nothing " +
            "else references mezzanine files). Pass dryRun=true to preview."
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
                put(
                    "description",
                    "Drop entries strictly older than now - maxAgeDays. Null disables the age policy.",
                )
            }
            putJsonObject("keepLastN") {
                put("type", "integer")
                put("minimum", 0)
                put(
                    "description",
                    "Keep the N most-recent entries (by createdAtEpochMs); drop the rest. Null disables the count policy.",
                )
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
        input.keepLastN?.let {
            require(it >= 0) { "keepLastN must be >= 0 (was $it)" }
        }

        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("project ${input.projectId} not found")

        val entries = project.clipRenderCache.entries
        val ageEnabled = input.maxAgeDays != null
        val countEnabled = input.keepLastN != null

        if (!ageEnabled && !countEnabled) {
            val out = Output(
                projectId = pid.value,
                totalEntries = entries.size,
                prunedCount = 0,
                keptCount = entries.size,
                prunedEntries = emptyList(),
                dryRun = input.dryRun,
                policiesApplied = emptyList(),
            )
            return ToolResult(
                title = if (input.dryRun) "gc clip cache (dry run)" else "gc clip cache",
                outputForLlm = "No GC policy set on project ${pid.value} (both maxAgeDays and " +
                    "keepLastN are null). Nothing to GC. Pass a policy argument to " +
                    "actually prune.",
                data = out,
            )
        }

        val nowMs = clock.now().toEpochMilliseconds()
        val ageCutoffMs: Long? = input.maxAgeDays?.let {
            nowMs - it.toLong() * MILLIS_PER_DAY
        }

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

        val prunedSummaries = mutableListOf<PrunedSummary>()
        for ((entry, reason) in selectedForDrop) {
            val deleted = if (input.dryRun) {
                false
            } else {
                runCatching { engine.deleteMezzanine(entry.mezzaninePath) }.getOrDefault(false)
            }
            prunedSummaries += PrunedSummary(
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

        val out = Output(
            projectId = pid.value,
            totalEntries = entries.size,
            prunedCount = selectedForDrop.size,
            keptCount = entries.size - selectedForDrop.size,
            prunedEntries = prunedSummaries,
            dryRun = input.dryRun,
            policiesApplied = policiesApplied,
        )

        val verb = if (input.dryRun) "Would drop" else "Dropped"
        val summary = when {
            entries.isEmpty() ->
                "Clip render cache on project ${pid.value} is empty. Nothing to GC."
            selectedForDrop.isEmpty() ->
                "All ${entries.size} clip cache entries on project ${pid.value} pass the GC " +
                    "policy (${policiesApplied.joinToString("+")}). Nothing to drop."
            else -> {
                val fileDeletedCount = prunedSummaries.count { it.fileDeleted }
                "$verb ${selectedForDrop.size} of ${entries.size} clip cache entries on project " +
                    "${pid.value} (policy: ${policiesApplied.joinToString("+")}; " +
                    "$fileDeletedCount mezzanine mp4(s) deleted on disk)."
            }
        }
        return ToolResult(
            title = if (input.dryRun) "gc clip cache (dry run)" else "gc clip cache",
            outputForLlm = summary,
            data = out,
        )
    }

    private companion object {
        const val MILLIS_PER_DAY: Long = 24L * 60L * 60L * 1000L
    }
}
