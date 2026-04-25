package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.ProjectStore
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolApplicability
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.video.filter.FILTER_ACTION_VERBS
import io.talevia.core.tool.builtin.video.filter.FILTER_ACTION_VERBS_BY_ID
import io.talevia.core.tool.builtin.video.filter.FilterActionDispatchContext
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
 * Apply or remove named filters on one or many video clips — the
 * consolidated action-dispatched form that replaces
 * `ApplyFilterTool` + `RemoveFilterTool`
 * (`debt-consolidate-video-filter-lut-apply-remove` first pass,
 * 2026-04-23).
 *
 * The apply and remove branches share the same entity (video-clip
 * filters, by `filterName`) so consolidating saves one LLM tool-spec
 * entry (~300 tokens per turn) while preserving both operations
 * behaviour-exactly. Cycle 153 reversed the prior "ApplyLutTool stays
 * separate" decision and absorbed it as `action="apply_lut"`: the
 * LUT-specific semantics (style_bible resolution + sourceBinding
 * cascade) live in their own dispatch arm rather than muddling the
 * shared apply/remove path, so the filter_action surface stays clean
 * while still saving the standalone tool's spec entry.
 *
 * ## Input contract
 *
 * - `action` = `"apply"`, `"remove"`, or `"apply_lut"` (required).
 * - apply / remove: `filterName` required.
 * - apply_lut: `clipIds` required + exactly one of `lutAssetId` or
 *   `styleBibleId` (mutually exclusive). Each affected clip gets a
 *   `Filter(name="lut", assetId=…)` appended; the style_bible path
 *   also adds the style_bible nodeId to each clip's `sourceBinding`.
 * - `action="apply"`: pass **exactly one** selector — `clipIds`
 *   (explicit list, single-clip = 1-element list), `trackId` (every
 *   clip on one track), or `allVideoClips=true`. `params` is
 *   optional. Non-video clips silently skipped; unresolvable clipIds
 *   reported in `skipped`.
 * - `action="remove"`: `clipIds` required (no `trackId` /
 *   `allVideoClips` shortcut — mirrors the previous
 *   `remove_filter`'s semantics). Idempotent per clip. Non-video or
 *   unresolvable clipIds abort the whole batch.
 *
 * The remove path intentionally doesn't gain `trackId` /
 * `allVideoClips` shortcuts in this cycle — broadening its selector
 * semantics is a separate design decision; this consolidation is
 * behaviour-preserving only.
 *
 * ## Output
 *
 * `action="apply"` populates `appliedClipIds` + `skipped`.
 * `action="remove"` populates `removed` (per-clip count + remaining
 * count + total). Both actions emit exactly one
 * `Part.TimelineSnapshot`; `revert_timeline` walks the batch back in
 * one step.
 */
class FilterActionTool(
    private val store: ProjectStore,
) : Tool<FilterActionTool.Input, FilterActionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /** `"apply"`, `"remove"`, or `"apply_lut"`. Case-sensitive. */
        val action: String,
        /**
         * Required for `apply` / `remove`; ignored for `apply_lut` (the
         * filter name is hard-coded to `"lut"` so the engine layer can
         * dispatch it through the LUT-specific handler).
         */
        val filterName: String = "",
        /** Apply-only. Numeric parameters attached to the filter (e.g. `{"intensity": 0.5}`). */
        val params: Map<String, Float> = emptyMap(),
        /**
         * Both paths. Apply: one of three selectors (mutually exclusive with
         * `trackId` / `allVideoClips`). Remove: required list of video clips.
         * Apply-LUT: required list of video clips (no track / all-clips
         * selector — broadcasting is the caller's responsibility for now).
         */
        val clipIds: List<String> = emptyList(),
        /** Apply-only. Target every clip on this track id. */
        val trackId: String? = null,
        /** Apply-only. Target every video clip in the project. */
        val allVideoClips: Boolean = false,
        /**
         * `apply_lut` only. Asset id of an imported LUT (.cube / .3dl).
         * Mutually exclusive with [styleBibleId].
         */
        val lutAssetId: String? = null,
        /**
         * `apply_lut` only. Source-node id of a `core.consistency.style_bible`.
         * The node's `lutReference` is resolved at apply time. Each affected
         * clip also gets the style_bible's nodeId added to its
         * `sourceBinding` so future staleness machinery can propagate
         * edits through the DAG (VISION §3.3). Mutually exclusive with
         * [lutAssetId].
         */
        val styleBibleId: String? = null,
    )

    @Serializable data class Skipped(
        val clipId: String,
        val reason: String,
    )

    @Serializable data class RemoveItemResult(
        val clipId: String,
        val removedCount: Int,
        val remainingFilterCount: Int,
    )

    @Serializable data class LutItemResult(
        val clipId: String,
        val filterCount: Int,
    )

    @Serializable data class Output(
        val projectId: String,
        val action: String,
        val filterName: String,
        val snapshotId: String,
        /** Apply-only: ids of clips that received the filter. Empty on remove. */
        val appliedClipIds: List<String> = emptyList(),
        /** Apply-only: clips excluded by selector resolution. Empty on remove. */
        val skipped: List<Skipped> = emptyList(),
        /** Remove-only: per-clip removal counts. Empty on apply. */
        val removed: List<RemoveItemResult> = emptyList(),
        /** Remove-only: sum of `removedCount`. Zero on apply. */
        val totalRemoved: Int = 0,
        /** Apply-LUT-only: resolved LUT asset id (echo from input or derived from style_bible). Empty on every other action. */
        val lutAssetId: String = "",
        /** Apply-LUT-only: per-clip LUT-application result. Empty on every other action. */
        val lutResults: List<LutItemResult> = emptyList(),
        /** Apply-LUT-only: echoes the input `styleBibleId` when the LUT was resolved through one. Null otherwise. */
        val lutStyleBibleId: String? = null,
    )

    override val id: String = "filter_action"
    override val helpText: String =
        "Apply or remove named filters on video clips atomically. " +
            "action=apply + filterName (brightness|saturation|blur|vignette|lut|…) + exactly one " +
            "selector (clipIds | trackId | allVideoClips=true); non-video clips skipped, missing " +
            "clipIds reported in `skipped`. action=remove + filterName + required clipIds; " +
            "idempotent per clip but unresolvable/non-video clipIds abort. " +
            "action=apply_lut + clipIds + exactly one of (lutAssetId | styleBibleId) attaches a 3D " +
            "LUT (.cube / .3dl) to one or many video clips; the style_bible path resolves " +
            "lutReference at apply time AND binds each clip to the style_bible nodeId so " +
            "future edits cascade through stale-clip detection. One snapshot per call."
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
            putJsonObject("action") {
                put("type", "string")
                put(
                    "description",
                    "`apply` to attach a filter, `remove` to drop filters by name, " +
                        "`apply_lut` to attach a 3D LUT (.cube / .3dl) by asset id or via a " +
                        "style_bible nodeId.",
                )
                put(
                    "enum",
                    JsonArray(
                        listOf(JsonPrimitive("apply"), JsonPrimitive("remove"), JsonPrimitive("apply_lut")),
                    ),
                )
            }
            putJsonObject("filterName") {
                put("type", "string")
                put("description", "Engine-specific filter name (brightness, saturation, blur, vignette, lut, …).")
            }
            putJsonObject("params") {
                put("type", "object")
                put("description", "Apply-only. Numeric parameters (e.g. {\"intensity\": 0.5}).")
                putJsonObject("additionalProperties") { put("type", "number") }
            }
            putJsonObject("clipIds") {
                put("type", "array")
                put(
                    "description",
                    "Apply: single-clip = 1-element list; mutually exclusive with trackId / allVideoClips. " +
                        "Remove: required list of video clip ids.",
                )
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("trackId") {
                put("type", "string")
                put(
                    "description",
                    "Apply-only. Target every video clip on this track id. Mutually exclusive with clipIds / allVideoClips.",
                )
            }
            putJsonObject("allVideoClips") {
                put("type", "boolean")
                put(
                    "description",
                    "Apply-only. Target every video clip in the project. Mutually exclusive with clipIds / trackId.",
                )
            }
            putJsonObject("lutAssetId") {
                put("type", "string")
                put(
                    "description",
                    "action=apply_lut only. Asset id of an imported LUT (.cube / .3dl). " +
                        "Mutually exclusive with styleBibleId.",
                )
            }
            putJsonObject("styleBibleId") {
                put("type", "string")
                put(
                    "description",
                    "action=apply_lut only. Source-node id of a core.consistency.style_bible. " +
                        "Its lutReference is resolved at apply time. Mutually exclusive with lutAssetId.",
                )
            }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("action"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val verb = FILTER_ACTION_VERBS_BY_ID[input.action]
            ?: error(
                "unknown action '${input.action}'; accepted: " +
                    FILTER_ACTION_VERBS.joinToString { it.id },
            )
        val pid = ctx.resolveProjectId(input.projectId)
        return verb.run(input, ctx, FilterActionDispatchContext(store, pid))
    }
}
