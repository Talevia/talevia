package io.talevia.core.tool.builtin.video

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

/**
 * Delete one or many clips from the timeline atomically — the missing primitive
 * from the cut/stitch/filter/transition lineup the agent uses to *edit* (VISION §1
 * "传统剪辑与特效渲染（cut / stitch / filter / transition / OpenGL shader / 合成）").
 *
 * Broadcast shape — a single shared `ripple` flag governs the whole batch. The
 * rare case of "ripple some, leave gaps on others" deserves two calls rather
 * than complicating the common case with per-item flags.
 *
 * By default, other clips' timeRanges are not adjusted — the gap is left as-is
 * so existing transitions / subtitles aligned to specific timeline timestamps
 * don't drift. Pass `ripple=true` to close each gap: every clip on the same
 * track whose `timeRange.start >= removed.timeRange.end` shifts left by the
 * removed clip's duration. Overlapping / layered clips on the same track
 * (`start < removed.end`) are left alone — they were intentionally placed to
 * overlap, and shifting them would destroy the overlap semantics. Other
 * tracks are never touched.
 *
 * Ripple cascades within the batch: when removing two clips A and B on the
 * same track with `ripple=true`, after A's removal the positions of B and
 * all clips after A have already shifted. We apply removals **in the order
 * listed** so the agent can predict cumulative effects, and we re-resolve
 * each clipId in the current (possibly shifted) state.
 *
 * All-or-nothing: any clipId that doesn't resolve aborts the whole batch.
 * One `Part.TimelineSnapshot` per call; `revert_timeline` rolls the whole
 * batch back in one step.
 */
class RemoveClipTool(
    private val store: ProjectStore,
) : Tool<RemoveClipTool.Input, RemoveClipTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val clipIds: List<String>,
        /**
         * When `true`, close each gap on the removed clip's track by shifting
         * every later non-overlapping clip left by the removed clip's
         * duration. Default `false` preserves "leave the gap" semantics. Does
         * NOT ripple across tracks.
         */
        val ripple: Boolean = false,
    )

    @Serializable data class ItemResult(
        val clipId: String,
        val trackId: String,
        val durationSeconds: Double,
        val shiftedClipCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val rippled: Boolean,
        val snapshotId: String,
    )

    override val id = "remove_clips"
    override val helpText =
        "Delete one or many clips atomically. Shared `ripple=true` closes the gap on each clip's " +
            "track (every later clip shifts left by the removed clip's duration); default `false` " +
            "leaves gaps so transitions / subtitles aligned to specific timestamps don't drift. " +
            "Ripple is single-track only — chain `move_clips` on other tracks when sync requires " +
            "it. All-or-nothing: if any clipId is missing, nothing is deleted. One timeline " +
            "snapshot per call."
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
            putJsonObject("clipIds") {
                put("type", "array")
                put("description", "Clip ids to delete. At least one required.")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("ripple") {
                put("type", "boolean")
                put("description", "Close the gap on each removed clip's track. Default false.")
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("clipIds"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.clipIds.isNotEmpty()) { "clipIds must not be empty" }
        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.clipIds.forEachIndexed { idx, clipId ->
                var foundTrackId: String? = null
                var removedRange: TimeRange? = null
                var shifted = 0
                tracks = tracks.map { track ->
                    val target = track.clips.firstOrNull { it.id.value == clipId }
                    if (target == null) {
                        track
                    } else {
                        foundTrackId = track.id.value
                        removedRange = target.timeRange
                        val keep = track.clips.filter { it.id.value != clipId }
                        val shiftedClips = if (input.ripple) {
                            keep.map { clip ->
                                if (clip.timeRange.start >= target.timeRange.end) {
                                    shifted += 1
                                    clip.shiftStart(-target.timeRange.duration)
                                } else {
                                    clip
                                }
                            }
                        } else {
                            keep
                        }
                        when (track) {
                            is Track.Video -> track.copy(clips = shiftedClips)
                            is Track.Audio -> track.copy(clips = shiftedClips)
                            is Track.Subtitle -> track.copy(clips = shiftedClips)
                            is Track.Effect -> track.copy(clips = shiftedClips)
                        }
                    }
                }
                if (foundTrackId == null) {
                    error("clipIds[$idx] ($clipId) not found in project ${pid.value}")
                }
                results += ItemResult(
                    clipId = clipId,
                    trackId = foundTrackId!!,
                    durationSeconds = removedRange!!.duration.inWholeMilliseconds / 1000.0,
                    shiftedClipCount = shifted,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        val summary = buildString {
            append("Removed ${results.size} clip(s)")
            if (input.ripple) append(" (rippled)")
            append(". Timeline snapshot: ${snapshotId.value}")
        }
        return ToolResult(
            title = "remove ${results.size} clip(s)",
            outputForLlm = summary,
            data = Output(
                projectId = pid.value,
                results = results,
                rippled = input.ripple,
                snapshotId = snapshotId.value,
            ),
        )
    }

    private fun Clip.shiftStart(delta: Duration): Clip {
        val newRange = timeRange.copy(start = timeRange.start + delta)
        return when (this) {
            is Clip.Video -> copy(timeRange = newRange)
            is Clip.Audio -> copy(timeRange = newRange)
            is Clip.Text -> copy(timeRange = newRange)
        }
    }
}
