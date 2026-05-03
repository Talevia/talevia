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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * Nine-way clip edit verb — consolidates `AddClipTool` + `RemoveClipTool` +
 * `DuplicateClipTool` (phase-1, 2026-04-23), `MoveClipTool` + `SplitClipTool`
 * + `TrimClipTool` (phase-2, 2026-04-23), `ReplaceClipTool` +
 * `FadeAudioClipTool` (phase-3, 2026-04-24 — fold enabled by the axis
 * split landed earlier the same day), and `EditTextClipTool`
 * (phase-4, cycle 152 — surgical text body / style edits on existing
 * `Clip.Text` clips).
 *
 * **Structure (post axis-split + phase-3, 2026-04-24).** The class itself carries
 * only the LLM-facing surface: nested data classes for input/output
 * payloads, the JSON schema, the tool metadata, the `rejectForeign`
 * one-liner, and the dispatch `when` in [execute]. The actual per-verb
 * business logic lives in sibling files:
 *
 * - [executeClipAdd] / [executeClipDuplicate] — `ClipCreateHandlers.kt`
 * - [executeClipRemove] / [executeClipMove] / [executeClipSplit] /
 *   [executeClipTrim] — `ClipMutateHandlers.kt` (place / shape / audio splits)
 * - [executeClipEditText] — `ClipEditTextHandler.kt`
 *
 * And the small shared helpers (`pickVideoTrack`, `cloneClip`,
 * `splitClip`, `trackKindOf`, …) live in `ClipActionHelpers.kt`. This
 * split was driven by the file crossing the R.5 #4 800-LOC forced-P1
 * threshold (it peaked at 938 LOC after the phase-2 consolidation) —
 * see `debt-split-clip-action-tool-axis` commit body for the axis
 * rationale. No behaviour change; the tool id / helpText / JSON schema
 * are byte-identical to pre-split.
 *
 * Tool id stays `clip_action` — this is an internal refactor, zero
 * LLM-visible surface change, zero test migration cost.
 *
 * Per-action contract summary (details in the dedicated handler's
 * KDoc in its file):
 * - `add` + `addItems` → [executeClipAdd]
 * - `remove` + `clipIds` (+ optional `ripple`) → [executeClipRemove]
 * - `duplicate` + `duplicateItems` → [executeClipDuplicate]
 * - `move` + `moveItems` → [executeClipMove]
 * - `split` + `splitItems` → [executeClipSplit]
 * - `trim` + `trimItems` → [executeClipTrim]
 * - `replace` + `replaceItems` → [executeClipReplace]
 * - `fade` + `fadeItems` → [executeClipFade]
 * - `edit_text` + `editTextItems` → [executeClipEditText]
 */
class ClipActionTool(
    private val store: ProjectStore,
) : Tool<ClipActionTool.Input, ClipActionTool.Output> {

    companion object {
        /**
         * Volume ceiling for `set_volume` items, absorbed from the
         * pre-merger `ClipSetActionTool` (cycle 44 consolidation).
         * Clip-level gain beyond 4× belongs in mix-time staging, not
         * a per-clip multiplier — agents that need louder boost should
         * route through `apply_filter(kind="volume")` instead.
         */
        const val MAX_VOLUME: Float = 4.0f
    }

    /** One add request. Mirrors the legacy `AddClipTool.Item`. */
    @Serializable data class AddItem(
        val assetId: String,
        val timelineStartSeconds: Double? = null,
        val sourceStartSeconds: Double = 0.0,
        val durationSeconds: Double? = null,
        val trackId: String? = null,
    )

    /** One duplicate request. Mirrors the legacy `DuplicateClipTool.Item`. */
    @Serializable data class DuplicateItem(
        val clipId: String,
        val timelineStartSeconds: Double,
        val trackId: String? = null,
    )

    /** One move request. Mirrors the legacy `MoveClipTool.Item`. */
    @Serializable data class MoveItem(
        val clipId: String,
        val timelineStartSeconds: Double? = null,
        val toTrackId: String? = null,
    )

    /** One split request. Mirrors the legacy `SplitClipTool.Item`. */
    @Serializable data class SplitItem(
        val clipId: String,
        val atTimelineSeconds: Double,
    )

    /** One trim request. Mirrors the legacy `TrimClipTool.Item`. */
    @Serializable data class TrimItem(
        val clipId: String,
        val newSourceStartSeconds: Double? = null,
        val newDurationSeconds: Double? = null,
    )

    /** One replace request. Mirrors the legacy `ReplaceClipTool.Item`. */
    @Serializable data class ReplaceItem(
        val clipId: String,
        val newAssetId: String,
    )

    /** One fade request. Mirrors the legacy `FadeAudioClipTool.Item`. */
    @Serializable data class FadeItem(
        val clipId: String,
        /** Fade-in ramp length in seconds. 0.0 disables; omit to keep current. */
        val fadeInSeconds: Float? = null,
        /** Fade-out ramp length in seconds. 0.0 disables; omit to keep current. */
        val fadeOutSeconds: Float? = null,
    )

    /**
     * One edit_text request. Mirrors the legacy `EditTextClipTool.Item`.
     * Partial-patch semantics: `null` = keep, value = replace,
     * `""` on `backgroundColor` = clear (set to null). At least one
     * field per item must be non-null; the handler rejects no-op items.
     */
    @Serializable data class EditTextItem(
        val clipId: String,
        /** New body text. Null = keep. Must be non-blank when provided. */
        val newText: String? = null,
        val fontFamily: String? = null,
        val fontSize: Float? = null,
        val color: String? = null,
        /** `""` clears (transparent); non-empty sets; null keeps. */
        val backgroundColor: String? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
    )

    /**
     * One volume edit. Absorbed from the pre-merger `ClipSetActionTool.VolumeItem`
     * (cycle 44). Used by `action="set_volume"`.
     */
    @Serializable data class VolumeItem(
        val clipId: String,
        /** Absolute multiplier in [0, 4]. 1.0 = unchanged, 0.0 = mute. */
        val volume: Float,
    )

    /**
     * One transform edit. Absorbed from the pre-merger
     * `ClipSetActionTool.TransformItem` (cycle 44). Used by
     * `action="set_transform"`.
     */
    @Serializable data class TransformItem(
        val clipId: String,
        val translateX: Float? = null,
        val translateY: Float? = null,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
        val rotationDeg: Float? = null,
        val opacity: Float? = null,
    )

    /**
     * One source-binding rebind. Absorbed from the pre-merger
     * `ClipSetActionTool.SourceBindingItem` (cycle 44). Used by
     * `action="set_sourceBinding"`.
     */
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
        /**
         * One of `"add"`, `"remove"`, `"duplicate"`, `"move"`, `"split"`,
         * `"trim"`, `"replace"`, `"fade"`, `"edit_text"`. Case-sensitive.
         */
        val action: String,
        /** Required when `action="add"`. Clips to insert. */
        val addItems: List<AddItem>? = null,
        /** Required when `action="remove"`. Clip ids to delete. */
        val clipIds: List<String>? = null,
        /** Optional (`action="remove"` only). Close the gap on each removed clip's track. */
        val ripple: Boolean = false,
        /** Required when `action="duplicate"`. Clones to produce. */
        val duplicateItems: List<DuplicateItem>? = null,
        /** Required when `action="move"`. Clips to reposition. */
        val moveItems: List<MoveItem>? = null,
        /** Required when `action="split"`. Clips to split. */
        val splitItems: List<SplitItem>? = null,
        /** Required when `action="trim"`. Clips to re-trim. */
        val trimItems: List<TrimItem>? = null,
        /** Required when `action="replace"`. Clip → new-asset mappings. */
        val replaceItems: List<ReplaceItem>? = null,
        /** Required when `action="fade"`. Audio clip fade-envelope edits. */
        val fadeItems: List<FadeItem>? = null,
        /** Required when `action="edit_text"`. Per-text-clip body / style edits. */
        val editTextItems: List<EditTextItem>? = null,
        /** Required when `action="set_volume"`. Audio-clip volume multipliers. */
        val volumeItems: List<VolumeItem>? = null,
        /** Required when `action="set_transform"`. Visual transform partial overrides. */
        val transformItems: List<TransformItem>? = null,
        /** Required when `action="set_sourceBinding"`. Full-replacement rebinds. */
        val sourceBindingItems: List<SourceBindingItem>? = null,
    )

    @Serializable data class AddResult(
        val clipId: String,
        val timelineStartSeconds: Double,
        val timelineEndSeconds: Double,
        val trackId: String,
    )

    @Serializable data class RemoveResult(
        val clipId: String,
        val trackId: String,
        val durationSeconds: Double,
        val shiftedClipCount: Int,
    )

    @Serializable data class DuplicateResult(
        val originalClipId: String,
        val newClipId: String,
        val sourceTrackId: String,
        val targetTrackId: String,
        val timelineStartSeconds: Double,
        val timelineEndSeconds: Double,
    )

    @Serializable data class MoveResult(
        val clipId: String,
        val fromTrackId: String,
        val toTrackId: String,
        val oldStartSeconds: Double,
        val newStartSeconds: Double,
        val changedTrack: Boolean,
    )

    @Serializable data class SplitResult(
        val originalClipId: String,
        val leftClipId: String,
        val rightClipId: String,
    )

    @Serializable data class TrimResult(
        val clipId: String,
        val trackId: String,
        val newSourceStartSeconds: Double,
        val newDurationSeconds: Double,
        val newTimelineEndSeconds: Double,
    )

    @Serializable data class ReplaceResult(
        val clipId: String,
        val previousAssetId: String,
        val newAssetId: String,
        val sourceBindingIds: List<String>,
    )

    @Serializable data class FadeResult(
        val clipId: String,
        val trackId: String,
        val oldFadeInSeconds: Float,
        val newFadeInSeconds: Float,
        val oldFadeOutSeconds: Float,
        val newFadeOutSeconds: Float,
    )

    @Serializable data class EditTextResult(
        val clipId: String,
        /** Field names that were updated (e.g. `["text", "fontSize", "bold"]`). */
        val updatedFields: List<String>,
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
        val action: String,
        val snapshotId: String,
        /** Populated when `action="add"`. */
        val added: List<AddResult> = emptyList(),
        /** Populated when `action="remove"`. */
        val removed: List<RemoveResult> = emptyList(),
        /** Populated when `action="duplicate"`. */
        val duplicated: List<DuplicateResult> = emptyList(),
        /** Populated when `action="move"`. */
        val moved: List<MoveResult> = emptyList(),
        /** Populated when `action="split"`. */
        val split: List<SplitResult> = emptyList(),
        /** Populated when `action="trim"`. */
        val trimmed: List<TrimResult> = emptyList(),
        /** Populated when `action="replace"`. */
        val replaced: List<ReplaceResult> = emptyList(),
        /** Populated when `action="fade"`. */
        val faded: List<FadeResult> = emptyList(),
        /** Populated when `action="edit_text"`. */
        val editedText: List<EditTextResult> = emptyList(),
        /** Populated when `action="set_volume"`. */
        val volumeResults: List<VolumeResult> = emptyList(),
        /** Populated when `action="set_transform"`. */
        val transformResults: List<TransformResult> = emptyList(),
        /** Populated when `action="set_sourceBinding"`. */
        val sourceBindingResults: List<SourceBindingResult> = emptyList(),
        /** `action="remove"` only — echoes the input `ripple` flag. */
        val rippled: Boolean = false,
    )

    override val id: String = "clip_action"
    override val helpText: String =
        "12-verb clip dispatcher: add / remove / duplicate / move / split / trim / replace / fade / " +
            "edit_text / set_volume / set_transform / set_sourceBinding. Per-action *Items arrays + shapes in " +
            "schema. All-or-nothing per call; one snapshot. text clips reject trim/replace; cross-kind " +
            "trackId rejected. ripple (remove) closes gap, default false. edit_text needs ≥ 1 field/item; " +
            "preserves clip ids / tracks / transforms / timeRanges; backgroundColor=\"\" clears. " +
            "set_volume on audio clips (0..4 multiplier; 1 unchanged); set_transform partial-override " +
            "(translate/scale/rotation/opacity); set_sourceBinding full-replacement set of source-node ids."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")
    override val applicability: ToolApplicability = ToolApplicability.RequiresAssets

    override val inputSchema: JsonObject = CLIP_ACTION_INPUT_SCHEMA

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val pid = ctx.resolveProjectId(input.projectId)
        return when (input.action) {
            "add" -> executeClipAdd(store, pid, input, ctx)
            "remove" -> executeClipRemove(store, pid, input, ctx)
            "duplicate" -> executeClipDuplicate(store, pid, input, ctx)
            "move" -> executeClipMove(store, pid, input, ctx)
            "split" -> executeClipSplit(store, pid, input, ctx)
            "trim" -> executeClipTrim(store, pid, input, ctx)
            "replace" -> executeClipReplace(store, pid, input, ctx)
            "fade" -> executeClipFade(store, pid, input, ctx)
            "edit_text" -> executeClipEditText(store, pid, input, ctx)
            "set_volume" -> executeClipSetVolume(store, pid, input, ctx)
            "set_transform" -> executeClipSetTransform(store, pid, input, ctx)
            "set_sourceBinding" -> executeClipSetSourceBinding(store, pid, input, ctx)
            else -> error(
                "unknown action '${input.action}'; " +
                    "accepted: add, remove, duplicate, move, split, trim, replace, fade, edit_text, " +
                    "set_volume, set_transform, set_sourceBinding",
            )
        }
    }
}
