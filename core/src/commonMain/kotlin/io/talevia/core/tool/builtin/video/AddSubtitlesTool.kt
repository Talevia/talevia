package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.TrackId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.TimeRange
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
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Place one or more subtitle segments on the subtitle track in a single
 * atomic edit. Unifies the former single-subtitle and batch tools
 * (`add_subtitle` + `add_subtitles`) behind one entry point —
 * single-subtitle callers pass `subtitles=[{text, timelineStartSeconds,
 * durationSeconds}]`; batch callers (the `transcribe_asset` → caption
 * loop) pass the full list.
 *
 * Style (`fontSize` / `color` / `backgroundColor`) is applied uniformly
 * to every segment in a single call — per-segment overrides are not
 * supported this round. Callers that need heterogeneous styling issue
 * multiple `add_subtitles` calls, one per style group, or edit
 * individual segments after the fact via `edit_text_clip`.
 *
 * Emits a single `Part.TimelineSnapshot` regardless of segment count so
 * `revert_timeline` rolls the whole batch back in one step (the
 * load-bearing reason for the merge — subtitle ingestion is usually
 * N ≫ 1 segments from transcribe_asset output).
 */
@OptIn(ExperimentalUuidApi::class)
class AddSubtitlesTool(
    private val store: ProjectStore,
) : Tool<AddSubtitlesTool.Input, AddSubtitlesTool.Output> {

    @Serializable data class SubtitleSpec(
        val text: String,
        val timelineStartSeconds: Double,
        val durationSeconds: Double,
    )

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /** At least one spec required. Single-subtitle callers pass a 1-element list. */
        val subtitles: List<SubtitleSpec>,
        val fontSize: Float = 48f,
        val color: String = "#FFFFFF",
        val backgroundColor: String? = null,
    )

    @Serializable data class Output(
        val trackId: String,
        val clipIds: List<String>,
    )

    override val id = "add_subtitles"
    override val helpText =
        "Place subtitle segments on the subtitle track atomically. Single-subtitle is a " +
            "1-element list. All segments share style (fontSize / color / backgroundColor). " +
            "Pairs with transcribe_asset: convert its segments[] to {text, timelineStart" +
            "Seconds, durationSeconds}. One snapshot per batch."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

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
            putJsonObject("subtitles") {
                put("type", "array")
                put("description", "One entry per subtitle line. Single-subtitle is a 1-element list.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("text") { put("type", "string") }
                        putJsonObject("timelineStartSeconds") { put("type", "number") }
                        putJsonObject("durationSeconds") { put("type", "number") }
                    }
                    put(
                        "required",
                        JsonArray(
                            listOf(
                                JsonPrimitive("text"),
                                JsonPrimitive("timelineStartSeconds"),
                                JsonPrimitive("durationSeconds"),
                            ),
                        ),
                    )
                    put("additionalProperties", false)
                }
            }
            putJsonObject("fontSize") { put("type", "number") }
            putJsonObject("color") { put("type", "string"); put("description", "CSS-style hex (e.g. #FFFFFF)") }
            putJsonObject("backgroundColor") {
                put("type", "string")
                put("description", "Optional background hex; null = transparent")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("subtitles"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.subtitles.isNotEmpty()) { "subtitles must not be empty" }
        val pid = ctx.resolveProjectId(input.projectId)
        val style = TextStyle(
            fontSize = input.fontSize,
            color = input.color,
            backgroundColor = input.backgroundColor,
        )
        val clipIds = input.subtitles.map { ClipId(Uuid.random().toString()) }
        var trackId: TrackId? = null
        val updated = store.mutate(pid) { project ->
            val subtitleTrack = pickSubtitleTrack(project.timeline.tracks)
            val newTextClips = input.subtitles.mapIndexed { index, seg ->
                Clip.Text(
                    id = clipIds[index],
                    timeRange = TimeRange(
                        seg.timelineStartSeconds.seconds,
                        seg.durationSeconds.seconds,
                    ),
                    text = seg.text,
                    style = style,
                )
            }
            val newClips = (subtitleTrack.clips + newTextClips).sortedBy { it.timeRange.start }
            val newTrack = subtitleTrack.copy(clips = newClips)
            trackId = newTrack.id
            val tracks = upsertTrackPreservingOrder(project.timeline.tracks, newTrack)
            val tailEnd = newTextClips.maxOf { it.timeRange.end }
            val duration = maxOf(project.timeline.duration, tailEnd)
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "subtitles x${input.subtitles.size}",
            outputForLlm = "Added ${input.subtitles.size} subtitle clip(s) to track ${trackId!!.value}. " +
                "Timeline snapshot: ${snapshotId.value}",
            data = Output(trackId = trackId!!.value, clipIds = clipIds.map { it.value }),
        )
    }

    private fun pickSubtitleTrack(tracks: List<Track>): Track.Subtitle {
        val match = tracks.firstOrNull { it is Track.Subtitle }
        return match as? Track.Subtitle ?: Track.Subtitle(TrackId(Uuid.random().toString()))
    }
}
