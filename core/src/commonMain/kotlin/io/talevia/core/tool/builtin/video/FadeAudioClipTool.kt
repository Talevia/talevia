package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
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
import kotlin.time.DurationUnit

/**
 * Set fade-in / fade-out ramps on an existing audio clip. The sibling of
 * [SetClipVolumeTool]: `set_clip_volume` controls the steady-state level,
 * this controls the attack / release envelope. Together they cover the
 * basics of "music swells in, dips during dialogue, fades out" workflows.
 *
 * Each field is optional; at least one must be set. Unspecified fields
 * keep the clip's current value, so "add a 2s fade-in" doesn't clobber
 * an existing 1s fade-out.
 *
 * Validation: both lengths must be non-negative, finite, and their sum
 * must not exceed the clip's timeline duration — overlapping fades have
 * no well-defined envelope. Applied to a non-audio clip the tool fails
 * loudly; the envelope has no meaning on text clips and video clips
 * have no audio-track fields yet (track-level mixing is a future
 * concern).
 *
 * Domain-only mutation today: the FFmpeg / AVFoundation / Media3 engines
 * don't apply audio envelopes yet (same "compiler captures intent,
 * renderer catches up" pattern as `set_clip_volume` and
 * `set_clip_transform`). Shipping the tool first lets the agent accept
 * fade requests and record them in the Project; the engine work is
 * tracked separately.
 *
 * Emits a `Part.TimelineSnapshot` so `revert_timeline` can undo.
 */
class FadeAudioClipTool(
    private val store: ProjectStore,
) : Tool<FadeAudioClipTool.Input, FadeAudioClipTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        /** Fade-in ramp length in seconds. `0.0` disables; omit to keep current. */
        val fadeInSeconds: Float? = null,
        /** Fade-out ramp length in seconds. `0.0` disables; omit to keep current. */
        val fadeOutSeconds: Float? = null,
    )

    @Serializable data class Output(
        val clipId: String,
        val trackId: String,
        val oldFadeInSeconds: Float,
        val newFadeInSeconds: Float,
        val oldFadeOutSeconds: Float,
        val newFadeOutSeconds: Float,
    )

    override val id = "fade_audio_clip"
    override val helpText =
        "Set fade-in / fade-out envelope on an audio clip (the attack/release pair around " +
            "set_clip_volume's steady-state level). Each field optional; at least one must be " +
            "set. 0.0 disables. Sum of fade-in + fade-out must not exceed the clip's timeline " +
            "duration. Audio clips only. Emits a timeline snapshot so revert_timeline can undo."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("fadeInSeconds") {
                put("type", "number")
                put("description", "Fade-in ramp length in seconds. 0.0 disables. Omit to keep current.")
            }
            putJsonObject("fadeOutSeconds") {
                put("type", "number")
                put("description", "Fade-out ramp length in seconds. 0.0 disables. Omit to keep current.")
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.fadeInSeconds != null || input.fadeOutSeconds != null) {
            "fade_audio_clip requires at least one of fadeInSeconds / fadeOutSeconds."
        }
        input.fadeInSeconds?.let {
            require(it.isFinite() && it >= 0f) { "fadeInSeconds must be finite and >= 0 (got $it)" }
        }
        input.fadeOutSeconds?.let {
            require(it.isFinite() && it >= 0f) { "fadeOutSeconds must be finite and >= 0 (got $it)" }
        }

        var foundTrackId: String? = null
        var oldIn = 0f
        var oldOut = 0f
        var newIn = 0f
        var newOut = 0f
        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId }
                if (target == null) {
                    track
                } else {
                    val audio = target as? Clip.Audio ?: error(
                        "fade_audio_clip only applies to audio clips; clip ${input.clipId} " +
                            "is a ${target::class.simpleName}.",
                    )
                    foundTrackId = track.id.value
                    oldIn = audio.fadeInSeconds
                    oldOut = audio.fadeOutSeconds
                    newIn = input.fadeInSeconds ?: oldIn
                    newOut = input.fadeOutSeconds ?: oldOut
                    val clipDurationSeconds =
                        audio.timeRange.duration.toDouble(DurationUnit.SECONDS).toFloat()
                    require(newIn + newOut <= clipDurationSeconds + 1e-3f) {
                        "fadeIn ($newIn) + fadeOut ($newOut) would exceed clip duration " +
                            "($clipDurationSeconds); fades would overlap."
                    }
                    val rebuilt = track.clips.map { clip ->
                        if (clip.id == audio.id) {
                            audio.copy(fadeInSeconds = newIn, fadeOutSeconds = newOut)
                        } else {
                            clip
                        }
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
                error("clip ${input.clipId} not found in project ${input.projectId}")
            }
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "fade audio ${input.clipId}",
            outputForLlm = "Set fade on audio clip ${input.clipId} (track $foundTrackId): " +
                "in $oldIn→$newIn s, out $oldOut→$newOut s. Timeline snapshot: ${snapshotId.value}",
            data = Output(
                clipId = input.clipId,
                trackId = foundTrackId!!,
                oldFadeInSeconds = oldIn,
                newFadeInSeconds = newIn,
                oldFadeOutSeconds = oldOut,
                newFadeOutSeconds = newOut,
            ),
        )
    }
}
