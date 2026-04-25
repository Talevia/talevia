package io.talevia.core.tool.builtin.project.query

import io.talevia.core.domain.Project
import io.talevia.core.domain.staleClipsFromLockfile
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Row for `select=stale_clips` — one timeline clip whose conditioning
 * source nodes have changed since the asset was generated. Sourced
 * from [staleClipsFromLockfile]: joins each AIGC clip against its
 * lockfile entry and reports the source ids whose snapshotted hash
 * no longer matches the project's current value.
 *
 * `changedSourceIds` lists the **directly-bound** ids that drifted.
 * A parent edit that propagates into a child via the source DAG (deep-
 * hash drift) surfaces under the child's id, not the root-cause
 * ancestor — the report names the bound-and-drifted nodes, not the
 * ultimate cause. This matches the contract the pre-fold
 * `find_stale_clips` tool documented and the
 * `transitiveConsistencyEditFlagsGrandchildBoundClip` test pinned.
 *
 * Imported (non-AIGC) media is excluded — there's no lockfile entry
 * to compare against. Legacy entries with empty `sourceContentHashes`
 * are also skipped (pre-snapshot lockfile schema; never stale, never
 * fresh — third state, not binary).
 */
@Serializable
data class StaleClipReportRow(
    val clipId: String,
    val assetId: String,
    val changedSourceIds: List<String>,
)

/**
 * `select=stale_clips` — every clip whose lockfile snapshot of bound
 * source-node `contentHash` no longer matches the project's current
 * value. This is the read side of the VISION §3.2 edit-source-then-
 * regenerate loop:
 *
 *   1. User: "make Mei's hair red instead of teal".
 *   2. Agent: `source_query(select=node_detail, id=mei)` → mutate
 *      body.visualDescription → `update_source_node_body`.
 *   3. Agent: `project_query(select=stale_clips)` → list of
 *      `{clipId, assetId, changedSourceIds}`.
 *   4. Agent: `regenerate_stale_clips` (or call the producing AIGC tool
 *      manually + `clip_action(action="replace")` with the new asset).
 *
 * Cycle 138 absorbed the standalone `find_stale_clips` tool here,
 * mirroring `clips_for_source` / `lockfile_orphans` / the
 * `node_detail` fold from cycle 137 — read-only single-purpose
 * lookups belong on the same dispatcher as bulk projections.
 *
 * Sorted by `clipId` ASC so repeated calls against the same project
 * state are reproducible. The dispatcher's common `limit` (default
 * 100, max 500) and `offset` apply; `total` reflects the true stale
 * count even when `rows` is truncated, so the agent can always tell
 * when it's looking at a partial view.
 */
internal fun runStaleClipsQuery(
    project: Project,
    limit: Int,
    offset: Int,
): ToolResult<ProjectQueryTool.Output> {
    val all = project.staleClipsFromLockfile()
        .map { r ->
            StaleClipReportRow(
                clipId = r.clipId.value,
                assetId = r.assetId.value,
                changedSourceIds = r.changedSourceIds.map { it.value },
            )
        }
        .sortedBy { it.clipId }
    val total = all.size
    val page = all.drop(offset).take(limit)
    val jsonRows = encodeRows(ListSerializer(StaleClipReportRow.serializer()), page)

    val totalClips = project.timeline.tracks.sumOf { it.clips.size }
    val summary = if (total == 0) {
        "All AIGC clips fresh ($totalClips clip(s) total; nothing to regenerate)."
    } else {
        val truncNote = if (total > page.size) " (showing ${page.size} of $total — raise limit to see more)" else ""
        val preview = page.take(5).joinToString("; ") {
            "${it.clipId} (changed: ${it.changedSourceIds.joinToString(",")})"
        }
        val tail = if (page.size > 5) "; …" else ""
        "$total of $totalClips clip(s) stale$truncNote. $preview$tail"
    }

    return ToolResult(
        title = "project_query stale_clips ($total)",
        outputForLlm = summary,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_STALE_CLIPS,
            total = total,
            returned = page.size,
            rows = jsonRows,
        ),
    )
}
