package io.talevia.core.tool.builtin.project

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
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
import kotlin.time.Duration.Companion.seconds

/**
 * Timeline-level clip introspection. `get_project_state` reports counts; this
 * tool reports the actual clips — what sits where, on which track, with which
 * source binding, at what seconds. The agent needs this to answer questions
 * like "trim the third clip" or "what's on the audio track at 00:14", and to
 * plan multi-step edits without re-deriving everything from scattered
 * snapshot metadata.
 *
 * Filterable by [Input.trackKind] (`video` | `audio` | `subtitle` | `effect`),
 * by [Input.trackId] (exact match), and by a time window
 * (`fromSeconds` / `toSeconds` — a clip is included if its timeRange
 * intersects the window). Results are ordered deterministically by track
 * index, then by `timeRange.start` so a dump can be compared across turns.
 *
 * `outputForLlm` is a compact one-line-per-clip summary (the agent reads
 * this every turn), `data.clips` is the structured view for programmatic
 * consumers. A small [Input.limit] cap prevents a runaway dump from eating
 * the context — clipped results flip `truncated=true` so the agent knows
 * to refine the filter.
 */
class ListTimelineClipsTool(
    private val projects: ProjectStore,
) : Tool<ListTimelineClipsTool.Input, ListTimelineClipsTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        /** Exact `track.id.value` match. Leave null to include every track. */
        val trackId: String? = null,
        /**
         * `"video"` | `"audio"` | `"subtitle"` | `"effect"` — case-insensitive.
         * Unknown values are rejected so typos surface loudly rather than
         * silently returning an empty list.
         */
        val trackKind: String? = null,
        val fromSeconds: Double? = null,
        val toSeconds: Double? = null,
        /** Max clips to return. Default 100; clips past the cap flip `truncated=true`. */
        val limit: Int = 100,
    )

    @Serializable data class ClipInfo(
        val clipId: String,
        val trackId: String,
        val trackKind: String,
        val clipKind: String,
        val startSeconds: Double,
        val durationSeconds: Double,
        val endSeconds: Double,
        /** Media asset (video / audio clips only). Null for text / effect. */
        val assetId: String? = null,
        val sourceStartSeconds: Double? = null,
        val sourceDurationSeconds: Double? = null,
        /** Video only: number of attached filters. */
        val filterCount: Int = 0,
        /** Audio only. */
        val volume: Float? = null,
        val fadeInSeconds: Float? = null,
        val fadeOutSeconds: Float? = null,
        /** Text only: truncated preview (first 80 chars). */
        val textPreview: String? = null,
        /** Source-DAG node ids this clip depends on. Empty on imported clips. */
        val sourceBindingNodeIds: List<String> = emptyList(),
    )

    @Serializable data class Output(
        val projectId: String,
        val totalClipCount: Int,
        val returnedClipCount: Int,
        val truncated: Boolean,
        val clips: List<ClipInfo>,
    )

    override val id: String = "list_timeline_clips"
    override val helpText: String =
        "List clips on the project's timeline with structured metadata (track, kind, start, " +
            "duration, assetId, filters, audio volume/fade, text preview, source bindings). " +
            "Filter by trackId, trackKind ('video'|'audio'|'subtitle'|'effect'), or a time " +
            "window. Use this whenever the user refers to 'that clip', 'the intro', 'track 2' — " +
            "it's cheaper than dumping the whole project. Capped at `limit` (default 100); the " +
            "`truncated` flag tells you to refine the filter."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("project.read")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("trackId") {
                put("type", "string")
                put("description", "Exact track id to filter by.")
            }
            putJsonObject("trackKind") {
                put("type", "string")
                put("description", "video | audio | subtitle | effect (case-insensitive).")
            }
            putJsonObject("fromSeconds") {
                put("type", "number")
                put("description", "Lower bound of the time window. Clips whose timeRange ends before this are excluded.")
            }
            putJsonObject("toSeconds") {
                put("type", "number")
                put("description", "Upper bound of the time window. Clips whose timeRange starts after this are excluded.")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "Max clips to return (default 100). Results past the cap flip `truncated=true`.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ProjectId(input.projectId)
        val project = projects.get(pid) ?: error("Project ${input.projectId} not found")

        val normalizedKind = input.trackKind?.trim()?.lowercase()
        if (normalizedKind != null && normalizedKind !in VALID_TRACK_KINDS) {
            error(
                "trackKind must be one of ${VALID_TRACK_KINDS.joinToString(", ")} (got '${input.trackKind}')",
            )
        }
        val limit = input.limit.coerceAtLeast(1)

        val fromDuration = input.fromSeconds?.coerceAtLeast(0.0)?.seconds
        val toDuration = input.toSeconds?.coerceAtLeast(0.0)?.seconds

        val collected = mutableListOf<ClipInfo>()
        var totalMatched = 0
        for (track in project.timeline.tracks) {
            val trackKind = trackKindOf(track)
            if (normalizedKind != null && trackKind != normalizedKind) continue
            if (input.trackId != null && track.id.value != input.trackId) continue
            val ordered = track.clips.sortedBy { it.timeRange.start }
            for (clip in ordered) {
                if (fromDuration != null && clip.timeRange.end < fromDuration) continue
                if (toDuration != null && clip.timeRange.start > toDuration) continue
                totalMatched += 1
                if (collected.size < limit) collected += clipInfoOf(clip, track, trackKind)
            }
        }

        val truncated = totalMatched > collected.size
        val out = Output(
            projectId = pid.value,
            totalClipCount = totalMatched,
            returnedClipCount = collected.size,
            truncated = truncated,
            clips = collected,
        )
        return ToolResult(
            title = "list timeline clips (${collected.size})",
            outputForLlm = summarise(collected, totalMatched, truncated),
            data = out,
        )
    }

    private fun summarise(clips: List<ClipInfo>, total: Int, truncated: Boolean): String {
        if (clips.isEmpty()) return "No clips match the given filters."
        val head = clips.joinToString("\n") { c ->
            val extra = when (c.clipKind) {
                "video" -> c.assetId?.let { " asset=$it" }.orEmpty() +
                    if (c.filterCount > 0) " filters=${c.filterCount}" else ""
                "audio" -> c.assetId?.let { " asset=$it" }.orEmpty() +
                    (c.volume?.let { " vol=$it" }.orEmpty())
                "text" -> c.textPreview?.let { " text=\"${it.take(40)}${if (it.length > 40) "…" else ""}\"" }.orEmpty()
                else -> ""
            }
            val binding = if (c.sourceBindingNodeIds.isEmpty()) {
                ""
            } else {
                " bindings=${c.sourceBindingNodeIds.joinToString(",")}"
            }
            "- [${c.trackKind}/${c.trackId}] ${c.clipId} @ ${c.startSeconds}s +${c.durationSeconds}s$extra$binding"
        }
        val tail = if (truncated) "\n… (${total - clips.size} more not shown)" else ""
        return head + tail
    }

    private fun trackKindOf(track: Track): String = when (track) {
        is Track.Video -> "video"
        is Track.Audio -> "audio"
        is Track.Subtitle -> "subtitle"
        is Track.Effect -> "effect"
    }

    private fun clipInfoOf(clip: Clip, track: Track, trackKind: String): ClipInfo {
        val start = clip.timeRange.start.toSecondsDouble()
        val dur = clip.timeRange.duration.toSecondsDouble()
        return when (clip) {
            is Clip.Video -> ClipInfo(
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
            is Clip.Audio -> ClipInfo(
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
            is Clip.Text -> ClipInfo(
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

    private fun Duration.toSecondsDouble(): Double = inWholeMilliseconds / 1000.0

    companion object {
        private val VALID_TRACK_KINDS = setOf("video", "audio", "subtitle", "effect")
    }
}
