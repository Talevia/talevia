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
import kotlin.time.DurationUnit

/**
 * Set fade-in / fade-out ramps on one or many audio clips atomically. The sibling
 * of [SetClipVolumeTool]: `set_clip_volumes` controls steady-state level; this
 * controls the attack/release envelope.
 *
 * Per-item shape — each entry carries its own clipId + fadeIn/fadeOut so one
 * call can "2s fade-in on the music, 1s fade-out on the ambience" in a single
 * atomic edit.
 *
 * Each item's fields are optional; at least one must be set. Unspecified fields
 * inherit from the clip's current value. Sum of fade-in + fade-out must not
 * exceed the clip's timeline duration — overlapping fades have no well-defined
 * envelope. Audio clips only. All-or-nothing; one snapshot per call.
 */
class FadeAudioClipTool(
    private val store: ProjectStore,
) : Tool<FadeAudioClipTool.Input, FadeAudioClipTool.Output> {

    @Serializable data class Item(
        val clipId: String,
        /** Fade-in ramp length in seconds. 0.0 disables; omit to keep current. */
        val fadeInSeconds: Float? = null,
        /** Fade-out ramp length in seconds. 0.0 disables; omit to keep current. */
        val fadeOutSeconds: Float? = null,
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
        val clipId: String,
        val trackId: String,
        val oldFadeInSeconds: Float,
        val newFadeInSeconds: Float,
        val oldFadeOutSeconds: Float,
        val newFadeOutSeconds: Float,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id = "fade_audio_clips"
    override val helpText =
        "Set fade-in / fade-out envelope on one or many audio clips atomically. Each item must " +
            "specify at least one of fadeInSeconds / fadeOutSeconds (0.0 disables). Sum of the " +
            "two must not exceed the clip's timeline duration. Audio clips only. All-or-nothing. " +
            "One snapshot per call."
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
                put("description", "Fade edits to apply. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("clipId") { put("type", "string") }
                        putJsonObject("fadeInSeconds") {
                            put("type", "number")
                            put("description", "Fade-in ramp seconds. 0.0 disables. Omit to keep current.")
                        }
                        putJsonObject("fadeOutSeconds") {
                            put("type", "number")
                            put("description", "Fade-out ramp seconds. 0.0 disables. Omit to keep current.")
                        }
                    }
                    put("required", JsonArray(listOf(JsonPrimitive("clipId"))))
                    put("additionalProperties", false)
                }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("items"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        require(input.items.isNotEmpty()) { "items must not be empty" }
        input.items.forEachIndexed { idx, item ->
            require(item.fadeInSeconds != null || item.fadeOutSeconds != null) {
                "items[$idx] (${item.clipId}): at least one of fadeInSeconds / fadeOutSeconds required"
            }
            item.fadeInSeconds?.let {
                require(it.isFinite() && it >= 0f) {
                    "items[$idx] (${item.clipId}): fadeInSeconds must be finite and >= 0 (got $it)"
                }
            }
            item.fadeOutSeconds?.let {
                require(it.isFinite() && it >= 0f) {
                    "items[$idx] (${item.clipId}): fadeOutSeconds must be finite and >= 0 (got $it)"
                }
            }
        }

        val pid = ctx.resolveProjectId(input.projectId)
        val results = mutableListOf<ItemResult>()

        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            input.items.forEachIndexed { idx, item ->
                val hit = tracks.firstNotNullOfOrNull { track ->
                    track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
                } ?: error("items[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                val (track, clip) = hit
                val audio = clip as? Clip.Audio ?: error(
                    "items[$idx]: fade_audio_clips only applies to audio clips; clip ${item.clipId} " +
                        "is a ${clip::class.simpleName}.",
                )
                val oldIn = audio.fadeInSeconds
                val oldOut = audio.fadeOutSeconds
                val newIn = item.fadeInSeconds ?: oldIn
                val newOut = item.fadeOutSeconds ?: oldOut
                val clipDurationSeconds = audio.timeRange.duration.toDouble(DurationUnit.SECONDS).toFloat()
                require(newIn + newOut <= clipDurationSeconds + 1e-3f) {
                    "items[$idx] (${item.clipId}): fadeIn ($newIn) + fadeOut ($newOut) would exceed " +
                        "clip duration ($clipDurationSeconds); fades would overlap."
                }
                val rebuilt = track.clips.map { c ->
                    if (c.id == audio.id) audio.copy(fadeInSeconds = newIn, fadeOutSeconds = newOut) else c
                }
                val newTrack = when (track) {
                    is Track.Video -> track.copy(clips = rebuilt)
                    is Track.Audio -> track.copy(clips = rebuilt)
                    is Track.Subtitle -> track.copy(clips = rebuilt)
                    is Track.Effect -> track.copy(clips = rebuilt)
                }
                tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
                results += ItemResult(
                    clipId = item.clipId,
                    trackId = track.id.value,
                    oldFadeInSeconds = oldIn,
                    newFadeInSeconds = newIn,
                    oldFadeOutSeconds = oldOut,
                    newFadeOutSeconds = newOut,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "fade audio × ${results.size}",
            outputForLlm = "Set fades on ${results.size} audio clip(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
        )
    }
}
