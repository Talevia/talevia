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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class AddSubtitleTool(
    private val store: ProjectStore,
) : Tool<AddSubtitleTool.Input, AddSubtitleTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val text: String,
        val timelineStartSeconds: Double,
        val durationSeconds: Double,
        val fontSize: Float = 48f,
        val color: String = "#FFFFFF",
        val backgroundColor: String? = null,
    )
    @Serializable data class Output(val clipId: String, val trackId: String)

    override val id = "add_subtitle"
    override val helpText = "Place a text overlay (subtitle) on the timeline for the given duration."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("text") { put("type", "string") }
            putJsonObject("timelineStartSeconds") { put("type", "number") }
            putJsonObject("durationSeconds") { put("type", "number") }
            putJsonObject("fontSize") { put("type", "number") }
            putJsonObject("color") { put("type", "string"); put("description", "CSS-style hex (e.g. #FFFFFF)") }
            putJsonObject("backgroundColor") { put("type", "string"); put("description", "Optional background hex; null = transparent") }
        }
        put("required", JsonArray(listOf(
            JsonPrimitive("projectId"), JsonPrimitive("text"),
            JsonPrimitive("timelineStartSeconds"), JsonPrimitive("durationSeconds"),
        )))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val clipId = ClipId(Uuid.random().toString())
        var trackId: TrackId? = null
        store.mutate(ProjectId(input.projectId)) { project ->
            val (subtitleTrack, others) = pickSubtitleTrack(project.timeline.tracks)
            val tlRange = TimeRange(input.timelineStartSeconds.seconds, input.durationSeconds.seconds)
            val clip = Clip.Text(
                id = clipId,
                timeRange = tlRange,
                text = input.text,
                style = TextStyle(
                    fontSize = input.fontSize,
                    color = input.color,
                    backgroundColor = input.backgroundColor,
                ),
            )
            val newClips = (subtitleTrack.clips + clip).sortedBy { it.timeRange.start }
            val newTrack = (subtitleTrack as Track.Subtitle).copy(clips = newClips)
            trackId = newTrack.id
            val tracks = others + newTrack
            val duration = maxOf(project.timeline.duration, tlRange.end)
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }
        return ToolResult(
            title = "subtitle '${input.text.take(24)}'",
            outputForLlm = "Added subtitle clip ${clipId.value} from ${input.timelineStartSeconds}s for ${input.durationSeconds}s",
            data = Output(clipId.value, trackId!!.value),
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun pickSubtitleTrack(tracks: List<Track>): Pair<Track, List<Track>> {
        val match = tracks.firstOrNull { it is Track.Subtitle }
        return if (match != null) match to tracks.filter { it.id != match.id }
        else Track.Subtitle(TrackId(Uuid.random().toString())) to tracks
    }
}
