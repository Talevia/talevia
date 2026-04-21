package io.talevia.core.tool.builtin.project

import io.talevia.core.JsonConfig
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.query.runAssetsQuery
import io.talevia.core.tool.builtin.project.query.runClipsForAssetQuery
import io.talevia.core.tool.builtin.project.query.runClipsForSourceQuery
import io.talevia.core.tool.builtin.project.query.runLockfileEntriesQuery
import io.talevia.core.tool.builtin.project.query.runTimelineClipsQuery
import io.talevia.core.tool.builtin.project.query.runTracksQuery
import io.talevia.core.tool.builtin.project.query.runTransitionsQuery
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
        // ---- clips_for_asset filter (required for that select) ----
        /** Asset id to look up. Required for `select=clips_for_asset`; rejected elsewhere. */
        val assetId: String? = null,
        // ---- clips_for_source filter (required for that select) ----
        /** Source node id to look up. Required for `select=clips_for_source`; rejected elsewhere. */
        val sourceNodeId: String? = null,
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

    override val id: String = "project_query"
    override val helpText: String =
        "Unified read-only query over a project (replaces list_tracks / list_timeline_clips / " +
            "list_assets / list_transitions / list_lockfile_entries / list_clips_bound_to_asset / " +
            "list_clips_for_source). Pick one `select`:\n" +
            "  • tracks — filter: trackKind, onlyNonEmpty. sortBy: index (default) | clipCount | span.\n" +
            "  • timeline_clips — filter: trackKind, trackId, fromSeconds, toSeconds, onlySourceBound, " +
            "onlyPinned. sortBy: startSeconds (default) | durationSeconds.\n" +
            "  • assets — filter: kind (video|audio|image|all), onlyUnused, onlyReferenced. sortBy: " +
            "insertion (default) | duration | duration-asc | id.\n" +
            "  • transitions — filter: onlyOrphaned. Chronological order.\n" +
            "  • lockfile_entries — filter: toolId (e.g. generate_image), onlyPinned. Most-recent first.\n" +
            "  • clips_for_asset — required: assetId. Every clip referencing the asset.\n" +
            "  • clips_for_source — required: sourceNodeId. Every clip bound to that source node " +
            "(directly or transitively).\n" +
            "Common: limit (default 100, clamped 1..500), offset (default 0). Setting a filter " +
            "that doesn't apply to the chosen select fails loud so typos surface instead of silently " +
            "returning an empty list."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to use the session's current project (set via switch_project).",
                )
            }
            putJsonObject("select") {
                put("type", "string")
                put(
                    "description",
                    "What to query: tracks | timeline_clips | assets | transitions | " +
                        "lockfile_entries | clips_for_asset | clips_for_source (case-insensitive).",
                )
            }
            putJsonObject("trackKind") {
                put("type", "string")
                put(
                    "description",
                    "video|audio|subtitle|effect (case-insensitive). Applies to " +
                        "select=tracks and select=timeline_clips.",
                )
            }
            putJsonObject("trackId") {
                put("type", "string")
                put("description", "Exact track id. select=timeline_clips only.")
            }
            putJsonObject("fromSeconds") {
                put("type", "number")
                put("description", "Time window lower bound (seconds). select=timeline_clips only.")
            }
            putJsonObject("toSeconds") {
                put("type", "number")
                put("description", "Time window upper bound (seconds). select=timeline_clips only.")
            }
            putJsonObject("onlyNonEmpty") {
                put("type", "boolean")
                put("description", "Skip tracks whose clips list is empty. select=tracks only.")
            }
            putJsonObject("onlySourceBound") {
                put("type", "boolean")
                put(
                    "description",
                    "Keep only clips with non-empty sourceBinding (AIGC-derived). " +
                        "select=timeline_clips only.",
                )
            }
            putJsonObject("onlyPinned") {
                put("type", "boolean")
                put(
                    "description",
                    "Pin filter. select=timeline_clips: true=clips pinned / false=clips NOT pinned. " +
                        "select=lockfile_entries: true=only pinned entries.",
                )
            }
            putJsonObject("kind") {
                put("type", "string")
                put("description", "video|audio|image|all (default all). select=assets only.")
            }
            putJsonObject("onlyUnused") {
                put("type", "boolean")
                put("description", "Keep only assets with zero clip references. select=assets only.")
            }
            putJsonObject("onlyReferenced") {
                put("type", "boolean")
                put(
                    "description",
                    "true = keep only assets referenced by clip/filter/lockfile. " +
                        "false = keep only un-referenced (safe-to-delete). select=assets only.",
                )
            }
            putJsonObject("onlyOrphaned") {
                put("type", "boolean")
                put(
                    "description",
                    "Keep only transitions whose flanking video clips are both missing " +
                        "(GC candidates). select=transitions only.",
                )
            }
            putJsonObject("toolId") {
                put("type", "string")
                put(
                    "description",
                    "Filter lockfile entries by producing tool id (e.g. generate_image, " +
                        "synthesize_speech). select=lockfile_entries only.",
                )
            }
            putJsonObject("assetId") {
                put("type", "string")
                put(
                    "description",
                    "Required for select=clips_for_asset. Asset id to look up clip references for.",
                )
            }
            putJsonObject("sourceNodeId") {
                put("type", "string")
                put(
                    "description",
                    "Required for select=clips_for_source. Source node id to find bound clips for.",
                )
            }
            putJsonObject("sortBy") {
                put("type", "string")
                put(
                    "description",
                    "Ordering applied before offset+limit. Accepted values depend on select; " +
                        "see the tool description.",
                )
            }
            putJsonObject("limit") {
                put("type", "integer")
                put(
                    "description",
                    "Max rows (default 100, clamped to [1, 500]). Applied after filter+sort+offset.",
                )
            }
            putJsonObject("offset") {
                put("type", "integer")
                put("description", "Skip N rows after filter+sort (default 0).")
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("select"))),
        )
        put("additionalProperties", false)
    }

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
            else -> error("unreachable — select validated above: '$select'")
        }
    }

    private fun rejectIncompatibleFilters(select: String, input: Input) {
        // Each filter field belongs to a specific select; setting it on the wrong
        // select usually means the LLM typed the wrong field name — fail loud so
        // the typo surfaces instead of silently returning a generic list.
        val misapplied = buildList {
            if (select != SELECT_TRACKS && input.onlyNonEmpty != null) {
                add("onlyNonEmpty (select=tracks only)")
            }
            if (select != SELECT_TIMELINE_CLIPS) {
                if (input.trackId != null) add("trackId (select=timeline_clips only)")
                if (input.fromSeconds != null) add("fromSeconds (select=timeline_clips only)")
                if (input.toSeconds != null) add("toSeconds (select=timeline_clips only)")
                if (input.onlySourceBound != null) add("onlySourceBound (select=timeline_clips only)")
            }
            // onlyPinned is valid for timeline_clips AND lockfile_entries; reject elsewhere.
            if (select != SELECT_TIMELINE_CLIPS && select != SELECT_LOCKFILE_ENTRIES && input.onlyPinned != null) {
                add("onlyPinned (select=timeline_clips or lockfile_entries only)")
            }
            if (select != SELECT_ASSETS) {
                if (input.kind != null) add("kind (select=assets only)")
                if (input.onlyUnused != null) add("onlyUnused (select=assets only)")
                if (input.onlyReferenced != null) add("onlyReferenced (select=assets only)")
            }
            if (select == SELECT_ASSETS && input.trackKind != null) {
                add("trackKind (select=tracks or timeline_clips only)")
            }
            if (select != SELECT_TRANSITIONS && input.onlyOrphaned != null) {
                add("onlyOrphaned (select=transitions only)")
            }
            if (select != SELECT_LOCKFILE_ENTRIES && input.toolId != null) {
                add("toolId (select=lockfile_entries only)")
            }
            if (select != SELECT_CLIPS_FOR_ASSET && input.assetId != null) {
                add("assetId (select=clips_for_asset only)")
            }
            if (select != SELECT_CLIPS_FOR_SOURCE && input.sourceNodeId != null) {
                add("sourceNodeId (select=clips_for_source only)")
            }
        }
        if (misapplied.isNotEmpty()) {
            error(
                "The following filter fields do not apply to select='$select': " +
                    misapplied.joinToString(", "),
            )
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
        private val ALL_SELECTS = setOf(
            SELECT_TRACKS, SELECT_TIMELINE_CLIPS, SELECT_ASSETS,
            SELECT_TRANSITIONS, SELECT_LOCKFILE_ENTRIES,
            SELECT_CLIPS_FOR_ASSET, SELECT_CLIPS_FOR_SOURCE,
        )

        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 500
    }
}
