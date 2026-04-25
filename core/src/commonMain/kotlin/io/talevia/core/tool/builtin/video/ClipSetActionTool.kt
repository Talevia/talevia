package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.ProjectStore
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
 * Text-clip body / style edits do NOT live here — cycle 152 absorbed
 * the standalone `EditTextClipTool` into
 * `clip_action(action="edit_text", editTextItems=…)` rather than
 * `set_clip_field` because the `editTextItems` shape carries 7 optional
 * partial-patch fields (newText / fontFamily / fontSize / color /
 * backgroundColor / bold / italic) which don't collapse into a single
 * typed-payload-per-item pattern that this tool's `field=` discriminator
 * relies on.
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
            "volume" -> executeClipSetVolume(store, pid, input, ctx)
            "transform" -> executeClipSetTransform(store, pid, input, ctx)
            "sourceBinding" -> executeClipSetSourceBinding(store, pid, input, ctx)
            else -> error(
                "unknown field '${input.field}'; accepted: volume, transform, sourceBinding",
            )
        }
    }
}

// `stringProp` / `numberProp` live in `ClipActionToolSchema.kt` as
// `internal` — see note there. Two file-private copies in this package
// silently break Kotlin/Native's `$default`-arg synthesizer.

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
