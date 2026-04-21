package io.talevia.core.tool.builtin.project

import io.talevia.core.JsonConfig
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.MediaAsset
import io.talevia.core.domain.MediaSource
import io.talevia.core.domain.Project
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Unified read-only query primitive over a [Project]. One tool spec in the
 * LLM's context instead of one tool per "kind of thing you can list" —
 * modelled on codebase-grep's `(select, filter, sort, limit)` shape.
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
            SELECT_TRACKS -> runTracks(project, input, limit, offset)
            SELECT_TIMELINE_CLIPS -> runTimelineClips(project, input, limit, offset)
            SELECT_ASSETS -> runAssets(project, input, limit, offset)
            else -> error("unreachable — select validated above: '$select'")
        }
    }

    // -----------------------------------------------------------------
    // select = tracks

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

    private fun runTracks(project: Project, input: Input, limit: Int, offset: Int): ToolResult<Output> {
        val kindFilter = input.trackKind?.trim()?.lowercase()
        if (kindFilter != null && kindFilter !in VALID_TRACK_KINDS) {
            error("trackKind must be one of ${VALID_TRACK_KINDS.joinToString(", ")} (got '${input.trackKind}')")
        }
        val sortBy = input.sortBy?.trim()?.lowercase()
        if (sortBy != null && sortBy !in TRACK_SORTS) {
            error("sortBy for select=tracks must be one of ${TRACK_SORTS.joinToString(", ")} (got '${input.sortBy}')")
        }

        val filtered = project.timeline.tracks.withIndex().mapNotNull { (index, track) ->
            val kind = trackKindOf(track)
            if (kindFilter != null && kind != kindFilter) return@mapNotNull null
            if (input.onlyNonEmpty == true && track.clips.isEmpty()) return@mapNotNull null
            buildTrackRow(track, index, kind)
        }

        val sorted = when (sortBy) {
            null, "index" -> filtered
            "clipcount" -> filtered.sortedByDescending { it.clipCount }
            "span" -> filtered.sortedByDescending { it.spanSeconds ?: 0.0 }
            else -> error("unreachable")
        }

        val page = sorted.drop(offset).take(limit)
        val rows = encodeRows(ListSerializer(TrackRow.serializer()), page)
        val llmBody = when {
            page.isEmpty() -> "No tracks match the given filter."
            else -> page.joinToString("\n") { r ->
                val span = if (r.isEmpty) {
                    "empty"
                } else {
                    "${r.clipCount} clips, ${r.firstClipStartSeconds}s..${r.lastClipEndSeconds}s"
                }
                "- #${r.index} [${r.trackKind}/${r.trackId}] $span"
            }
        }
        val scope = buildList {
            kindFilter?.let { add("kind=$it") }
            if (input.onlyNonEmpty == true) add("non-empty")
            sortBy?.let { add("sort=$it") }
        }.joinToString(", ").let { if (it.isEmpty()) "" else ", $it" }
        return ToolResult(
            title = "project_query tracks (${page.size}/${filtered.size})",
            outputForLlm = "Project ${project.id.value}: ${page.size}/${filtered.size} tracks$scope.\n$llmBody",
            data = Output(project.id.value, SELECT_TRACKS, filtered.size, page.size, rows),
        )
    }

    private fun buildTrackRow(track: Track, index: Int, kind: String): TrackRow {
        val clips = track.clips
        if (clips.isEmpty()) {
            return TrackRow(
                trackId = track.id.value,
                trackKind = kind,
                index = index,
                clipCount = 0,
                isEmpty = true,
            )
        }
        val firstStart = clips.minOf { it.timeRange.start }
        val lastEnd = clips.maxOf { it.timeRange.end }
        return TrackRow(
            trackId = track.id.value,
            trackKind = kind,
            index = index,
            clipCount = clips.size,
            isEmpty = false,
            firstClipStartSeconds = firstStart.toSecondsDouble(),
            lastClipEndSeconds = lastEnd.toSecondsDouble(),
            spanSeconds = (lastEnd - firstStart).toSecondsDouble(),
        )
    }

    // -----------------------------------------------------------------
    // select = timeline_clips

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

    private fun runTimelineClips(project: Project, input: Input, limit: Int, offset: Int): ToolResult<Output> {
        val kindFilter = input.trackKind?.trim()?.lowercase()
        if (kindFilter != null && kindFilter !in VALID_TRACK_KINDS) {
            error("trackKind must be one of ${VALID_TRACK_KINDS.joinToString(", ")} (got '${input.trackKind}')")
        }
        val sortBy = input.sortBy?.trim()?.lowercase()
        if (sortBy != null && sortBy !in CLIP_SORTS) {
            error(
                "sortBy for select=timeline_clips must be one of ${CLIP_SORTS.joinToString(", ")} " +
                    "(got '${input.sortBy}')",
            )
        }
        val fromDuration = input.fromSeconds?.coerceAtLeast(0.0)?.seconds
        val toDuration = input.toSeconds?.coerceAtLeast(0.0)?.seconds

        val filtered = mutableListOf<ClipRow>()
        for (track in project.timeline.tracks) {
            val trackKind = trackKindOf(track)
            if (kindFilter != null && trackKind != kindFilter) continue
            if (input.trackId != null && track.id.value != input.trackId) continue
            for (clip in track.clips.sortedBy { it.timeRange.start }) {
                if (fromDuration != null && clip.timeRange.end < fromDuration) continue
                if (toDuration != null && clip.timeRange.start > toDuration) continue
                if (input.onlySourceBound == true && clip.sourceBinding.isEmpty()) continue
                filtered += buildClipRow(clip, track, trackKind)
            }
        }
        val sorted = when (sortBy) {
            null, "startseconds" -> filtered
            "durationseconds" -> filtered.sortedByDescending { it.durationSeconds }
            else -> error("unreachable")
        }

        val page = sorted.drop(offset).take(limit)
        val rows = encodeRows(ListSerializer(ClipRow.serializer()), page)
        val body = if (page.isEmpty()) {
            "No clips match the given filters."
        } else {
            page.joinToString("\n") { c ->
                val extra = when (c.clipKind) {
                    "video" -> c.assetId?.let { " asset=$it" }.orEmpty() +
                        if (c.filterCount > 0) " filters=${c.filterCount}" else ""
                    "audio" -> c.assetId?.let { " asset=$it" }.orEmpty() +
                        (c.volume?.let { " vol=$it" }.orEmpty())
                    "text" -> c.textPreview?.let {
                        " text=\"${it.take(40)}${if (it.length > 40) "…" else ""}\""
                    }.orEmpty()
                    else -> ""
                }
                val binding = if (c.sourceBindingNodeIds.isEmpty()) {
                    ""
                } else {
                    " bindings=${c.sourceBindingNodeIds.joinToString(",")}"
                }
                "- [${c.trackKind}/${c.trackId}] ${c.clipId} @ ${c.startSeconds}s +${c.durationSeconds}s$extra$binding"
            }
        }
        val hiddenByPage = filtered.size - page.size
        val tail = if (hiddenByPage > 0) "\n… ($hiddenByPage more not shown)" else ""
        return ToolResult(
            title = "project_query timeline_clips (${page.size}/${filtered.size})",
            outputForLlm = body + tail,
            data = Output(project.id.value, SELECT_TIMELINE_CLIPS, filtered.size, page.size, rows),
        )
    }

    private fun buildClipRow(clip: Clip, track: Track, trackKind: String): ClipRow {
        val start = clip.timeRange.start.toSecondsDouble()
        val dur = clip.timeRange.duration.toSecondsDouble()
        return when (clip) {
            is Clip.Video -> ClipRow(
                clipId = clip.id.value,
                trackId = track.id.value,
                trackKind = trackKind,
                clipKind = "video",
                startSeconds = start,
                durationSeconds = dur,
                endSeconds = start + dur,
                assetId = clip.assetId.value,
                sourceStartSeconds = clip.sourceRange.start.toSecondsDouble(),
                sourceDurationSeconds = clip.sourceRange.duration.toSecondsDouble(),
                filterCount = clip.filters.size,
                sourceBindingNodeIds = clip.sourceBinding.map { it.value }.sorted(),
            )
            is Clip.Audio -> ClipRow(
                clipId = clip.id.value,
                trackId = track.id.value,
                trackKind = trackKind,
                clipKind = "audio",
                startSeconds = start,
                durationSeconds = dur,
                endSeconds = start + dur,
                assetId = clip.assetId.value,
                sourceStartSeconds = clip.sourceRange.start.toSecondsDouble(),
                sourceDurationSeconds = clip.sourceRange.duration.toSecondsDouble(),
                volume = clip.volume,
                fadeInSeconds = clip.fadeInSeconds,
                fadeOutSeconds = clip.fadeOutSeconds,
                sourceBindingNodeIds = clip.sourceBinding.map { it.value }.sorted(),
            )
            is Clip.Text -> ClipRow(
                clipId = clip.id.value,
                trackId = track.id.value,
                trackKind = trackKind,
                clipKind = "text",
                startSeconds = start,
                durationSeconds = dur,
                endSeconds = start + dur,
                textPreview = clip.text.take(80),
                sourceBindingNodeIds = clip.sourceBinding.map { it.value }.sorted(),
            )
        }
    }

    // -----------------------------------------------------------------
    // select = assets

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

    private fun runAssets(project: Project, input: Input, limit: Int, offset: Int): ToolResult<Output> {
        val kindFilter = (input.kind ?: "all").trim().lowercase()
        if (kindFilter !in ASSET_KINDS) {
            error("kind must be one of ${ASSET_KINDS.joinToString(", ")} (got '${input.kind}')")
        }
        val sortBy = input.sortBy?.trim()?.lowercase()
        if (sortBy != null && sortBy !in ASSET_SORTS) {
            error("sortBy for select=assets must be one of ${ASSET_SORTS.joinToString(", ")} (got '${input.sortBy}')")
        }

        val refCount: Map<String, Int> = buildMap {
            project.timeline.tracks.forEach { track ->
                track.clips.forEach { clip ->
                    val assetId = when (clip) {
                        is Clip.Video -> clip.assetId.value
                        is Clip.Audio -> clip.assetId.value
                        is Clip.Text -> null
                    }
                    if (assetId != null) put(assetId, (get(assetId) ?: 0) + 1)
                }
            }
        }

        val filtered = project.assets.asSequence()
            .map { asset -> asset to classify(asset) }
            .filter { (_, kind) -> kindFilter == "all" || kind == kindFilter }
            .map { (asset, kind) -> buildAssetRow(asset, kind, refCount[asset.id.value] ?: 0) }
            .filter { input.onlyUnused != true || it.inUseByClips == 0 }
            .toList()

        val sorted = when (sortBy) {
            null, "insertion" -> filtered
            "duration" -> filtered.sortedByDescending { it.durationSeconds }
            "duration-asc" -> filtered.sortedBy { it.durationSeconds }
            "id" -> filtered.sortedBy { it.assetId }
            else -> error("unreachable")
        }

        val page = sorted.drop(offset).take(limit)
        val rows = encodeRows(ListSerializer(AssetRow.serializer()), page)
        val scopeBits = buildList {
            add("kind=$kindFilter")
            if (input.onlyUnused == true) add("unused-only")
            sortBy?.let { add("sort=$it") }
        }.joinToString(", ")
        return ToolResult(
            title = "project_query assets ($kindFilter)",
            outputForLlm = "Project ${project.id.value}: ${filtered.size} matching assets, " +
                "returning ${page.size} (offset $offset, $scopeBits).",
            data = Output(project.id.value, SELECT_ASSETS, filtered.size, page.size, rows),
        )
    }

    private fun classify(asset: MediaAsset): String {
        val hasV = asset.metadata.videoCodec != null
        val hasA = asset.metadata.audioCodec != null
        return when {
            hasV -> "video"
            hasA -> "audio"
            else -> "image"
        }
    }

    private fun buildAssetRow(asset: MediaAsset, kind: String, refCount: Int): AssetRow {
        val res = asset.metadata.resolution
        val sourceKind = when (asset.source) {
            is MediaSource.File -> "file"
            is MediaSource.Http -> "http"
            is MediaSource.Platform -> "platform"
        }
        return AssetRow(
            assetId = asset.id.value,
            kind = kind,
            durationSeconds = asset.metadata.duration.toDouble(DurationUnit.SECONDS),
            width = res?.width,
            height = res?.height,
            hasVideoTrack = asset.metadata.videoCodec != null,
            hasAudioTrack = asset.metadata.audioCodec != null,
            sourceKind = sourceKind,
            inUseByClips = refCount,
        )
    }

    // -----------------------------------------------------------------
    // shared

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

    private fun trackKindOf(track: Track): String = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }

    private fun <T> encodeRows(serializer: KSerializer<List<T>>, rows: List<T>): JsonArray =
        JsonConfig.default.encodeToJsonElement(serializer, rows) as JsonArray

    private fun Duration.toSecondsDouble(): Double = inWholeMilliseconds / 1000.0

    companion object {
        const val SELECT_TRACKS = "tracks"
        const val SELECT_TIMELINE_CLIPS = "timeline_clips"
        const val SELECT_ASSETS = "assets"
        private val ALL_SELECTS = setOf(SELECT_TRACKS, SELECT_TIMELINE_CLIPS, SELECT_ASSETS)

        private val VALID_TRACK_KINDS = setOf("video", "audio", "subtitle", "effect")
        private val ASSET_KINDS = setOf("video", "audio", "image", "all")

        private val TRACK_SORTS = setOf("index", "clipcount", "span")
        private val CLIP_SORTS = setOf("startseconds", "durationseconds")
        private val ASSET_SORTS = setOf("insertion", "duration", "duration-asc", "id")

        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 500
    }
}
