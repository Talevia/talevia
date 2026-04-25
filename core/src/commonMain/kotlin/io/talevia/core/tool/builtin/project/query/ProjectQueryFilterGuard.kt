package io.talevia.core.tool.builtin.project.query

import io.talevia.core.tool.builtin.project.ProjectQueryTool

/**
 * Cross-field validation for [ProjectQueryTool.Input] — each filter field
 * belongs to a specific `select`, and setting it on the wrong select usually
 * means the LLM typed the wrong field name. This guard fails loud so the
 * typo surfaces at dispatch time instead of silently returning a
 * misinterpreted empty list.
 *
 * Extracted out of `ProjectQueryTool.kt` (debt-split-project-query-tool
 * cycle) so the dispatcher file stays focused on routing + row shapes. The
 * logic is byte-identical to what shipped before the extraction — every
 * incompatibility message string is preserved verbatim so the LLM's error
 * traces are unchanged.
 *
 * Errors via `error(...)` so the tool-dispatch layer catches + surfaces the
 * failure as a normal tool error. Zero side effects; pure function of
 * `(select, input)`.
 */
internal fun rejectIncompatibleProjectQueryFilters(
    select: String,
    input: ProjectQueryTool.Input,
) {
    val misapplied = buildList {
        if (select != ProjectQueryTool.SELECT_TRACKS && input.onlyNonEmpty != null) {
            add("onlyNonEmpty (select=tracks only)")
        }
        if (select != ProjectQueryTool.SELECT_TIMELINE_CLIPS) {
            if (input.trackId != null) add("trackId (select=timeline_clips only)")
            if (input.fromSeconds != null) add("fromSeconds (select=timeline_clips only)")
            if (input.toSeconds != null) add("toSeconds (select=timeline_clips only)")
            if (input.onlySourceBound != null) add("onlySourceBound (select=timeline_clips only)")
        }
        // onlyPinned is valid for timeline_clips AND lockfile_entries; reject elsewhere.
        if (select != ProjectQueryTool.SELECT_TIMELINE_CLIPS &&
            select != ProjectQueryTool.SELECT_LOCKFILE_ENTRIES &&
            input.onlyPinned != null
        ) {
            add("onlyPinned (select=timeline_clips or lockfile_entries only)")
        }
        if (select != ProjectQueryTool.SELECT_ASSETS) {
            if (input.kind != null) add("kind (select=assets only)")
            if (input.onlyUnused != null) add("onlyUnused (select=assets only)")
            if (input.onlyReferenced != null) add("onlyReferenced (select=assets only)")
        }
        if (select == ProjectQueryTool.SELECT_ASSETS && input.trackKind != null) {
            add("trackKind (select=tracks or timeline_clips only)")
        }
        if (select != ProjectQueryTool.SELECT_TRANSITIONS && input.onlyOrphaned != null) {
            add("onlyOrphaned (select=transitions only)")
        }
        if (select != ProjectQueryTool.SELECT_LOCKFILE_ENTRIES && input.toolId != null) {
            add("toolId (select=lockfile_entries only)")
        }
        // assetId is valid for clips_for_asset (lookup clips bound to an asset)
        // AND lockfile_entry (reverse-lookup the entry that produced an asset
        // via Lockfile.byAssetId — see runLockfileEntryDetailQuery). Reject
        // elsewhere.
        if (select != ProjectQueryTool.SELECT_CLIPS_FOR_ASSET &&
            select != ProjectQueryTool.SELECT_LOCKFILE_ENTRY &&
            input.assetId != null
        ) {
            add("assetId (select=clips_for_asset or lockfile_entry only)")
        }
        if (select != ProjectQueryTool.SELECT_CLIPS_FOR_SOURCE &&
            select != ProjectQueryTool.SELECT_CONSISTENCY_PROPAGATION &&
            select != ProjectQueryTool.SELECT_LOCKFILE_ENTRIES &&
            input.sourceNodeId != null
        ) {
            add("sourceNodeId (select=clips_for_source, consistency_propagation, or lockfile_entries only)")
        }
        if (select != ProjectQueryTool.SELECT_LOCKFILE_ENTRIES && input.sinceEpochMs != null) {
            add("sinceEpochMs (select=lockfile_entries only)")
        }
        if (select != ProjectQueryTool.SELECT_SNAPSHOTS && input.maxAgeDays != null) {
            add("maxAgeDays (select=snapshots only)")
        }
        if (select != ProjectQueryTool.SELECT_CLIP && input.clipId != null) {
            add("clipId (select=clip only)")
        }
        if (select != ProjectQueryTool.SELECT_LOCKFILE_ENTRY && input.inputHash != null) {
            add("inputHash (select=lockfile_entry only)")
        }
        if (select != ProjectQueryTool.SELECT_TIMELINE_DIFF) {
            if (input.fromSnapshotId != null) add("fromSnapshotId (select=timeline_diff only)")
            if (input.toSnapshotId != null) add("toSnapshotId (select=timeline_diff only)")
        }
    }
    if (misapplied.isNotEmpty()) {
        error(
            "The following filter fields do not apply to select='$select': " +
                misapplied.joinToString(", "),
        )
    }
}
