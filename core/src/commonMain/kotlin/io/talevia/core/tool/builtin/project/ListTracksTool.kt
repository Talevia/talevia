package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import kotlin.time.Duration

/**
 * Track-level layout introspection. [ListTimelineClipsTool] drills into
 * individual clips; this tool summarises the track skeleton — one row per
 * track with kind, clip count, time span, and stacking index — so the agent
 * can plan multi-track edits (PiP layering, multi-stem audio, localised
 * subtitle variants) without paging through every clip.
 *
 * Ordering in [Output.tracks] preserves [Timeline.tracks] order, which is
 * also the engines' stacking order (first track is drawn on the bottom;
 * later tracks composite on top). An agent reading this output can reason
 * about "which video track is on top" without a separate `reorder_tracks`
 * lookup.
 *
 * Read-only, `project.read` permission — cheap to call at session start or
 * after any `add_track` / `remove_track` / `reorder_tracks` mutation.
 */
class ListTracksTool(
    private val projects: ProjectStore,
) : Tool<ListTracksTool.Input, ListTracksTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /**
         * `"video"` | `"audio"` | `"subtitle"` | `"effect"` — case-insensitive.
         * Unknown values are rejected so typos surface loudly instead of
         * returning an empty list that looks legitimate.
         */
        val trackKind: String? = null,
        /**
         * When `true`, skip tracks whose `clips` list is empty — useful for
         * orientation reads that want to ignore scaffold tracks added but
         * never populated. `null` or `false` keeps today's behaviour of
         * returning every track. Composes with [trackKind] (kind filter
         * applies first, then empty-skip).
         */
        val onlyNonEmpty: Boolean? = null,
        /**
         * Max rows to return after filters apply. Defaults to 100; silently
         * clamped to `[1, 500]` so out-of-range values do not throw.
         * [Output.totalTrackCount] still reflects the pre-filter total, so
         * the agent can tell when the cap hid rows.
         */
        val limit: Int? = null,
    )

    @Serializable data class TrackInfo(
        val trackId: String,
        val trackKind: String,
        /** 0-based position in [Timeline.tracks]. Lower = drawn earlier (bottom). */
        val index: Int,
        val clipCount: Int,
        val isEmpty: Boolean,
        /** Start of the earliest clip on this track, in seconds. Null for empty tracks. */
        val firstClipStartSeconds: Double? = null,
        /** End of the latest clip on this track, in seconds. Null for empty tracks. */
        val lastClipEndSeconds: Double? = null,
        /**
         * Total wall-clock span covered by clips — `(lastEnd - firstStart)`, NOT
         * the sum of clip durations. Null for empty tracks. Gives a feel for how
         * tightly packed the track is when compared against `clipCount`.
         */
        val spanSeconds: Double? = null,
    )

    @Serializable data class Output(
        val projectId: String,
        val totalTrackCount: Int,
        val returnedTrackCount: Int,
        val tracks: List<TrackInfo>,
    )

    override val id: String = "list_tracks"
    override val helpText: String =
        "List timeline tracks in stacking order (first = drawn on bottom, later = composited on top). " +
            "Each row reports kind, clipCount, first/last clip seconds, and span — what the agent needs to " +
            "plan PiP layouts, multi-stem audio mixes, or localised subtitle variants without pulling every " +
            "clip. Filter by trackKind (video|audio|subtitle|effect, case-insensitive). Pass onlyNonEmpty=true " +
            "to hide scaffold tracks with no clips (composes with trackKind). Cap the response with limit " +
            "(default 100, clamped to 1..500)."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("trackKind") {
                put("type", "string")
                put("description", "video | audio | subtitle | effect (case-insensitive).")
            }
            putJsonObject("onlyNonEmpty") {
                put("type", "boolean")
                put(
                    "description",
                    "When true, skip tracks whose clip list is empty — hides scaffold tracks from " +
                        "orientation reads. Composes with trackKind (kind filter first, then empty-skip). " +
                        "Defaults to false.",
                )
            }
            putJsonObject("limit") {
                put("type", "integer")
                put(
                    "description",
                    "Cap on returned rows after filters apply (default 100, clamped to 1..500). " +
                        "totalTrackCount stays the pre-filter total so you can tell when rows were hidden.",
                )
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val project = projects.get(ProjectId(input.projectId))
            ?: error("Project ${input.projectId} not found")

        val normalizedKind = input.trackKind?.trim()?.lowercase()
        if (normalizedKind != null && normalizedKind !in VALID_TRACK_KINDS) {
            error(
                "trackKind must be one of ${VALID_TRACK_KINDS.joinToString(", ")} (got '${input.trackKind}')",
            )
        }

        val limit = (input.limit ?: DEFAULT_LIMIT).coerceIn(MIN_LIMIT, MAX_LIMIT)

        val allTracks = project.timeline.tracks
        val filteredTracks = allTracks.withIndex().filter { (_, track) ->
            val kind = trackKindOf(track)
            if (normalizedKind != null && kind != normalizedKind) return@filter false
            if (input.onlyNonEmpty == true && track.clips.isEmpty()) return@filter false
            true
        }
        val rows = filteredTracks.take(limit).map { (index, track) ->
            buildInfo(track, index, trackKindOf(track))
        }

        val out = Output(
            projectId = input.projectId,
            totalTrackCount = allTracks.size,
            returnedTrackCount = rows.size,
            tracks = rows,
        )

        val body = if (rows.isEmpty()) {
            "No tracks match the given filter."
        } else {
            rows.joinToString("\n") { r ->
                val span = if (r.isEmpty) {
                    "empty"
                } else {
                    "${r.clipCount} clips, ${r.firstClipStartSeconds}s..${r.lastClipEndSeconds}s"
                }
                "- #${r.index} [${r.trackKind}/${r.trackId}] $span"
            }
        }

        val scopeParts = buildList {
            normalizedKind?.let { add("kind=$it") }
            if (input.onlyNonEmpty == true) add("non-empty")
        }
        val scope = if (scopeParts.isEmpty()) "" else ", ${scopeParts.joinToString(", ")}"
        return ToolResult(
            title = "list tracks (${rows.size}/${allTracks.size})",
            outputForLlm = "Project ${input.projectId}: ${rows.size} of ${allTracks.size} track(s)$scope.\n$body",
            data = out,
        )
    }

    private fun trackKindOf(track: Track): String = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }

    private fun buildInfo(track: Track, index: Int, kind: String): TrackInfo {
        val clips = track.clips
        if (clips.isEmpty()) {
            return TrackInfo(
                trackId = track.id.value,
                trackKind = kind,
                index = index,
                clipCount = 0,
                isEmpty = true,
            )
        }
        val firstStart = clips.minOf { it.timeRange.start }
        val lastEnd = clips.maxOf { it.timeRange.end }
        return TrackInfo(
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

    private fun Duration.toSecondsDouble(): Double = inWholeMilliseconds / 1000.0

    companion object {
        private val VALID_TRACK_KINDS = setOf("video", "audio", "subtitle", "effect")
        private const val DEFAULT_LIMIT = 100
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 500
    }
}
