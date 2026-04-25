package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.Track
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult

/**
 * `clip_action(action="edit_text", editTextItems=…)` dispatch handler.
 *
 * Cycle 152 absorbed the standalone `EditTextClipTool` (211 LOC,
 * `edit_text_clips`) into the dispatcher. Same partial-patch
 * semantics: `null` = keep field, value = replace, `""` on
 * `backgroundColor` = clear (set to null). Works on text clips on
 * any track kind (Subtitle or Effect). `timeRange` is not touched —
 * use `clip_action(action=move|trim)` for positional edits.
 *
 * Per-item validation (each item in [ClipActionTool.Input.editTextItems]):
 *  - At least one field must be provided (no-op item rejected loud).
 *  - `newText` non-blank when present.
 *  - `fontFamily` non-blank when present.
 *  - `fontSize > 0` when present.
 *  - `color` non-blank when present.
 *  - `backgroundColor == ""` is a legitimate "clear" signal — NOT
 *    treated as no-op.
 *
 * All-or-nothing per call (validation fires before the
 * [ProjectStore.mutate] block); one [io.talevia.core.session.Part.TimelineSnapshot]
 * per call.
 */
internal suspend fun executeClipEditText(
    store: ProjectStore,
    pid: ProjectId,
    input: ClipActionTool.Input,
    ctx: ToolContext,
): ToolResult<ClipActionTool.Output> {
    val items = input.editTextItems
        ?: error("editTextItems is required when action=edit_text")
    require(items.isNotEmpty()) { "editTextItems must not be empty" }

    items.forEachIndexed { idx, item ->
        val changed = changedFieldsOf(item)
        require(changed.isNotEmpty()) {
            "editTextItems[$idx] (${item.clipId}): at least one field to change is required"
        }
        item.newText?.let { require(it.isNotBlank()) { "editTextItems[$idx] (${item.clipId}): newText must be non-blank" } }
        item.fontFamily?.let { require(it.isNotBlank()) { "editTextItems[$idx] (${item.clipId}): fontFamily must be non-blank" } }
        item.fontSize?.let { require(it > 0f) { "editTextItems[$idx] (${item.clipId}): fontSize must be > 0 (got $it)" } }
        item.color?.let { require(it.isNotBlank()) { "editTextItems[$idx] (${item.clipId}): color must be non-blank" } }
    }

    val results = mutableListOf<ClipActionTool.EditTextResult>()

    val updated = store.mutate(pid) { project ->
        var tracks = project.timeline.tracks
        items.forEachIndexed { idx, item ->
            val hit = tracks.firstNotNullOfOrNull { track ->
                track.clips.firstOrNull { it.id.value == item.clipId }?.let { track to it }
            } ?: error("editTextItems[$idx]: clip ${item.clipId} not found in project ${pid.value}")
            val (track, clip) = hit
            val target = clip as? Clip.Text
                ?: error("editTextItems[$idx]: edit_text only supports text clips (clip ${item.clipId})")

            val changed = changedFieldsOf(item)
            val newStyle = TextStyle(
                fontFamily = item.fontFamily ?: target.style.fontFamily,
                fontSize = item.fontSize ?: target.style.fontSize,
                color = item.color ?: target.style.color,
                backgroundColor = when (item.backgroundColor) {
                    null -> target.style.backgroundColor
                    "" -> null
                    else -> item.backgroundColor
                },
                bold = item.bold ?: target.style.bold,
                italic = item.italic ?: target.style.italic,
            )
            val newClip = target.copy(
                text = item.newText ?: target.text,
                style = newStyle,
            )
            val rebuilt = track.clips.map { if (it.id == target.id) newClip else it }
            val newTrack = when (track) {
                is Track.Video -> track.copy(clips = rebuilt)
                is Track.Audio -> track.copy(clips = rebuilt)
                is Track.Subtitle -> track.copy(clips = rebuilt)
                is Track.Effect -> track.copy(clips = rebuilt)
            }
            tracks = tracks.map { if (it.id == newTrack.id) newTrack else it }
            results += ClipActionTool.EditTextResult(clipId = item.clipId, updatedFields = changed)
        }
        project.copy(timeline = project.timeline.copy(tracks = tracks))
    }

    val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
    return ToolResult(
        title = "edit text × ${results.size}",
        outputForLlm = "Updated ${results.size} text clip(s). Snapshot: ${snapshotId.value}",
        data = ClipActionTool.Output(
            projectId = pid.value,
            action = "edit_text",
            snapshotId = snapshotId.value,
            editedText = results,
        ),
    )
}

/** Field names the item declares it intends to change (per partial-patch semantics). */
private fun changedFieldsOf(item: ClipActionTool.EditTextItem): List<String> = buildList {
    if (item.newText != null) add("text")
    if (item.fontFamily != null) add("fontFamily")
    if (item.fontSize != null) add("fontSize")
    if (item.color != null) add("color")
    if (item.backgroundColor != null) add("backgroundColor")
    if (item.bold != null) add("bold")
    if (item.italic != null) add("italic")
}
