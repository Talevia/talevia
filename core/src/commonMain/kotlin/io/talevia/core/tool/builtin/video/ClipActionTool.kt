package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.ProjectStore
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
 * Eight-way clip edit verb — consolidates `AddClipTool` + `RemoveClipTool` +
 * `DuplicateClipTool` (phase-1, 2026-04-23), `MoveClipTool` + `SplitClipTool`
 * + `TrimClipTool` (phase-2, 2026-04-23), and `ReplaceClipTool` +
 * `FadeAudioClipTool` (phase-3, 2026-04-24 — fold enabled by the axis
 * split landed earlier the same day).
 *
 * **Structure (post axis-split + phase-3, 2026-04-24).** The class itself carries
 * only the LLM-facing surface: nested data classes for input/output
 * payloads, the JSON schema, the tool metadata, the `rejectForeign`
 * one-liner, and the six-way dispatch `when` in [execute]. The actual
 * per-verb business logic lives in two sibling files:
 *
 * - [executeClipAdd] / [executeClipDuplicate] — `ClipCreateHandlers.kt`
 * - [executeClipRemove] / [executeClipMove] / [executeClipSplit] /
 *   [executeClipTrim] — `ClipMutateHandlers.kt`
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
 */
class ClipActionTool(
    private val store: ProjectStore,
) : Tool<ClipActionTool.Input, ClipActionTool.Output> {

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

    @Serializable data class Input(
        /**
         * Optional — omit to default to the session's current project binding
         * (`ToolContext.currentProjectId`). Required when the session is
         * unbound; fail loud points the agent at `switch_project`.
         */
        val projectId: String? = null,
        /**
         * One of `"add"`, `"remove"`, `"duplicate"`, `"move"`, `"split"`,
         * `"trim"`, `"replace"`, `"fade"`. Case-sensitive.
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
        /** `action="remove"` only — echoes the input `ripple` flag. */
        val rippled: Boolean = false,
    )

    override val id: String = "clip_action"
    override val helpText: String =
        "Eight-way clip edit verb dispatching on `action`: " +
            "`add` + addItems (assetId, timelineStartSeconds?, sourceStartSeconds?, " +
            "durationSeconds?, trackId?). " +
            "`remove` + clipIds + optional `ripple` (default false). " +
            "`duplicate` + duplicateItems (clipId, timelineStartSeconds, trackId?). " +
            "`move` + moveItems (clipId, timelineStartSeconds?, toTrackId?). " +
            "`split` + splitItems (clipId, atTimelineSeconds). " +
            "`trim` + trimItems (clipId, newSourceStartSeconds?, newDurationSeconds?). " +
            "`replace` + replaceItems (clipId, newAssetId). " +
            "`fade` + fadeItems (clipId, fadeInSeconds?, fadeOutSeconds?). " +
            "All-or-nothing per call; one snapshot per call. text clips rejected by " +
            "trim/replace; cross-kind trackId rejected."
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
            else -> error(
                "unknown action '${input.action}'; accepted: add, remove, duplicate, move, split, trim, replace, fade",
            )
        }
    }
}
