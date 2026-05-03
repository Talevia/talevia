package io.talevia.core.tool.builtin.project.query

import io.talevia.core.AssetId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Row for `select=lockfile_orphans` — one lockfile entry no clip on the
 * timeline references. Surfaced so `gc_lockfile` / manual prune decisions
 * have concrete inputs ("which cache entries are eating space but aren't
 * being used anywhere?").
 *
 * `pinned` is carried on the row deliberately: a pinned-but-orphan entry
 * should not be dropped without the user's explicit override (pin =
 * "keep across GC"), but showing it is still useful — operators want to
 * audit what their pins are protecting. Sorting bubbles unpinned orphans
 * to the top so the first-page result is the actionable set.
 */
@Serializable
data class LockfileOrphanRow(
    val assetId: String,
    val inputHash: String,
    val toolId: String,
    val providerId: String,
    val modelId: String,
    val costCents: Long? = null,
    val createdAtEpochMs: Long,
    val pinned: Boolean,
)

/**
 * `select=lockfile_orphans` — lockfile entries whose `assetId` is not
 * referenced by any clip on the timeline (direct `Clip.Video.assetId` /
 * `Clip.Audio.assetId`; `Clip.Text` has no asset). Returns rows sorted
 * by (pinned ascending, createdAtEpochMs descending) so the most-recent
 * unpinned entries — the ripest drop candidates — are at the top.
 *
 * No filters beyond the common `limit` / `offset`; adding a toolId or
 * providerId filter would be a thin overlay on `select=lockfile_entries`
 * combined with an `onlyOrphan = true` flag — we take the dedicated
 * select path for discoverability (the gc / audit use case is worth a
 * named verb rather than a flag on the general enumerator).
 *
 * Complements `select=lockfile_entries` (all entries, filter-as-needed)
 * and `select=lockfile_cache_stats` (ratios / aggregate) — three
 * orthogonal ways to read the lockfile. Rubric §5.7 observability.
 */
internal fun runLockfileOrphansQuery(
    project: Project,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val referenced: Set<AssetId> = buildSet {
        for (track in project.timeline.tracks) {
            for (clip in track.clips) {
                when (clip) {
                    is Clip.Video -> add(clip.assetId)
                    is Clip.Audio -> add(clip.assetId)
                    is Clip.Text -> { /* no asset */ }
                }
            }
        }
    }

    val orphans = project.lockfile.stream().filter { it.assetId !in referenced }.toList()
    val sorted = orphans.sortedWith(
        // Unpinned first so the top of the list is the actionable
        // drop-candidate set; within each pinned bucket, newest first so
        // a scrolling reader sees recent activity without paging.
        compareBy<io.talevia.core.domain.lockfile.LockfileEntry> { it.pinned }
            .thenByDescending { it.provenance.createdAtEpochMs },
    )
    val total = sorted.size
    val page = sorted.drop(offset).take(limit)

    val rows = page.map { e ->
        LockfileOrphanRow(
            assetId = e.assetId.value,
            inputHash = e.inputHash,
            toolId = e.toolId,
            providerId = e.provenance.providerId,
            modelId = e.provenance.modelId,
            costCents = e.costCents,
            createdAtEpochMs = e.provenance.createdAtEpochMs,
            pinned = e.pinned,
        )
    }
    val jsonRows = encodeRows(ListSerializer(LockfileOrphanRow.serializer()), rows)

    val unpinnedCount = orphans.count { !it.pinned }
    val pinnedCount = orphans.size - unpinnedCount
    val summary = if (total == 0) {
        "No orphan lockfile entries in project ${project.id.value} — every cached AIGC " +
            "product is still referenced by a clip."
    } else {
        val costTotal = orphans.mapNotNull { it.costCents }.sum()
        "$total orphan lockfile entr${if (total == 1) "y" else "ies"} in project " +
            "${project.id.value} ($unpinnedCount unpinned, $pinnedCount pinned)" +
            if (costTotal > 0L) "; roughly ¢$costTotal of cached AIGC cost is unreferenced." else "."
    }

    return ToolResult(
        title = "project_query lockfile_orphans (${rows.size}/$total)",
        outputForLlm = summary,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_LOCKFILE_ORPHANS,
            total = total,
            returned = rows.size,
            rows = jsonRows,
        ),
    )
}
