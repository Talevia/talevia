package io.talevia.core.tool.builtin.video

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
 * Edit one or many clips' visual [Transform] fields in place — opacity, scale,
 * translate, rotate. Per-item shape: each entry carries its own clipId + any
 * subset of transform fields, so a batch can set different transforms on
 * different clips in a single atomic edit.
 *
 * Semantics:
 *  - Each item must specify at least one transform field.
 *  - Unspecified fields inherit from that clip's *current* first transform
 *    (or the default `Transform()` when the list is empty).
 *  - The tool writes `transforms = listOf(newTransform)` per clip — v1
 *    normalizes to one transform per clip.
 *
 * Clamps mirror common-sense ranges:
 *  - `opacity` ∈ [0, 1].
 *  - `scaleX` / `scaleY` > 0.
 *  - `rotationDeg` unclamped.
 *  - `translateX` / `translateY` unclamped — engine-defined units.
 *
 * All-or-nothing: any item's validation failure (invalid value, missing clip,
 * no fields set) aborts the whole batch. One `Part.TimelineSnapshot` per call.
 */
class SetClipTransformTool(
    private val store: ProjectStore,
) : Tool<SetClipTransformTool.Input, SetClipTransformTool.Output> {

    @Serializable data class Item(
        val clipId: String,
        val translateX: Float? = null,
        val translateY: Float? = null,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
        val rotationDeg: Float? = null,
        val opacity: Float? = null,
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
        val oldTransform: Transform,
        val newTransform: Transform,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id = "set_clip_transforms"
    override val helpText =
        "Set visual transforms (opacity, scale, translate, rotate) on one or many clips " +
            "atomically. Each item carries its own clipId + any subset of transform fields; " +
            "unspecified fields inherit the clip's current values. Normalizes each clip's " +
            "transforms list to a single transform. Preserves clip id + other attached state. " +
            "All-or-nothing. One timeline snapshot per call."
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
                put("description", "Transform edits to apply. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("clipId") { put("type", "string") }
                        putJsonObject("translateX") { put("type", "number") }
                        putJsonObject("translateY") { put("type", "number") }
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
            val overrides = listOfNotNull(
                item.translateX, item.translateY,
                item.scaleX, item.scaleY,
                item.rotationDeg, item.opacity,
            )
            require(overrides.isNotEmpty()) {
                "items[$idx] (${item.clipId}): at least one of translate/scale/rotation/opacity required"
            }
            item.opacity?.let {
                require(it.isFinite() && it in 0f..1f) {
                    "items[$idx] (${item.clipId}): opacity must be in [0, 1] (got $it)"
                }
            }
            item.scaleX?.let {
                require(it.isFinite() && it > 0f) { "items[$idx] (${item.clipId}): scaleX must be > 0 (got $it)" }
            }
            item.scaleY?.let {
                require(it.isFinite() && it > 0f) { "items[$idx] (${item.clipId}): scaleY must be > 0 (got $it)" }
            }
            item.translateX?.let { require(it.isFinite()) { "items[$idx] (${item.clipId}): translateX must be finite" } }
            item.translateY?.let { require(it.isFinite()) { "items[$idx] (${item.clipId}): translateY must be finite" } }
            item.rotationDeg?.let { require(it.isFinite()) { "items[$idx] (${item.clipId}): rotationDeg must be finite" } }
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
                val base = clip.transforms.firstOrNull() ?: Transform()
                val merged = base.copy(
                    translateX = item.translateX ?: base.translateX,
                    translateY = item.translateY ?: base.translateY,
                    scaleX = item.scaleX ?: base.scaleX,
                    scaleY = item.scaleY ?: base.scaleY,
                    rotationDeg = item.rotationDeg ?: base.rotationDeg,
                    opacity = item.opacity ?: base.opacity,
                )
                val rebuilt = track.clips.map { c ->
                    if (c.id != clip.id) {
                        c
                    } else {
                        when (c) {
                            is Clip.Video -> c.copy(transforms = listOf(merged))
                            is Clip.Audio -> c.copy(transforms = listOf(merged))
                            is Clip.Text -> c.copy(transforms = listOf(merged))
                        }
                    }
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
                    oldTransform = base,
                    newTransform = merged,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "set transform × ${results.size}",
            outputForLlm = "Set transforms on ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
        )
    }
}
