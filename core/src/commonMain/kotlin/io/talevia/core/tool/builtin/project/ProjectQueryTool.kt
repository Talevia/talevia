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
 * Select lane history:
 *  - `tracks` / `timeline_clips` / `assets` — original consolidation of
 *    `list_tracks` / `list_timeline_clips` / `list_assets`
 *    (`unify-project-query` decision).
 *  - `transitions` / `lockfile_entries` / `clips_for_asset` /
 *    `clips_for_source` — folded in by the
 *    `debt-consolidate-project-list-queries` cycle, absorbing
 *    `list_transitions` / `list_lockfile_entries` /
 *    `list_clips_bound_to_asset` / `list_clips_for_source`.
 *
 * Stale-clip discovery stays in [FindStaleClipsTool] — its DAG traversal
 * is aggregate, not pure projection.
 *
 * Output is uniform: `{projectId, select, total, returned, rows}` where
 * `rows` is a [JsonArray] whose shape depends on `select`. Consumers
 * inspect the echoed `select` and decode with the matching row
 * serializer; wire encoding is the canonical [JsonConfig.default].
 *
 * Implementation is split per-select across
 * `core/tool/builtin/project/query/`. This class stays a thin dispatcher:
 * schema + validation + select routing. Row data classes are kept here
 * because their serializers are part of the public API (consumers call
 * `ProjectQueryTool.TrackRow.serializer()` etc).
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

    @Serializable data class TrackRow(
        val trackId: String,
        val trackKind: String,
        val index: Int,
        val clipCount: Int,
        val isEmpty: Boolean,
        val firstClipStartSeconds: Double? = null,
        val lastClipEndSeconds: Double? = null,
        val spanSeconds: Double? = null,
        /** Stamped by [io.talevia.core.domain.FileProjectStore]; null on pre-recency blobs. */
        val updatedAtEpochMs: Long? = null,
    )

    @Serializable data class ClipRow(
        val clipId: String,
        val trackId: String,
        val trackKind: String,
        val clipKind: String,
        val startSeconds: Double,
        val durationSeconds: Double,
        val endSeconds: Double,
        val assetId: String? = null,
        val sourceStartSeconds: Double? = null,
        val sourceDurationSeconds: Double? = null,
        val filterCount: Int = 0,
        val volume: Float? = null,
        val fadeInSeconds: Float? = null,
        val fadeOutSeconds: Float? = null,
        val textPreview: String? = null,
        val sourceBindingNodeIds: List<String> = emptyList(),
        /** Stamped by [io.talevia.core.domain.FileProjectStore]; null on pre-recency blobs. */
        val updatedAtEpochMs: Long? = null,
    )

    @Serializable data class AssetRow(
        val assetId: String,
        val kind: String,
        val durationSeconds: Double,
        val width: Int? = null,
        val height: Int? = null,
        val hasVideoTrack: Boolean,
        val hasAudioTrack: Boolean,
        val sourceKind: String,
        val inUseByClips: Int,
        /** Stamped by [io.talevia.core.domain.FileProjectStore]; null on pre-recency blobs. */
        val updatedAtEpochMs: Long? = null,
    )

    @Serializable data class TransitionRow(
        val transitionClipId: String,
        val trackId: String,
        val transitionName: String,
        val startSeconds: Double,
        val durationSeconds: Double,
        val endSeconds: Double,
        val fromClipId: String? = null,
        val toClipId: String? = null,
        val orphaned: Boolean = false,
    )

    @Serializable data class LockfileEntryRow(
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        val providerId: String,
        val modelId: String,
        val seed: Long,
        val createdAtEpochMs: Long,
        val sourceBindingIds: List<String> = emptyList(),
        val pinned: Boolean,
    )

    @Serializable data class ClipForAssetRow(
        val clipId: String,
        val trackId: String,
        /** `"video"` or `"audio"`. Text clips never match (no asset). */
        val kind: String,
        val startSeconds: Double,
        val durationSeconds: Double,
    )

    @Serializable data class ClipForSourceRow(
        val clipId: String,
        val trackId: String,
        val assetId: String? = null,
        val directlyBound: Boolean,
        val boundVia: List<String> = emptyList(),
    )

    /**
     * `select=consistency_propagation` — verifies that a consistency-style
     * source node (character_ref / style_bible / brand_palette / …)
     * actually reached the prompts of clips bound to it, by substring-
     * matching the node's body string values against each clip's lockfile
     * entry `baseInputs.prompt`. Supports VISION §5.5 "did my
     * character_ref really influence shot-3?" — a provider can nominally
     * see a binding but still not incorporate it.
     *
     * Row per bound clip. `aigcEntryFound=false` → clip is not AIGC-
     * backed or its lockfile entry is missing (nothing to audit).
     * `keywordsInBody` is a deterministic slice of string values from
     * the node's top-level body JSON; `keywordsMatchedInPrompt` is the
     * subset that appear (case-insensitive substring) in
     * `baseInputs.prompt`. `promptContainsKeywords` is convenience.
     */
    @Serializable data class ConsistencyPropagationRow(
        val clipId: String,
        val trackId: String,
        val assetId: String? = null,
        val directlyBound: Boolean,
        val boundVia: List<String> = emptyList(),
        val aigcEntryFound: Boolean,
        val lockfileInputHash: String? = null,
        val aigcToolId: String? = null,
        val keywordsInBody: List<String> = emptyList(),
        val keywordsMatchedInPrompt: List<String> = emptyList(),
        val promptContainsKeywords: Boolean = false,
    )

    // -----------------------------------------------------------------
    // Single-row drill-down rows — folded from the deleted describe_*
    // tools (debt-consolidate-project-describe-queries cycle).

    @Serializable data class ClipDetailTimeRange(
        val startMs: Long,
        val durationMs: Long,
        val endMs: Long,
    )

    @Serializable data class ClipDetailLockfileRef(
        val inputHash: String,
        val toolId: String,
        val pinned: Boolean,
        val currentlyStale: Boolean,
        val driftedSourceNodeIds: List<String> = emptyList(),
    )

    /**
     * `select=clip` — single-row drill-down replacing the deleted
     * `describe_clip` tool. Returns a rich Clip descriptor (timeRange,
     * sourceRange, transforms, sourceBinding, per-kind fields, derived
     * lockfile ref).
     */
    @Serializable data class ClipDetailRow(
        val clipId: String,
        val trackId: String,
        /** `"video"` | `"audio"` | `"text"`. */
        val clipType: String,
        val timeRange: ClipDetailTimeRange,
        val sourceRange: ClipDetailTimeRange? = null,
        val sourceBindingIds: List<String> = emptyList(),
        val transforms: List<io.talevia.core.domain.Transform> = emptyList(),
        val assetId: String? = null,
        val filters: List<io.talevia.core.domain.Filter>? = null,
        val volume: Float? = null,
        val fadeInSeconds: Float? = null,
        val fadeOutSeconds: Float? = null,
        val text: String? = null,
        val textStyle: io.talevia.core.domain.TextStyle? = null,
        val lockfile: ClipDetailLockfileRef? = null,
    )

    @Serializable data class LockfileEntryProvenance(
        val providerId: String,
        val modelId: String,
        val modelVersion: String? = null,
        val seed: Long,
        val createdAtEpochMs: Long,
    )

    @Serializable data class LockfileEntryDriftedNode(
        val nodeId: String,
        val snapshotContentHash: String,
        /** Null when the bound node has since been deleted from the project. */
        val currentContentHash: String? = null,
    )

    @Serializable data class LockfileEntryClipRef(
        val clipId: String,
        val trackId: String,
        val clipType: String,
    )

    /**
     * `select=lockfile_entry` — single-row drill-down replacing the
     * deleted `describe_lockfile_entry` tool. Looks up by `inputHash`;
     * returns full provenance, source-binding snapshot / drift state,
     * baseInputs, clip references on the current timeline.
     */
    @Serializable data class LockfileEntryDetailRow(
        val inputHash: String,
        val toolId: String,
        val assetId: String,
        val pinned: Boolean,
        val provenance: LockfileEntryProvenance,
        val sourceBindingIds: List<String> = emptyList(),
        val sourceContentHashes: Map<String, String> = emptyMap(),
        val baseInputs: JsonObject,
        val baseInputsEmpty: Boolean,
        val currentlyStale: Boolean,
        val driftedNodes: List<LockfileEntryDriftedNode> = emptyList(),
        val clipReferences: List<LockfileEntryClipRef> = emptyList(),
        /**
         * The fully-expanded prompt the AIGC tool sent to the provider,
         * after consistency-fold (character_ref / style_bible etc.
         * prepended). Null for tools without a prompt concept (upscale,
         * synthesize_speech) and for pre-cycle-7 legacy entries.
         */
        val resolvedPrompt: String? = null,
        /**
         * Session message id whose tool call produced this entry. Lets
         * the audit path trace "which prompt generated this image?"
         * without grepping session parts. Null for pre-provenance-cycle
         * legacy entries. VISION §5.2.
         */
        val originatingMessageId: String? = null,
    )

    @Serializable data class ProjectMetadataProfile(
        val resolutionWidth: Int,
        val resolutionHeight: Int,
        val frameRate: Int,
        val videoCodec: String,
        val audioCodec: String,
    )

    @Serializable data class ProjectMetadataSnapshotSummary(
        val id: String,
        val label: String,
        val capturedAtEpochMs: Long,
    )

    /**
     * `select=project_metadata` — single-row drill-down replacing the
     * deleted `describe_project` tool. Compact aggregate across every
     * axis: timeline, tracks-by-kind, clips-by-kind, source-nodes-by-
     * kind, lockfile-by-tool, snapshots, plus a pre-rendered
     * `summaryText` the LLM can quote verbatim.
     */
    /**
     * `select=snapshots` — enumerate saved snapshots on the project,
     * newest-first (by `capturedAtEpochMs`). Replaces the deleted
     * `list_project_snapshots` tool (debt-fold-list-project-snapshots
     * cycle). Filters: [Input.maxAgeDays] + [Input.limit] (default 50,
     * clamped 1..500 for this select, same as the old tool). Returns
     * compact summaries — the full captured `Project` payload is not
     * surfaced here; callers that need the live state still use
     * `get_project_state` / `restore_project_snapshot`.
     */
    @Serializable data class SnapshotRow(
        val snapshotId: String,
        val label: String,
        val capturedAtEpochMs: Long,
        val clipCount: Int,
        val trackCount: Int,
        val assetCount: Int,
    )

    /**
     * `select=spend` — single-row aggregate of AIGC spend across the
     * project's lockfile. [costCents] sums on every entry whose stamped
     * `costCents` is non-null; entries with `null` cost (no pricing rule)
     * are counted in [unknownCostEntries] and NOT rolled into
     * [totalCostCents] — silent zero-coalescing would misrepresent spend
     * as cheaper than it is. [byTool] / [bySession] break the total down
     * so dashboards can answer "which tool / session ate the budget".
     */
    @Serializable data class SpendSummaryRow(
        val projectId: String,
        val totalCostCents: Long,
        val entryCount: Int,
        val knownCostEntries: Int,
        val unknownCostEntries: Int,
        val byTool: Map<String, Long> = emptyMap(),
        val bySession: Map<String, Long> = emptyMap(),
        val unknownByTool: Map<String, Int> = emptyMap(),
    )

    @Serializable data class ProjectMetadataRow(
        val title: String,
        val createdAtEpochMs: Long,
        val updatedAtEpochMs: Long,
        val timelineDurationSeconds: Double,
        val trackCount: Int,
        val tracksByKind: Map<String, Int>,
        val clipCount: Int,
        val clipsByKind: Map<String, Int>,
        val assetCount: Int,
        val sourceNodeCount: Int,
        val sourceNodesByKind: Map<String, Int>,
        val lockfileEntryCount: Int,
        val lockfileByTool: Map<String, Int>,
        val snapshotCount: Int,
        val recentSnapshots: List<ProjectMetadataSnapshotSummary> = emptyList(),
        val outputProfile: ProjectMetadataProfile? = null,
        /** Pre-rendered ~300-char human summary, LLM-quotable verbatim. */
        val summaryText: String,
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
        rejectIncompatibleFilters(select, input)

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
            else -> error("unreachable — select validated above: '$select'")
        }
    }

    private fun rejectIncompatibleFilters(select: String, input: Input) =
        rejectIncompatibleProjectQueryFilters(select, input)

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
        private val ALL_SELECTS = setOf(
            SELECT_TRACKS, SELECT_TIMELINE_CLIPS, SELECT_ASSETS,
            SELECT_TRANSITIONS, SELECT_LOCKFILE_ENTRIES,
            SELECT_CLIPS_FOR_ASSET, SELECT_CLIPS_FOR_SOURCE,
            SELECT_CLIP, SELECT_LOCKFILE_ENTRY, SELECT_PROJECT_METADATA,
            SELECT_CONSISTENCY_PROPAGATION, SELECT_SPEND, SELECT_SNAPSHOTS,
        )

        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 500
    }
}
