package io.talevia.core.tool.builtin.project

import io.talevia.core.JsonConfig
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.query.rejectIncompatibleProjectQueryFilters
import io.talevia.core.tool.builtin.project.query.runAssetsQuery
import io.talevia.core.tool.builtin.project.query.runClipDetailQuery
import io.talevia.core.tool.builtin.project.query.runClipsForAssetQuery
import io.talevia.core.tool.builtin.project.query.runClipsForSourceQuery
import io.talevia.core.tool.builtin.project.query.runConsistencyPropagationQuery
import io.talevia.core.tool.builtin.project.query.runLockfileEntriesQuery
import io.talevia.core.tool.builtin.project.query.runLockfileEntryDetailQuery
import io.talevia.core.tool.builtin.project.query.runProjectMetadataQuery
import io.talevia.core.tool.builtin.project.query.runSnapshotsQuery
import io.talevia.core.tool.builtin.project.query.runSpendQuery
import io.talevia.core.tool.builtin.project.query.runTimelineClipsQuery
import io.talevia.core.tool.builtin.project.query.runTimelineDiffQuery
import io.talevia.core.tool.builtin.project.query.runTracksQuery
import io.talevia.core.tool.builtin.project.query.runTransitionsQuery
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Unified read-only query primitive over a [io.talevia.core.domain.Project].
 * One tool spec in the LLM's context instead of one tool per "kind of thing
 * you can list" — modelled on codebase-grep's `(select, filter, sort,
 * limit)` shape.
 *
 * [Input.select] discriminates what to return; each select advertises its
 * own filter fields and sort keys (see [helpText]). Filter fields that
 * don't apply to the chosen select fail loud — silent empty lists would
 * hide typos.
 *
 * Output is uniform: `{projectId, select, total, returned, rows}` where
 * `rows` is a [JsonArray] whose shape depends on `select`. The per-select
 * row data classes are top-level types in
 * `io.talevia.core.tool.builtin.project.query` (one file per select);
 * consumers decode with the matching row serializer using the canonical
 * [JsonConfig.default].
 *
 * This file stays a thin dispatcher: schema + validation + select routing.
 * Each select's implementation — including its row data class — lives in
 * its own sibling file so the dispatcher doesn't grow with every new
 * select.
 */
class ProjectQueryTool(
    private val projects: ProjectStore,
    /**
     * Wall clock used by `select=snapshots` when honouring the `maxAgeDays`
     * filter. Defaults to `Clock.System`; tests inject a fixed clock so
     * relative-age assertions stay deterministic.
     */
    private val clock: kotlinx.datetime.Clock = kotlinx.datetime.Clock.System,
) : Tool<ProjectQueryTool.Input, ProjectQueryTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud directs the agent at `switch_project`.
         */
        val projectId: String? = null,
        /** One of the `SELECT_*` constants (case-insensitive). */
        val select: String,
        // ---- tracks / timeline_clips filter ----
        /** `"video"` | `"audio"` | `"subtitle"` | `"effect"` (case-insensitive). */
        val trackKind: String? = null,
        /** Exact `track.id.value`. `select=timeline_clips` only. */
        val trackId: String? = null,
        /** Lower bound of the time window in seconds. `select=timeline_clips` only. */
        val fromSeconds: Double? = null,
        /** Upper bound of the time window in seconds. `select=timeline_clips` only. */
        val toSeconds: Double? = null,
        /** Skip tracks with zero clips. `select=tracks` only. */
        val onlyNonEmpty: Boolean? = null,
        /** Keep only clips with non-empty `sourceBinding`. `select=timeline_clips` only. */
        val onlySourceBound: Boolean? = null,
        /**
         * Tri-state pin filter. For `select=timeline_clips`: `true` = keep only
         * clips whose backing lockfile entry is pinned; `false` = keep only
         * clips NOT pinned. For `select=lockfile_entries`: `true` = keep only
         * pinned entries; `false` is treated as no filter (by the lockfile
         * entries query). `null` = no filter.
         */
        val onlyPinned: Boolean? = null,
        // ---- assets filter ----
        /** `"video"` | `"audio"` | `"image"` | `"all"`. `select=assets` only. */
        val kind: String? = null,
        /** Keep only assets referenced by zero clips. `select=assets` only. */
        val onlyUnused: Boolean? = null,
        /**
         * Tri-state reference filter (broader than [onlyUnused]). Considers a
         * reference from any of: Video/Audio clip asset, Video clip's LUT
         * filter asset, lockfile entry. `true` = keep only referenced assets.
         * `false` = keep only un-referenced assets (safe-to-delete orphans).
         * `null` = no filter. `select=assets` only.
         */
        val onlyReferenced: Boolean? = null,
        // ---- transitions filter ----
        /** Keep only transitions whose flanking video clips are both missing. `select=transitions` only. */
        val onlyOrphaned: Boolean? = null,
        // ---- lockfile_entries filter ----
        /** Filter lockfile entries by producing tool id (e.g. `"generate_image"`). `select=lockfile_entries` only. */
        val toolId: String? = null,
        /**
         * Lower-bound timestamp filter (entries with
         * `provenance.createdAtEpochMs >= sinceEpochMs`). `select=lockfile_entries`
         * only. Null = no time filter. Useful for "what has this project
         * generated since Monday" / "new entries since the last UI refresh"
         * queries.
         */
        val sinceEpochMs: Long? = null,
        // ---- snapshots filter ----
        /**
         * Drop snapshots captured strictly earlier than `now - maxAgeDays`.
         * `select=snapshots` only. Null = no age filter. Mirrors the flag
         * the old `list_project_snapshots` carried for long-lived projects
         * that only care about recent snapshots.
         */
        val maxAgeDays: Int? = null,
        // ---- clips_for_asset filter (required for that select) ----
        /** Asset id to look up. Required for `select=clips_for_asset`; rejected elsewhere. */
        val assetId: String? = null,
        // ---- clips_for_source filter (required for that select) ----
        /** Source node id to look up. Required for `select=clips_for_source`; rejected elsewhere. */
        val sourceNodeId: String? = null,
        // ---- clip drill-down (required for that select) ----
        /** Clip id for drill-down. Required for `select=clip`; rejected elsewhere. */
        val clipId: String? = null,
        // ---- lockfile_entry drill-down (required for that select) ----
        /** Lockfile entry inputHash. Required for `select=lockfile_entry`; rejected elsewhere. */
        val inputHash: String? = null,
        // ---- timeline_diff inputs (required for that select) ----
        /**
         * Snapshot id for the "from" side of a timeline diff. Null means
         * "current live state of the project". Required for `select=timeline_diff`;
         * rejected elsewhere. At least one of [fromSnapshotId] / [toSnapshotId]
         * must be non-null — diffing current-vs-current would always be
         * identical and is almost always a usage error.
         */
        val fromSnapshotId: String? = null,
        /**
         * Snapshot id for the "to" side of a timeline diff. Null means
         * "current live state of the project". Required for `select=timeline_diff`;
         * rejected elsewhere.
         */
        val toSnapshotId: String? = null,
        // ---- common ----
        /** Sort key — interpretation depends on [select]. See [helpText]. */
        val sortBy: String? = null,
        /** Post-filter cap. Default 100, clamped to `[1, 500]`. */
        val limit: Int? = null,
        /** Skip the first N rows after filter+sort. Default 0. */
        val offset: Int? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        /** Echo of the (normalised) select used to produce [rows]. */
        val select: String,
        /** Count of matches after filters, before offset/limit. */
        val total: Int,
        /** Count of rows in [rows]. Lower than [total] when offset/limit hide rows. */
        val returned: Int,
        /** Select-specific row objects, serialised via [JsonConfig.default]. */
        val rows: JsonArray,
    )

    override val id: String = "project_query"
    override val helpText: String = PROJECT_QUERY_HELP_TEXT
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")
    override val applicability: ToolApplicability = ToolApplicability.RequiresProjectBinding

    // Schema is long (~170 lines of per-field `putJsonObject`); lives in
    // `ProjectQueryToolSchema.kt` so this file stays focused on dispatch
    // + row types. The sibling file's `PROJECT_QUERY_INPUT_SCHEMA` is the
    // only thing that has to change when you add a new filter field —
    // plus the KDoc on the matching `Input` property here.
    override val inputSchema: JsonObject = PROJECT_QUERY_INPUT_SCHEMA

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = input.select.trim().lowercase()
        if (select !in ALL_SELECTS) {
            error("select must be one of ${ALL_SELECTS.joinToString(", ")} (got '${input.select}')")
        }
        rejectIncompatibleProjectQueryFilters(select, input)

        val pid = ctx.resolveProjectId(input.projectId)
        val project = projects.get(pid)
            ?: error("Project ${pid.value} not found")

        val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)
        val offset = (input.offset ?: 0).coerceAtLeast(0)

        return when (select) {
            SELECT_TRACKS -> runTracksQuery(project, input, limit, offset)
            SELECT_TIMELINE_CLIPS -> runTimelineClipsQuery(project, input, limit, offset)
            SELECT_ASSETS -> runAssetsQuery(project, input, limit, offset)
            SELECT_TRANSITIONS -> runTransitionsQuery(project, input, limit, offset)
            SELECT_LOCKFILE_ENTRIES -> runLockfileEntriesQuery(project, input, limit, offset)
            SELECT_CLIPS_FOR_ASSET -> runClipsForAssetQuery(project, input, limit, offset)
            SELECT_CLIPS_FOR_SOURCE -> runClipsForSourceQuery(project, input, limit, offset)
            SELECT_CLIP -> runClipDetailQuery(project, input)
            SELECT_LOCKFILE_ENTRY -> runLockfileEntryDetailQuery(project, input)
            SELECT_PROJECT_METADATA -> runProjectMetadataQuery(project, projects, input)
            SELECT_CONSISTENCY_PROPAGATION -> runConsistencyPropagationQuery(project, input, limit, offset)
            SELECT_SPEND -> runSpendQuery(project)
            SELECT_SNAPSHOTS -> runSnapshotsQuery(project, input, clock)
            SELECT_TIMELINE_DIFF -> runTimelineDiffQuery(project, input)
            else -> error("unreachable — select validated above: '$select'")
        }
    }

    companion object {
        const val SELECT_TRACKS = "tracks"
        const val SELECT_TIMELINE_CLIPS = "timeline_clips"
        const val SELECT_ASSETS = "assets"
        const val SELECT_TRANSITIONS = "transitions"
        const val SELECT_LOCKFILE_ENTRIES = "lockfile_entries"
        const val SELECT_CLIPS_FOR_ASSET = "clips_for_asset"
        const val SELECT_CLIPS_FOR_SOURCE = "clips_for_source"
        const val SELECT_CLIP = "clip"
        const val SELECT_LOCKFILE_ENTRY = "lockfile_entry"
        const val SELECT_PROJECT_METADATA = "project_metadata"
        const val SELECT_CONSISTENCY_PROPAGATION = "consistency_propagation"
        const val SELECT_SPEND = "spend"
        const val SELECT_SNAPSHOTS = "snapshots"
        const val SELECT_TIMELINE_DIFF = "timeline_diff"
        private val ALL_SELECTS = setOf(
            SELECT_TRACKS, SELECT_TIMELINE_CLIPS, SELECT_ASSETS,
            SELECT_TRANSITIONS, SELECT_LOCKFILE_ENTRIES,
            SELECT_CLIPS_FOR_ASSET, SELECT_CLIPS_FOR_SOURCE,
            SELECT_CLIP, SELECT_LOCKFILE_ENTRY, SELECT_PROJECT_METADATA,
            SELECT_CONSISTENCY_PROPAGATION, SELECT_SPEND, SELECT_SNAPSHOTS,
            SELECT_TIMELINE_DIFF,
        )

        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 500
    }
}
