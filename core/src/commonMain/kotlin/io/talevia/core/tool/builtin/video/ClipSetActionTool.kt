package io.talevia.core.tool.builtin.video

import io.talevia.core.SourceNodeId
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
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Three-way clip *setter* — the consolidated `field`-dispatched form that
 * replaces the previous `SetClipVolumeTool` + `SetClipTransformTool` +
 * `SetClipSourceBindingTool` trio (`debt-video-clip-consolidate-set-family`,
 * 2026-04-24). Mirrors `ClipActionTool` / `SourceNodeActionTool` / the
 * `TransitionActionTool` precedent, but on an orthogonal axis: the verb
 * tools reshape the timeline (add / remove / split / …) while this one
 * only writes a single typed field on existing clips. Two tools carry the
 * two axes; growth on each stays bounded.
 *
 * Per-call batch — each invocation carries its own `field` + one nullable
 * `*Items` payload. A single call can set `volume` on three clips or set
 * `transforms` on five clips, but not mix fields — the `field` argument
 * picks exactly one of `volumeItems` / `transformItems` / `sourceBindingItems`
 * and any foreign payload is rejected before any mutation. All-or-nothing:
 * any item's validation failure (invalid value, missing clip, wrong kind,
 * unknown source-node id) aborts the whole batch and leaves `talevia.json`
 * untouched. One `Part.TimelineSnapshot` per call so `revert_timeline`
 * walks back the whole batch in one step.
 *
 * `EditTextClipTool` stays standalone — text-style fields (font / size /
 * colour / alignment) have a distinct per-field shape that doesn't collapse
 * into the single-typed-payload pattern; the bullet explicitly scopes it
 * out.
 *
 * ## Fields
 *
 * - `field="volume"` + `volumeItems` (clipId, volume). Absolute multiplier
 *   in [0, 4]; 0.0 mutes, 1.0 unchanged, caps at 4× (most renderers clip
 *   beyond). Audio clips only — video / text clips have no `volume`
 *   field today; the legacy message "`set_clip_volumes` only applies to
 *   audio clips" is preserved verbatim so the existing agent prompts
 *   still match.
 * - `field="transform"` + `transformItems` (clipId, any subset of
 *   translateX / translateY / scaleX / scaleY / rotationDeg / opacity;
 *   at least one). Unspecified fields inherit from the clip's current
 *   *first* transform (or `Transform()` when the list is empty). Writes
 *   `transforms = listOf(merged)` — v1 normalises to one transform per
 *   clip. Clamps: `opacity ∈ [0, 1]`, `scaleX` / `scaleY > 0`.
 * - `field="sourceBinding"` + `sourceBindingItems` (clipId,
 *   sourceBinding: List<String>). Full replacement set of source-node
 *   ids per item. Empty list clears that clip's binding. Every id must
 *   resolve in `project.source.byId` — unknown ids fail loud with the
 *   missing set named. Works on all three Clip variants.
 */
class ClipSetActionTool(
    private val store: ProjectStore,
) : Tool<ClipSetActionTool.Input, ClipSetActionTool.Output> {

    /** One volume edit. Mirrors the legacy `SetClipVolumeTool.Item`. */
    @Serializable data class VolumeItem(
        val clipId: String,
        /** Absolute multiplier in [0, 4]. 1.0 = unchanged, 0.0 = mute. */
        val volume: Float,
    )

    /** One transform edit. Mirrors the legacy `SetClipTransformTool.Item`. */
    @Serializable data class TransformItem(
        val clipId: String,
        val translateX: Float? = null,
        val translateY: Float? = null,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
        val rotationDeg: Float? = null,
        val opacity: Float? = null,
    )

    /** One source-binding rebind. Mirrors the legacy `SetClipSourceBindingTool.Item`. */
    @Serializable data class SourceBindingItem(
        val clipId: String,
        /** Full replacement set of source-node ids. Empty list clears the binding. */
        val sourceBinding: List<String>,
    )

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /** One of `"volume"`, `"transform"`, `"sourceBinding"`. Case-sensitive. */
        val field: String,
        /** Required when `field="volume"`. */
        val volumeItems: List<VolumeItem>? = null,
        /** Required when `field="transform"`. */
        val transformItems: List<TransformItem>? = null,
        /** Required when `field="sourceBinding"`. */
        val sourceBindingItems: List<SourceBindingItem>? = null,
    )

    @Serializable data class VolumeResult(
        val clipId: String,
        val trackId: String,
        val oldVolume: Float,
        val newVolume: Float,
    )

    @Serializable data class TransformResult(
        val clipId: String,
        val trackId: String,
        val oldTransform: Transform,
        val newTransform: Transform,
    )

    @Serializable data class SourceBindingResult(
        val clipId: String,
        val previousBinding: List<String>,
        val newBinding: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val field: String,
        val snapshotId: String,
        /** Populated when `field="volume"`. */
        val volumeResults: List<VolumeResult> = emptyList(),
        /** Populated when `field="transform"`. */
        val transformResults: List<TransformResult> = emptyList(),
        /** Populated when `field="sourceBinding"`. */
        val sourceBindingResults: List<SourceBindingResult> = emptyList(),
    )

    override val id: String = "clip_set_action"
    override val helpText: String =
        "Three-way clip field setter dispatching on `field`. " +
            "`field=\"volume\"` + `volumeItems` (clipId, volume) sets playback volume on audio " +
            "clips; 0.0 mutes, 1.0 unchanged, up to 4.0 amplifies; video / text clips rejected. " +
            "`field=\"transform\"` + `transformItems` (clipId, any subset of translate/scale/" +
            "rotation/opacity) sets the visual transform; unspecified fields inherit current " +
            "values; normalizes to one Transform per clip. " +
            "`field=\"sourceBinding\"` + `sourceBindingItems` (clipId, sourceBinding list) " +
            "replaces each clip's set of source-node ids (empty list clears); unknown ids fail. " +
            "Each call mutates one field across a batch; use separate calls for different fields. " +
            "All-or-nothing; one timeline snapshot per call."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")
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
            putJsonObject("field") {
                put("type", "string")
                put("description", "One of: volume, transform, sourceBinding.")
                put(
                    "enum",
                    JsonArray(listOf("volume", "transform", "sourceBinding").map(::JsonPrimitive)),
                )
            }
            putJsonObject("volumeItems") {
                itemArray(
                    "Required when field=volume. Volume edits to apply; at least one.",
                    required = listOf("clipId", "volume"),
                ) {
                    stringProp("clipId")
                    numberProp("volume", "Absolute multiplier in [0, 4]. 1.0 = unchanged, 0.0 = mute.")
                }
            }
            putJsonObject("transformItems") {
                itemArray(
                    "Required when field=transform. Transform edits to apply; at least one. " +
                        "Each item must set at least one of translate/scale/rotation/opacity.",
                    required = listOf("clipId"),
                ) {
                    stringProp("clipId")
                    numberProp("translateX")
                    numberProp("translateY")
                    numberProp("scaleX", "Horizontal scale multiplier. Must be > 0. 1.0 = unchanged.")
                    numberProp("scaleY", "Vertical scale multiplier. Must be > 0. 1.0 = unchanged.")
                    numberProp("rotationDeg", "Rotation in degrees. Unclamped; renderer takes modulo 360.")
                    numberProp("opacity", "Opacity in [0, 1]. 0 = fully transparent, 1 = fully opaque.")
                }
            }
            putJsonObject("sourceBindingItems") {
                itemArray(
                    "Required when field=sourceBinding. Rebind operations; at least one. " +
                        "Empty sourceBinding list clears that clip's binding.",
                    required = listOf("clipId", "sourceBinding"),
                ) {
                    stringProp("clipId")
                    putJsonObject("sourceBinding") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put(
                            "description",
                            "Full replacement set of source-node ids. Empty list clears the binding.",
                        )
                    }
                }
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive("field"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        return when (input.field) {
            "volume" -> executeVolume(pid, input, ctx)
            "transform" -> executeTransform(pid, input, ctx)
            "sourceBinding" -> executeSourceBinding(pid, input, ctx)
            else -> error(
                "unknown field '${input.field}'; accepted: volume, transform, sourceBinding",
            )
        }
    }

    /**
     * Ensure only the payload list for [field] is present; reject the others loudly.
     * Central shared validator for all three field branches.
     */
    private fun rejectForeign(field: String, input: Input) {
        val foreign = buildList {
            if (field != "volume" && input.volumeItems != null) add("volumeItems")
            if (field != "transform" && input.transformItems != null) add("transformItems")
            if (field != "sourceBinding" && input.sourceBindingItems != null) add("sourceBindingItems")
        }
        require(foreign.isEmpty()) {
            "field=$field rejects ${foreign.joinToString(" / ")} — use this field's own payload list"
        }
    }

    private suspend fun executeVolume(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val items = input.volumeItems ?: error("field=volume requires `volumeItems`")
        rejectForeign("volume", input)
        require(items.isNotEmpty()) { "volumeItems must not be empty" }
        items.forEachIndexed { idx, item ->
            require(item.volume.isFinite()) {
                "volumeItems[$idx]: volume must be finite (got ${item.volume})"
            }
            require(item.volume >= 0f) {
                "volumeItems[$idx]: volume must be >= 0 (got ${item.volume})"
            }
            require(item.volume <= MAX_VOLUME) {
                "volumeItems[$idx]: volume must be <= $MAX_VOLUME (got ${item.volume}); " +
                    "clip-level gain beyond that belongs in mix-time staging."
            }
        }

        val results = mutableListOf<VolumeResult>()
        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            items.forEachIndexed { idx, item ->
                val hit = tracks.firstNotNullOfOrNull { track ->
                    track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
                } ?: error("volumeItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
                val (track, clip) = hit
                val audio = clip as? Clip.Audio ?: error(
                    "volumeItems[$idx]: set_clip_volumes only applies to audio clips; clip ${item.clipId} " +
                        "is a ${clip::class.simpleName}.",
                )
                val oldVolume = audio.volume
                val rebuilt = track.clips.map {
                    if (it.id == audio.id) audio.copy(volume = item.volume) else it
                }
                tracks = tracks.map { if (it.id == track.id) withClips(track, rebuilt) else it }
                results += VolumeResult(
                    clipId = item.clipId,
                    trackId = track.id.value,
                    oldVolume = oldVolume,
                    newVolume = item.volume,
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "set volume × ${results.size}",
            outputForLlm = "Set volume on ${results.size} audio clip(s). Snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                field = "volume",
                snapshotId = snapshotId.value,
                volumeResults = results,
            ),
        )
    }

    private suspend fun executeTransform(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val items = input.transformItems ?: error("field=transform requires `transformItems`")
        rejectForeign("transform", input)
        require(items.isNotEmpty()) { "transformItems must not be empty" }
        items.forEachIndexed { idx, item ->
            val overrides = listOfNotNull(
                item.translateX, item.translateY,
                item.scaleX, item.scaleY,
                item.rotationDeg, item.opacity,
            )
            require(overrides.isNotEmpty()) {
                "transformItems[$idx] (${item.clipId}): at least one of translate/scale/rotation/opacity required"
            }
            item.opacity?.let {
                require(it.isFinite() && it in 0f..1f) {
                    "transformItems[$idx] (${item.clipId}): opacity must be in [0, 1] (got $it)"
                }
            }
            item.scaleX?.let {
                require(it.isFinite() && it > 0f) {
                    "transformItems[$idx] (${item.clipId}): scaleX must be > 0 (got $it)"
                }
            }
            item.scaleY?.let {
                require(it.isFinite() && it > 0f) {
                    "transformItems[$idx] (${item.clipId}): scaleY must be > 0 (got $it)"
                }
            }
            item.translateX?.let {
                require(it.isFinite()) { "transformItems[$idx] (${item.clipId}): translateX must be finite" }
            }
            item.translateY?.let {
                require(it.isFinite()) { "transformItems[$idx] (${item.clipId}): translateY must be finite" }
            }
            item.rotationDeg?.let {
                require(it.isFinite()) { "transformItems[$idx] (${item.clipId}): rotationDeg must be finite" }
            }
        }

        val results = mutableListOf<TransformResult>()
        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            items.forEachIndexed { idx, item ->
                val hit = tracks.firstNotNullOfOrNull { track ->
                    track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
                } ?: error("transformItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
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
                tracks = tracks.map { if (it.id == track.id) withClips(track, rebuilt) else it }
                results += TransformResult(
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
            data = Output(
                projectId = pid.value,
                field = "transform",
                snapshotId = snapshotId.value,
                transformResults = results,
            ),
        )
    }

    private suspend fun executeSourceBinding(
        pid: io.talevia.core.ProjectId,
        input: Input,
        ctx: ToolContext,
    ): ToolResult<Output> {
        val items = input.sourceBindingItems
            ?: error("field=sourceBinding requires `sourceBindingItems`")
        rejectForeign("sourceBinding", input)
        require(items.isNotEmpty()) { "sourceBindingItems must not be empty" }

        val results = mutableListOf<SourceBindingResult>()
        val updated = store.mutate(pid) { project ->
            var tracks = project.timeline.tracks
            items.forEachIndexed { idx, item ->
                val newBindingSet = item.sourceBinding.map { SourceNodeId(it) }.toSet()
                val missing = newBindingSet.filter { it !in project.source.byId }
                require(missing.isEmpty()) {
                    "sourceBindingItems[$idx] (${item.clipId}): unknown source node ids: " +
                        missing.joinToString(", ") { it.value }
                }

                val hit = tracks.firstNotNullOfOrNull { track ->
                    track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
                } ?: error(
                    "sourceBindingItems[$idx]: clip ${item.clipId} not found in project ${pid.value}",
                )
                val (track, clip) = hit
                val previousBinding = clip.sourceBinding.map { it.value }.sorted()

                val rebound: Clip = when (clip) {
                    is Clip.Video -> clip.copy(sourceBinding = newBindingSet)
                    is Clip.Audio -> clip.copy(sourceBinding = newBindingSet)
                    is Clip.Text -> clip.copy(sourceBinding = newBindingSet)
                }
                val rebuilt = track.clips.map { if (it.id == clip.id) rebound else it }
                tracks = tracks.map { if (it.id == track.id) withClips(track, rebuilt) else it }
                results += SourceBindingResult(
                    clipId = item.clipId,
                    previousBinding = previousBinding,
                    newBinding = newBindingSet.map { it.value }.sorted(),
                )
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }
        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "rebind × ${results.size}",
            outputForLlm = "Rebound source bindings on ${results.size} clip(s). Snapshot: ${snapshotId.value}",
            data = Output(
                projectId = pid.value,
                field = "sourceBinding",
                snapshotId = snapshotId.value,
                sourceBindingResults = results,
            ),
        )
    }

    private fun withClips(track: Track, clips: List<Clip>): Track = when (track) {
        is Track.Video -> track.copy(clips = clips)
        is Track.Audio -> track.copy(clips = clips)
        is Track.Subtitle -> track.copy(clips = clips)
        is Track.Effect -> track.copy(clips = clips)
    }

    companion object {
        const val MAX_VOLUME: Float = 4.0f
    }
}

private fun JsonObjectBuilder.stringProp(name: String, description: String? = null) = putJsonObject(name) {
    put("type", "string")
    if (description != null) put("description", description)
}

private fun JsonObjectBuilder.numberProp(name: String, description: String? = null) = putJsonObject(name) {
    put("type", "number")
    if (description != null) put("description", description)
}

/** `type=array` + `items=object(properties, required, additionalProperties=false)` — one shape per *Items payload. */
private fun JsonObjectBuilder.itemArray(
    description: String,
    required: List<String>,
    props: JsonObjectBuilder.() -> Unit,
) {
    put("type", "array")
    put("description", description)
    putJsonObject("items") {
        put("type", "object")
        putJsonObject("properties", props)
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
}
