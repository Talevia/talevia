package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
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

/**
 * Set the playback volume on an audio clip already on the timeline.
 *
 * `Clip.Audio.volume` was settable at construction (e.g. `add_clip` records
 * the asset's natural level) but had no post-creation editor — "lower the
 * background music to 30%" is a basic editing request that previously
 * required `remove_clip` + `add_clip`, which would lose downstream
 * filter / source-binding state. Same shape as `move_clip` / `trim_clip`:
 * one in-place edit that preserves the clip id.
 *
 * Video clips have no `volume` field today (track-level mixing is a future
 * concern when we model audio rails), and Text clips obviously have no
 * audio — both fail loud rather than silently no-op.
 *
 * Volume is an absolute multiplier in [0, 4]:
 *  - `0.0` mutes the clip without removing it (preserves automation
 *    references for a future fade-out tool).
 *  - `1.0` is unchanged.
 *  - `> 1.0` amplifies; capped at 4× because most renderers (ffmpeg
 *    `volume` filter included) clip beyond that and louder usually means
 *    the user really wants gain staging at mix time, not at the clip
 *    level.
 *
 * Emits a `Part.TimelineSnapshot` so `revert_timeline` can undo.
 */
class SetClipVolumeTool(
    private val store: ProjectStore,
) : Tool<SetClipVolumeTool.Input, SetClipVolumeTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        val clipId: String,
        /** Absolute multiplier in [0, 4]. 1.0 = unchanged, 0.0 = mute. */
        val volume: Float,
    )

    @Serializable data class Output(
        val clipId: String,
        val trackId: String,
        val oldVolume: Float,
        val newVolume: Float,
    )

    override val id = "set_clip_volume"
    override val helpText =
        "Set the playback volume of an audio clip on the timeline (0.0 = mute, " +
            "1.0 = unchanged, up to 4.0 = +12dB). Audio clips only — video and text " +
            "clips fail loudly. Preserves clip id and every other attached state " +
            "(sourceRange, sourceBinding, transforms). Emits a timeline snapshot " +
            "so revert_timeline can undo."
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
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("volume") {
                put("type", "number")
                put("description", "Absolute multiplier in [0, 4]. 1.0 = unchanged.")
            }
        }
        put(
            "required",
            JsonArray(
                listOf(
                    JsonPrimitive("clipId"),
                    JsonPrimitive("volume"),
                ),
            ),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.volume.isFinite()) { "volume must be finite (got ${input.volume})" }
        require(input.volume >= 0f) { "volume must be >= 0 (got ${input.volume})" }
        require(input.volume <= MAX_VOLUME) {
            "volume must be <= $MAX_VOLUME; clip-level gain beyond that belongs in mix-time staging."
        }

        val pid = ctx.resolveProjectId(input.projectId)
        var foundTrackId: String? = null
        var oldVolume = 0f
        val updated = store.mutate(pid) { project ->
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId }
                if (target == null) {
                    track
                } else {
                    val audio = target as? Clip.Audio ?: error(
                        "set_clip_volume only applies to audio clips; clip ${input.clipId} " +
                            "is a ${target::class.simpleName}.",
                    )
                    foundTrackId = track.id.value
                    oldVolume = audio.volume
                    val rebuilt = track.clips.map {
                        if (it.id == audio.id) audio.copy(volume = input.volume) else it
                    }
                    when (track) {
                        is Track.Video -> track.copy(clips = rebuilt)
                        is Track.Audio -> track.copy(clips = rebuilt)
                        is Track.Subtitle -> track.copy(clips = rebuilt)
                        is Track.Effect -> track.copy(clips = rebuilt)
                    }
                }
            }
            if (foundTrackId == null) {
                error("clip ${input.clipId} not found in project ${pid.value}")
            }
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "set volume ${input.clipId} → ${input.volume}",
            outputForLlm = "Set audio clip ${input.clipId} on track $foundTrackId volume " +
                "$oldVolume → ${input.volume}. Timeline snapshot: ${snapshotId.value}",
            data = Output(
                clipId = input.clipId,
                trackId = foundTrackId!!,
                oldVolume = oldVolume,
                newVolume = input.volume,
            ),
        )
    }

    companion object {
        const val MAX_VOLUME: Float = 4.0f
    }
}
