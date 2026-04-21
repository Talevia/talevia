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

@OptIn(ExperimentalUuidApi::class)
class AddSubtitleTool(
    private val store: ProjectStore,
) : Tool<AddSubtitleTool.Input, AddSubtitleTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
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
            putJsonObject("projectId") {
                put("type", "string")
                put(
                    "description",
                    "Optional — omit to use the session's current project (set via switch_project).",
                )
            }
            putJsonObject("text") { put("type", "string") }
            putJsonObject("timelineStartSeconds") { put("type", "number") }
            putJsonObject("durationSeconds") { put("type", "number") }
            putJsonObject("fontSize") { put("type", "number") }
            putJsonObject("color") { put("type", "string"); put("description", "CSS-style hex (e.g. #FFFFFF)") }
            putJsonObject("backgroundColor") { put("type", "string"); put("description", "Optional background hex; null = transparent") }
        }
        put("required", JsonArray(listOf(
            JsonPrimitive("text"),
            JsonPrimitive("timelineStartSeconds"), JsonPrimitive("durationSeconds"),
        )))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        val clipId = ClipId(Uuid.random().toString())
        var trackId: TrackId? = null
        val updated = store.mutate(pid) { project ->
            val subtitleTrack = pickSubtitleTrack(project.timeline.tracks)
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
            val newTrack = subtitleTrack.copy(clips = newClips)
            trackId = newTrack.id
            val tracks = upsertTrackPreservingOrder(project.timeline.tracks, newTrack)
            val duration = maxOf(project.timeline.duration, tlRange.end)
            project.copy(timeline = project.timeline.copy(tracks = tracks, duration = duration))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "subtitle '${input.text.take(24)}'",
            outputForLlm = "Added subtitle clip ${clipId.value} from ${input.timelineStartSeconds}s for ${input.durationSeconds}s. Timeline snapshot: ${snapshotId.value}",
            data = Output(clipId.value, trackId!!.value),
        )
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun pickSubtitleTrack(tracks: List<Track>): Track.Subtitle {
        val match = tracks.firstOrNull { it is Track.Subtitle }
        return match as? Track.Subtitle ?: Track.Subtitle(TrackId(Uuid.random().toString()))
    }
}
