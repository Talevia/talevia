package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.ProjectId
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
 * Batch variant of [AddSubtitleTool] — drops N time-aligned subtitle segments
 * onto the subtitle track in a single [ProjectStore.mutate] and emits a single
 * [io.talevia.core.session.Part.TimelineSnapshot].
 *
 * Why a separate tool, not an extra field on `add_subtitle`: closing the
 * `transcribe_asset` → subtitle loop is the load-bearing use case. A 60-second
 * clip easily has 30+ transcript segments; making the agent call `add_subtitle`
 * 30 times is wasteful in tokens, latency, and snapshot stack depth
 * (`revert_timeline` would have to step back through 30 micro-edits). This tool
 * commits all segments atomically so the undo unit matches the user intent.
 *
 * Style (`fontSize` / `color` / `backgroundColor`) is applied uniformly to all
 * segments. The narrow manual path — one subtitle at a time with per-line
 * style — is still served by [AddSubtitleTool].
 */
@OptIn(ExperimentalUuidApi::class)
class AddSubtitlesTool(
    private val store: ProjectStore,
) : Tool<AddSubtitlesTool.Input, AddSubtitlesTool.Output> {

    @Serializable data class Segment(
        val text: String,
        val startSeconds: Double,
        val durationSeconds: Double,
    )

    @Serializable data class Input(
        val projectId: String,
        val segments: List<Segment>,
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
        "Place N subtitle segments on the timeline in a single atomic edit. Intended pair " +
            "for `transcribe_asset`: convert its `segments[]` (startMs/endMs/text) to " +
            "{startSeconds, durationSeconds, text} and pass them here. All segments " +
            "receive the same style. One snapshot is emitted regardless of segment count."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("segments") {
                put("type", "array")
                put("description", "One entry per subtitle line.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("text") { put("type", "string") }
                        putJsonObject("startSeconds") { put("type", "number") }
                        putJsonObject("durationSeconds") { put("type", "number") }
                    }
                    put(
                        "required",
                        JsonArray(
                            listOf(
                                JsonPrimitive("text"),
                                JsonPrimitive("startSeconds"),
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
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("segments"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.segments.isNotEmpty()) { "segments must not be empty" }
        val style = TextStyle(
            fontSize = input.fontSize,
            color = input.color,
            backgroundColor = input.backgroundColor,
        )
        val clipIds = input.segments.map { ClipId(Uuid.random().toString()) }
        var trackId: TrackId? = null
        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val subtitleTrack = pickSubtitleTrack(project.timeline.tracks)
            val newTextClips = input.segments.mapIndexed { index, seg ->
                Clip.Text(
                    id = clipIds[index],
                    timeRange = TimeRange(
                        seg.startSeconds.seconds,
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
            title = "subtitles x${input.segments.size}",
            outputForLlm = "Added ${input.segments.size} subtitle clip(s) to track ${trackId!!.value}. " +
                "Timeline snapshot: ${snapshotId.value}",
            data = Output(trackId = trackId!!.value, clipIds = clipIds.map { it.value }),
        )
    }

    private fun pickSubtitleTrack(tracks: List<Track>): Track.Subtitle {
        val match = tracks.firstOrNull { it is Track.Subtitle }
        return match as? Track.Subtitle ?: Track.Subtitle(TrackId(Uuid.random().toString()))
    }
}
