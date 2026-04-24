package io.talevia.core.tool.builtin.video

/**
 * Tiny helpers shared by the three `ClipSetActionTool` field handlers
 * ([executeClipSetVolume], [executeClipSetTransform], [executeClipSetSourceBinding]).
 *
 * Kept small on purpose — the per-clip mutation / track rebuild primitives
 * (`withClips`, `Track` copy helpers) already live in
 * [ClipActionHelpers.kt] and are `internal` at package level, so the
 * field-handler files reach them directly without a second indirection.
 *
 * **Max volume ceiling** lives here rather than on [ClipSetActionTool]'s
 * `companion object` because it's consumed by the volume handler, not by
 * the dispatcher class.
 */

internal const val MAX_VOLUME: Float = 4.0f

/**
 * Shared per-field input-shape guard. Rejects payload lists that don't
 * belong to the selected `field` — fails fast and loud so a typo in
 * `volumeItems` vs `transformItems` surfaces instead of being silently
 * ignored. Mirrors [rejectForeignClipActionFields]'s shape for
 * [ClipActionTool].
 */
internal fun rejectForeignClipSetFields(field: String, input: ClipSetActionTool.Input) {
    val foreign = buildList {
        if (field != "volume" && input.volumeItems != null) add("volumeItems")
        if (field != "transform" && input.transformItems != null) add("transformItems")
        if (field != "sourceBinding" && input.sourceBindingItems != null) add("sourceBindingItems")
    }
    require(foreign.isEmpty()) {
        "field=$field rejects ${foreign.joinToString(" / ")} — use this field's own payload list"
    }
}
