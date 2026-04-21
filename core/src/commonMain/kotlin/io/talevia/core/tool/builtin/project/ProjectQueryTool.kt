package io.talevia.core.tool.builtin.project

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.project.query.runAssetsQuery
import io.talevia.core.tool.builtin.project.query.runTimelineClipsQuery
import io.talevia.core.tool.builtin.project.query.runTracksQuery
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
 * This iteration covers `tracks`, `timeline_clips`, `assets`. Later cycles
 * can absorb `list_transitions`, `find_pinned_clips`,
 * `find_unreferenced_assets`, etc. Stale-clip discovery stays in
 * [FindStaleClipsTool] — its DAG traversal is aggregate, not projection.
 *
 * Output is uniform: `{projectId, select, total, returned, rows}` where
 * `rows` is a [JsonArray] whose shape depends on `select`. Consumers
 * inspect the echoed `select` and decode with the matching row
 * serializer; wire encoding is the canonical [JsonConfig.default].
 *
 * Implementation is split per-select across
 * `core/tool/builtin/project/query/` — `runTracksQuery`,
 * `runTimelineClipsQuery`, `runAssetsQuery`. This class stays a thin
 * dispatcher: schema + validation + select routing. Row data classes are
 * kept here because their serializers are part of the public API
 * (consumers call `ProjectQueryTool.TrackRow.serializer()` etc).
 */
class ProjectQueryTool(
    private val projects: ProjectStore,
) : Tool<ProjectQueryTool.Input, ProjectQueryTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** One of [SELECT_TRACKS] / [SELECT_TIMELINE_CLIPS] / [SELECT_ASSETS] (case-insensitive). */
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
        // ---- assets filter ----
        /** `"video"` | `"audio"` | `"image"` | `"all"`. `select=assets` only. */
        val kind: String? = null,
        /** Keep only assets referenced by zero clips. `select=assets` only. */
        val onlyUnused: Boolean? = null,
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

    override val id: String = "project_query"
    override val helpText: String =
        "Unified read-only query over a project (replaces list_tracks / list_timeline_clips / " +
            "list_assets). Pick one `select`:\n" +
            "  • tracks — rows: {trackId, trackKind, index, clipCount, isEmpty, " +
            "firstClipStartSeconds, lastClipEndSeconds, spanSeconds}. " +
            "filter: trackKind, onlyNonEmpty. sortBy: index (default) | clipCount | span.\n" +
            "  • timeline_clips — rows: {clipId, trackId, trackKind, clipKind, " +
            "startSeconds, durationSeconds, endSeconds, assetId, sourceStartSeconds, " +
            "sourceDurationSeconds, filterCount, volume, fadeInSeconds, fadeOutSeconds, " +
            "textPreview, sourceBindingNodeIds}. filter: trackKind, trackId, fromSeconds, " +
            "toSeconds, onlySourceBound. sortBy: startSeconds (default) | durationSeconds.\n" +
            "  • assets — rows: {assetId, kind, durationSeconds, width, height, " +
            "hasVideoTrack, hasAudioTrack, sourceKind, inUseByClips}. filter: kind " +
            "(video|audio|image|all), onlyUnused. sortBy: insertion (default) | duration | " +
            "duration-asc | id.\n" +
            "Common: limit (default 100, clamped 1..500), offset (default 0). Setting a " +
            "filter that doesn't apply to the chosen select fails loud so typos surface " +
            "instead of silently returning an empty list."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("select") {
                put("type", "string")
                put(
                    "description",
                    "What to query: tracks | timeline_clips | assets (case-insensitive).",
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
            putJsonObject("kind") {
                put("type", "string")
                put("description", "video|audio|image|all (default all). select=assets only.")
            }
            putJsonObject("onlyUnused") {
                put("type", "boolean")
                put("description", "Keep only assets with zero clip references. select=assets only.")
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
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("select"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val select = input.select.trim().lowercase()
        if (select !in ALL_SELECTS) {
            error("select must be one of ${ALL_SELECTS.joinToString(", ")} (got '${input.select}')")
        }
        rejectIncompatibleFilters(select, input)

        val project = projects.get(ProjectId(input.projectId))
            ?: error("Project ${input.projectId} not found")

        val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)
        val offset = (input.offset ?: 0).coerceAtLeast(0)

        return when (select) {
            SELECT_TRACKS -> runTracksQuery(project, input, limit, offset)
            SELECT_TIMELINE_CLIPS -> runTimelineClipsQuery(project, input, limit, offset)
            SELECT_ASSETS -> runAssetsQuery(project, input, limit, offset)
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
            if (select != SELECT_ASSETS) {
                if (input.kind != null) add("kind (select=assets only)")
                if (input.onlyUnused != null) add("onlyUnused (select=assets only)")
            }
            if (select == SELECT_ASSETS && input.trackKind != null) {
                add("trackKind (select=tracks or timeline_clips only)")
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
        private val ALL_SELECTS = setOf(SELECT_TRACKS, SELECT_TIMELINE_CLIPS, SELECT_ASSETS)

        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 500
    }
}
