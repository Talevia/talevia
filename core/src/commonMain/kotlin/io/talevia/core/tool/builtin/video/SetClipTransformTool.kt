package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.Track
import io.talevia.core.domain.Transform
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
 * Edit a clip's visual [Transform] in place — opacity, scale, translate,
 * rotate. The `Transform` data class has existed on every clip since M0,
 * but no tool ever set it, so the fields were dead state. This tool is
 * the "set X to Y" sibling of [SetClipVolumeTool] for visual layout.
 *
 * Use cases:
 *  - "make the intro text smaller" (scaleX / scaleY)
 *  - "fade the watermark" (opacity)
 *  - "move the logo to the corner for picture-in-picture" (translate + scale)
 *  - "rotate the title 10 degrees" (rotationDeg)
 *
 * Semantics: every input field is optional, and at least one must be set.
 * Unspecified fields inherit from the clip's *current* first transform
 * (or the default `Transform()` when the list is empty). The tool writes
 * `transforms = listOf(newTransform)` — v1 normalizes to one transform per
 * clip. If a user ever needs explicit composition ("translate, then scale,
 * then rotate as three passes"), that becomes a separate `push_transform`
 * tool; most real edits are one transform with overrides applied.
 *
 * Clamps mirror common-sense ranges:
 *  - `opacity` ∈ [0, 1]. Outside that is meaningless on screen.
 *  - `scaleX` / `scaleY` > 0. Zero collapses the clip; negatives are an
 *    unsupported way to request a mirror (a real `flip_clip` tool would
 *    own that).
 *  - `rotationDeg` unclamped — any float is valid; the renderer takes
 *    rotation modulo 360 anyway.
 *  - `translateX` / `translateY` unclamped — units are engine-defined
 *    (pixels on FFmpeg/AVFoundation, normalized on Media3) and clamping
 *    here would bake the wrong model.
 *
 * Emits a `Part.TimelineSnapshot` so `revert_timeline` can undo.
 * Preserves clip id, track, timeRange, sourceRange, sourceBinding,
 * filters, and every other attached state.
 */
class SetClipTransformTool(
    private val store: ProjectStore,
) : Tool<SetClipTransformTool.Input, SetClipTransformTool.Output> {

    @Serializable data class Input(
        val projectId: String,
        val clipId: String,
        val translateX: Float? = null,
        val translateY: Float? = null,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
        val rotationDeg: Float? = null,
        val opacity: Float? = null,
    )

    @Serializable data class Output(
        val clipId: String,
        val trackId: String,
        val oldTransform: Transform,
        val newTransform: Transform,
    )

    override val id = "set_clip_transform"
    override val helpText =
        "Set a clip's visual transform (opacity, scale, translate, rotate). Every field is " +
            "optional; unspecified fields inherit the current value. Normalizes the clip's " +
            "transforms list to a single transform. Preserves clip id + every other attached " +
            "state. Emits a timeline snapshot so revert_timeline can undo."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("translateX") {
                put("type", "number")
                put("description", "Horizontal offset. Engine-defined units (pixels on FFmpeg/AVFoundation).")
            }
            putJsonObject("translateY") {
                put("type", "number")
                put("description", "Vertical offset. Engine-defined units.")
            }
            putJsonObject("scaleX") {
                put("type", "number")
                put("description", "Horizontal scale multiplier. Must be > 0. 1.0 = unchanged.")
            }
            putJsonObject("scaleY") {
                put("type", "number")
                put("description", "Vertical scale multiplier. Must be > 0. 1.0 = unchanged.")
            }
            putJsonObject("rotationDeg") {
                put("type", "number")
                put("description", "Rotation in degrees. Unclamped; renderer takes modulo 360.")
            }
            putJsonObject("opacity") {
                put("type", "number")
                put("description", "Opacity in [0, 1]. 0 = fully transparent, 1 = fully opaque.")
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val overrides = listOfNotNull(
            input.translateX, input.translateY,
            input.scaleX, input.scaleY,
            input.rotationDeg, input.opacity,
        )
        require(overrides.isNotEmpty()) {
            "set_clip_transform requires at least one of translateX/Y, scaleX/Y, rotationDeg, opacity."
        }
        input.opacity?.let {
            require(it.isFinite() && it in 0f..1f) {
                "opacity must be in [0, 1] (got $it)"
            }
        }
        input.scaleX?.let {
            require(it.isFinite() && it > 0f) { "scaleX must be > 0 (got $it)" }
        }
        input.scaleY?.let {
            require(it.isFinite() && it > 0f) { "scaleY must be > 0 (got $it)" }
        }
        input.translateX?.let { require(it.isFinite()) { "translateX must be finite" } }
        input.translateY?.let { require(it.isFinite()) { "translateY must be finite" } }
        input.rotationDeg?.let { require(it.isFinite()) { "rotationDeg must be finite" } }

        var foundTrackId: String? = null
        var oldTransform = Transform()
        var newTransform = Transform()
        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId }
                if (target == null) {
                    track
                } else {
                    foundTrackId = track.id.value
                    val base = target.transforms.firstOrNull() ?: Transform()
                    oldTransform = base
                    val merged = base.copy(
                        translateX = input.translateX ?: base.translateX,
                        translateY = input.translateY ?: base.translateY,
                        scaleX = input.scaleX ?: base.scaleX,
                        scaleY = input.scaleY ?: base.scaleY,
                        rotationDeg = input.rotationDeg ?: base.rotationDeg,
                        opacity = input.opacity ?: base.opacity,
                    )
                    newTransform = merged
                    val rebuilt = track.clips.map { clip ->
                        if (clip.id != target.id) {
                            clip
                        } else {
                            when (clip) {
                                is Clip.Video -> clip.copy(transforms = listOf(merged))
                                is Clip.Audio -> clip.copy(transforms = listOf(merged))
                                is Clip.Text -> clip.copy(transforms = listOf(merged))
                            }
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
            title = "set transform ${input.clipId}",
            outputForLlm = "Set transform on clip ${input.clipId} (track $foundTrackId): " +
                "$oldTransform → $newTransform. Timeline snapshot: ${snapshotId.value}",
            data = Output(
                clipId = input.clipId,
                trackId = foundTrackId!!,
                oldTransform = oldTransform,
                newTransform = newTransform,
            ),
        )
    }
}
