package io.talevia.core.tool.builtin.video

import io.talevia.core.ClipId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TimeRange
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
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

/**
 * Split one or many clips at the given timeline positions in a single atomic edit.
 *
 * Per-item shape: each entry carries its own clipId + atTimelineSeconds. Items
 * are applied in order; a split's output ids are returned in the same order.
 * Cascading splits on the same clip within one call are allowed — after a
 * split, the later op can reference either resulting half by the id the
 * previous op produced (but the agent cannot know those ids in the same
 * assistant message, so that pattern normally requires a second call).
 *
 * All-or-nothing. One `Part.TimelineSnapshot` per call.
 */
@OptIn(ExperimentalUuidApi::class)
class SplitClipTool(
    private val store: ProjectStore,
) : Tool<SplitClipTool.Input, SplitClipTool.Output> {

    @Serializable data class Item(
        val clipId: String,
        val atTimelineSeconds: Double,
    )

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val items: List<Item>,
    )

    @Serializable data class ItemResult(
        val originalClipId: String,
        val leftClipId: String,
        val rightClipId: String,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id = "split_clips"
    override val helpText = "Split one or many clips at the given timeline positions atomically. " +
        "Each item produces two fresh clip ids (left + right halves). All-or-nothing: any item " +
        "whose split point lies outside the clip's time range aborts the whole batch. One " +
        "timeline snapshot per call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

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
            putJsonObject("items") {
                put("type", "array")
                put("description", "Split operations. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("clipId") { put("type", "string") }
                        putJsonObject("atTimelineSeconds") {
                            put("type", "number")
                            put("description", "Absolute timeline position to split at (strictly between clip's start and end).")
                        }
                    }
                    put(
                        "required",
                        JsonArray(listOf(JsonPrimitive("clipId"), JsonPrimitive("atTimelineSeconds"))),
                    )
                    put("additionalProperties", false)
                }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("items"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.items.isNotEmpty()) { "items must not be empty" }
        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.items.forEachIndexed { idx, item ->
                var found = false
                var leftId: ClipId? = null
                var rightId: ClipId? = null
                val splitAt = item.atTimelineSeconds.seconds
                tracks = tracks.map { track ->
                    val target = track.clips.firstOrNull { it.id.value == item.clipId }
                        ?: return@map track
                    found = true
                    if (splitAt <= target.timeRange.start || splitAt >= target.timeRange.end) {
                        error(
                            "items[$idx] (${item.clipId}): split point ${item.atTimelineSeconds}s " +
                                "is outside clip ${target.timeRange.start}..${target.timeRange.end}",
                        )
                    }
                    val offset = splitAt - target.timeRange.start
                    val (l, r) = splitClip(target, offset)
                    leftId = l.id
                    rightId = r.id
                    rebuildTrack(track, target, listOf(l, r))
                }
                if (!found) error("items[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                results += ItemResult(
                    originalClipId = item.clipId,
                    leftClipId = leftId!!.value,
                    rightClipId = rightId!!.value,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "split × ${results.size}",
            outputForLlm = "Split ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
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
