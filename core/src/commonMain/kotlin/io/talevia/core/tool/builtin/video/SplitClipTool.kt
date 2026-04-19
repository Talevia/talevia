package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
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
class SplitClipTool(
    private val store: ProjectStore,
) : Tool<SplitClipTool.Input, SplitClipTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        val atTimelineSeconds: Double,
    )
    @Serializable data class Output(val leftClipId: String, val rightClipId: String)

    override val id = "split_clip"
    override val helpText = "Split a clip in two at the given timeline position. New clip IDs are generated for both halves."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("atTimelineSeconds") { put("type", "number"); put("description", "Absolute timeline position to split at.") }
        }
        put("required", JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"), JsonPrimitive("atTimelineSeconds"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        var left: ClipId? = null
        var right: ClipId? = null
        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            var found = false
            val splitAt = input.atTimelineSeconds.seconds
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId } ?: return@map track
                found = true
                if (splitAt <= target.timeRange.start || splitAt >= target.timeRange.end) {
                    error("Split point ${input.atTimelineSeconds}s is outside clip ${target.timeRange.start}..${target.timeRange.end}")
                }
                val offset = splitAt - target.timeRange.start
                val (l, r) = splitClip(target, offset)
                left = l.id; right = r.id
                rebuildTrack(track, target, listOf(l, r))
            }
            if (!found) error("clip ${input.clipId} not found in project ${input.projectId}")
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }
        val cs = (input.atTimelineSeconds * 100).toLong()
        val pretty = "${cs / 100}.${(cs % 100).toString().padStart(2, '0')}"
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "split clip @ ${pretty}s",
            outputForLlm = "Split clip ${input.clipId} into ${left?.value} + ${right?.value}. Timeline snapshot: ${snapshotId.value}",
            data = Output(left!!.value, right!!.value),
        )
    }

    private fun splitClip(c: Clip, offset: Duration): Pair<Clip, Clip> {
        val leftId = ClipId(Uuid.random().toString())
        val rightId = ClipId(Uuid.random().toString())
        val leftRange = TimeRange(c.timeRange.start, offset)
        val rightRange = TimeRange(c.timeRange.start + offset, c.timeRange.duration - offset)
        return when (c) {
            is Clip.Video -> {
                val srcLeft = TimeRange(c.sourceRange.start, offset)
                val srcRight = TimeRange(c.sourceRange.start + offset, c.sourceRange.duration - offset)
                c.copy(id = leftId, timeRange = leftRange, sourceRange = srcLeft) to
                    c.copy(id = rightId, timeRange = rightRange, sourceRange = srcRight)
            }
            is Clip.Audio -> {
                val srcLeft = TimeRange(c.sourceRange.start, offset)
                val srcRight = TimeRange(c.sourceRange.start + offset, c.sourceRange.duration - offset)
                c.copy(id = leftId, timeRange = leftRange, sourceRange = srcLeft) to
                    c.copy(id = rightId, timeRange = rightRange, sourceRange = srcRight)
            }
            is Clip.Text -> c.copy(id = leftId, timeRange = leftRange) to c.copy(id = rightId, timeRange = rightRange)
        }
    }

    private fun rebuildTrack(track: Track, removed: Clip, replacements: List<Clip>): Track {
        val clips = (track.clips.filter { it.id != removed.id } + replacements).sortedBy { it.timeRange.start }
        return when (track) {
            is Track.Video -> track.copy(clips = clips)
            is Track.Audio -> track.copy(clips = clips)
            is Track.Subtitle -> track.copy(clips = clips)
            is Track.Effect -> track.copy(clips = clips)
        }
    }
}
