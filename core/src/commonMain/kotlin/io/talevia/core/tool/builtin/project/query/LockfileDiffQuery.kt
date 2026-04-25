package io.talevia.core.tool.builtin.project.query

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectSnapshotId
import io.talevia.core.domain.Project
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.ProjectQueryTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * `select=lockfile_diff` — single-row diff between two project payloads' AIGC
 * lockfiles, keyed by `inputHash` (the content-addressable identity of a
 * cached generation). Answers "after `regenerate_stale_clips`, which entries
 * are new and which were collected?" without forcing the agent to pull
 * `lockfile_entries` twice and diff client-side.
 *
 * Why no "changed" category — `LockfileEntry` is immutable by design:
 * `inputHash` is the deterministic hash of the inputs, and entries get
 * appended (or pinned/unpinned) but never mutated in place. So the diff
 * collapses to two non-empty categories — added (only in `to`) and removed
 * (only in `from`) — plus an unchanged count for context.
 *
 * Same-project only. Cross-project lockfile diffs are out of scope (would
 * conflate provider IDs and seeds across unrelated bundles). At least one of
 * `fromSnapshotId` / `toSnapshotId` must reference a snapshot — diffing
 * current-vs-current is always identical and almost always a usage error.
 *
 * Mirrors [TimelineDiffQuery]'s shape so callers that pull both diffs see
 * the same `fromLabel` / `toLabel` / `identical` / `totalChanges` envelope.
 */
@Serializable data class LockfileDiffEntryRef(
    val inputHash: String,
    val toolId: String,
    val assetId: String,
    val providerId: String,
    val modelId: String,
    val createdAtEpochMs: Long,
)

@Serializable data class LockfileDiffRow(
    val fromLabel: String,
    val toLabel: String,
    val added: List<LockfileDiffEntryRef> = emptyList(),
    val removed: List<LockfileDiffEntryRef> = emptyList(),
    /**
     * Count (not detail list) of inputHashes present in both sides. The
     * detail list would dominate the response on any mature project — the
     * count is what the agent actually needs to reason about cache health.
     */
    val unchangedCount: Int,
    /** True when neither `added` nor `removed` has any entries. */
    val identical: Boolean,
    /** Exact `added.size + removed.size`. */
    val totalChanges: Int,
)

/**
 * Cap on the per-side detail list. Mirrors `TIMELINE_DIFF_MAX_DETAIL`'s
 * intent: a wholesale lockfile rewrite (e.g. an export pipeline that
 * regenerates everything) shouldn't blow the response into thousands of
 * tokens. `unchangedCount` and `totalChanges` stay exact regardless.
 */
private const val LOCKFILE_DIFF_MAX_DETAIL = 50

private fun <T> List<T>.capLockfileDiff(): List<T> = if (size <= LOCKFILE_DIFF_MAX_DETAIL) {
    this
} else {
    take(LOCKFILE_DIFF_MAX_DETAIL)
}

internal fun runLockfileDiffQuery(
    project: Project,
    input: ProjectQueryTool.Input,
): ToolResult<ProjectQueryTool.Output> {
    val fromSnap = input.fromSnapshotId
    val toSnap = input.toSnapshotId
    require(fromSnap != null || toSnap != null) {
        "select='${ProjectQueryTool.SELECT_LOCKFILE_DIFF}' requires at least one of " +
            "fromSnapshotId / toSnapshotId to reference a snapshot; diffing current-vs-current " +
            "is always identical."
    }

    val (fromProject, fromLabel) = resolveDiffSide(project, fromSnap, "from")
    val (toProject, toLabel) = resolveDiffSide(project, toSnap, "to")

    val fromByHash = fromProject.lockfile.entries.associateBy { it.inputHash }
    val toByHash = toProject.lockfile.entries.associateBy { it.inputHash }
    val addedKeys = toByHash.keys - fromByHash.keys
    val removedKeys = fromByHash.keys - toByHash.keys
    val unchangedKeys = fromByHash.keys.intersect(toByHash.keys)

    val added = addedKeys.map { hash ->
        val e = toByHash.getValue(hash)
        LockfileDiffEntryRef(
            inputHash = hash,
            toolId = e.toolId,
            assetId = e.assetId.value,
            providerId = e.provenance.providerId,
            modelId = e.provenance.modelId,
            createdAtEpochMs = e.provenance.createdAtEpochMs,
        )
    }.sortedByDescending { it.createdAtEpochMs }
    val removed = removedKeys.map { hash ->
        val e = fromByHash.getValue(hash)
        LockfileDiffEntryRef(
            inputHash = hash,
            toolId = e.toolId,
            assetId = e.assetId.value,
            providerId = e.provenance.providerId,
            modelId = e.provenance.modelId,
            createdAtEpochMs = e.provenance.createdAtEpochMs,
        )
    }.sortedByDescending { it.createdAtEpochMs }

    val totalChanges = added.size + removed.size
    val diff = LockfileDiffRow(
        fromLabel = fromLabel,
        toLabel = toLabel,
        added = added.capLockfileDiff(),
        removed = removed.capLockfileDiff(),
        unchangedCount = unchangedKeys.size,
        identical = totalChanges == 0,
        totalChanges = totalChanges,
    )
    val rows = JsonConfig.default.encodeToJsonElement(
        ListSerializer(LockfileDiffRow.serializer()),
        listOf(diff),
    )

    val summary = if (diff.identical) {
        "$fromLabel → $toLabel: lockfile identical (${diff.unchangedCount} entries unchanged)."
    } else {
        "$fromLabel → $toLabel: lockfile ${diff.totalChanges}Δ " +
            "(+${added.size} added / -${removed.size} removed / =${diff.unchangedCount} unchanged)."
    }

    return ToolResult(
        title = "project_query lockfile_diff ${project.id.value} ($fromLabel → $toLabel)",
        outputForLlm = summary,
        data = ProjectQueryTool.Output(
            projectId = project.id.value,
            select = ProjectQueryTool.SELECT_LOCKFILE_DIFF,
            total = 1,
            returned = 1,
            rows = rows as kotlinx.serialization.json.JsonArray,
        ),
    )
}

private fun resolveDiffSide(project: Project, snapshotId: String?, side: String): Pair<Project, String> {
    if (snapshotId == null) return project to "${project.id.value} @current"
    val snap = project.snapshots.firstOrNull { it.id == ProjectSnapshotId(snapshotId) }
        ?: error(
            "Snapshot '$snapshotId' not found on project ${project.id.value} ($side side). " +
                "Call project_query(select=snapshots) to list valid snapshot ids.",
        )
    return snap.project to "${project.id.value} @${snap.label}"
}
