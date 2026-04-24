package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=lockfile_cache_stats` — single-row aggregate that answers VISION
 * §5.7 / §5.3's "pin 命中率可见" (M2 criterion 3): for the project's current
 * timeline, how many AIGC clips would short-circuit on a lockfile lookup vs
 * would re-run the provider if the user re-regenerated.
 *
 * **Computation (all derived from [Project] state — no cross-request
 * bookkeeping):**
 * - [LockfileCacheStatsRow.totalExports] — `project.renderCache.entries.size`.
 *   The count of whole-timeline exports this project has memoized so far.
 *   This is a proxy for "how many distinct export configurations the project
 *   has rendered"; each entry is keyed on a fingerprint over canonical
 *   timeline JSON + [io.talevia.core.platform.OutputSpec], so re-exporting the
 *   same timeline to the same spec hits the same entry.
 * - [LockfileCacheStatsRow.cacheHits] — count of current-timeline clips whose
 *   `assetId` appears in `project.lockfile.byAssetId`. These are AIGC-produced
 *   clips whose lockfile entry still stands: re-dispatching the producing
 *   tool with the same canonical inputs would match on `inputHash` and return
 *   the cached asset instead of calling the provider.
 * - [LockfileCacheStatsRow.cacheMisses] — current-timeline clips with a
 *   non-empty `sourceBinding` but no matching lockfile entry (either the
 *   entry was pruned, or the clip was authored pre-lockfile and the source
 *   binding was stamped manually). Clips with empty `sourceBinding` are NOT
 *   counted — those are hand-authored / imported media, where "cache
 *   hit/miss" doesn't apply.
 * - [LockfileCacheStatsRow.hitRatio] — `hits / (hits + misses)` clamped so an
 *   all-imported project (zero hits, zero misses) reports `0.0` rather than
 *   dividing by zero.
 * - [LockfileCacheStatsRow.perModelBreakdown] — one [PerModelBreakdownRow]
 *   per `(providerId, modelId)` seen across the hit clips. Misses have no
 *   lockfile entry and therefore no model to attribute to, so they roll up
 *   into a single synthetic `"unknown"` row at the tail when any exist —
 *   never silently dropped.
 */
@Serializable data class LockfileCacheStatsRow(
    val projectId: String,
    val totalExports: Int,
    val cacheHits: Int,
    val cacheMisses: Int,
    val hitRatio: Double,
    val perModelBreakdown: List<PerModelBreakdownRow> = emptyList(),
)

@Serializable data class PerModelBreakdownRow(
    val providerId: String,
    val modelId: String,
    val hits: Int,
    val misses: Int,
)

internal fun runLockfileCacheStatsQuery(
    project: Project,
): ToolResult<ProjectQueryTool.Output> {
    val totalExports = project.renderCache.entries.size
    val byAssetId = project.lockfile.byAssetId

    var hits = 0
    var misses = 0
    // Key = "providerId::modelId" so we keep both fields out to the row
    // without re-indexing. Misses without a lockfile entry accumulate under
    // a sentinel key surfaced in the row as a literal "unknown" provider +
    // "unknown" model — rolled up so the reader can see "N clips have no
    // lockfile entry" without spelunking the row array.
    val hitsByModel = mutableMapOf<Pair<String, String>, Int>()
    var unknownMisses = 0

    for (track in project.timeline.tracks) {
        for (clip in track.clips) {
            val (assetId, hasBinding) = when (clip) {
                is Clip.Video -> clip.assetId to clip.sourceBinding.isNotEmpty()
                is Clip.Audio -> clip.assetId to clip.sourceBinding.isNotEmpty()
                is Clip.Text -> continue // text clips have no assetId
            }
            val entry = byAssetId[assetId]
            if (entry != null) {
                hits += 1
                val key = entry.provenance.providerId to entry.provenance.modelId
                hitsByModel[key] = (hitsByModel[key] ?: 0) + 1
            } else if (hasBinding) {
                // AIGC-ish clip (declared sourceBinding) without a lockfile
                // entry — a genuine miss: re-generating it would hit the
                // provider.
                misses += 1
                unknownMisses += 1
            }
            // Else: assetId not in lockfile AND sourceBinding empty — treat
            // as imported / hand-authored media, neither hit nor miss.
        }
    }

    val denom = (hits + misses).coerceAtLeast(1)
    val hitRatio = hits.toDouble() / denom.toDouble()

    val breakdown = buildList {
        hitsByModel.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }))
            .forEach { (k, h) ->
                add(
                    PerModelBreakdownRow(
                        providerId = k.first,
                        modelId = k.second,
                        hits = h,
                        misses = 0,
                    ),
                )
            }
        if (unknownMisses > 0) {
            add(
                PerModelBreakdownRow(
                    providerId = "unknown",
                    modelId = "unknown",
                    hits = 0,
                    misses = unknownMisses,
                ),
            )
        }
    }

    val row = LockfileCacheStatsRow(
        projectId = project.id.value,
        totalExports = totalExports,
        cacheHits = hits,
        cacheMisses = misses,
        hitRatio = hitRatio,
        perModelBreakdown = breakdown,
    )
    val rows = encodeRows(
        ListSerializer(LockfileCacheStatsRow.serializer()),
        listOf(row),
    )
    val ratioPct = (hitRatio * 100.0).let { kotlin.math.round(it * 10.0) / 10.0 }
    val summary =
        "Project ${project.id.value}: $totalExports export(s) memoized; " +
            "$hits clip(s) would hit the lockfile vs $misses miss(es) on re-generation " +
            "(hit ratio $ratioPct%)."
    return ToolResult(
        title = "project_query lockfile_cache_stats ($hits/${hits + misses})",
        outputForLlm = summary,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_LOCKFILE_CACHE_STATS,
            total = 1,
            returned = 1,
            rows = rows,
        ),
    )
}
