package io.talevia.core.tool.builtin.video

import io.talevia.core.ProjectId
import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.Track
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.tool.Tool
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
 * Surgical edits on an existing [Clip.Text] — text body or style
 * fields. The missing counterpart to `add_subtitle` / `add_subtitles`:
 * once a subtitle is on the timeline, fixing a typo or bumping the
 * font size should not require removing and re-adding the clip (which
 * drops the id, breaks any downstream tool state that captured the
 * id, and resets transforms).
 *
 * Semantics match the `set_character_ref` / `set_style_bible` patch path —
 * optional per-field overrides, at least one required:
 *   - `null` → keep current value.
 *   - A provided value → replace.
 *   - `""` on `backgroundColor` → clear (set to null).
 *
 * Works on text clips on any track kind (Subtitle or Effect). Emits
 * a timeline snapshot so the edit is revertable. `timeRange` is not
 * touched — use `move_clip` / `trim_clip` for positional edits.
 */
class EditTextClipTool(
    private val store: ProjectStore,
) : Tool<EditTextClipTool.Input, EditTextClipTool.Output> {

    @Serializable data class Input(
        val projectId: String,
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

    @Serializable data class Output(
        val clipId: String,
        val updatedFields: List<String>,
    )

    override val id: String = "edit_text_clip"
    override val helpText: String =
        "Edit an existing text clip's body and/or style fields in place. " +
            "All fields optional; at least one required. Preserves clip id, " +
            "track, transforms, and timeRange."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()
    override val permission: PermissionSpec = PermissionSpec.fixed("timeline.write")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("projectId") { put("type", "string") }
            putJsonObject("clipId") { put("type", "string") }
            putJsonObject("newText") {
                put("type", "string")
                put("description", "Replace the text body. Must be non-blank.")
            }
            putJsonObject("fontFamily") { put("type", "string") }
            putJsonObject("fontSize") { put("type", "number"); put("description", "Point size; must be > 0.") }
            putJsonObject("color") { put("type", "string"); put("description", "CSS-style hex (e.g. #FFFFFF).") }
            putJsonObject("backgroundColor") {
                put("type", "string")
                put("description", "Background hex; '' clears (transparent).")
            }
            putJsonObject("bold") { put("type", "boolean") }
            putJsonObject("italic") { put("type", "boolean") }
        }
        put(
            "required",
            JsonArray(listOf(JsonPrimitive("projectId"), JsonPrimitive("clipId"))),
        )
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        val changed = buildList {
            if (input.newText != null) add("text")
            if (input.fontFamily != null) add("fontFamily")
            if (input.fontSize != null) add("fontSize")
            if (input.color != null) add("color")
            if (input.backgroundColor != null) add("backgroundColor")
            if (input.bold != null) add("bold")
            if (input.italic != null) add("italic")
        }
        require(changed.isNotEmpty()) { "edit_text_clip requires at least one field to change" }
        input.newText?.let { require(it.isNotBlank()) { "newText must be non-blank" } }
        input.fontFamily?.let { require(it.isNotBlank()) { "fontFamily must be non-blank" } }
        input.fontSize?.let { require(it > 0f) { "fontSize must be > 0 (got $it)" } }
        input.color?.let { require(it.isNotBlank()) { "color must be non-blank" } }

        var found = false
        val updated = store.mutate(ProjectId(input.projectId)) { project ->
            val newTracks = project.timeline.tracks.map { track ->
                val target = track.clips.firstOrNull { it.id.value == input.clipId } ?: return@map track
                found = true
                if (target !is Clip.Text) error("edit_text_clip only supports text clips")
                val newStyle = TextStyle(
                    fontFamily = input.fontFamily ?: target.style.fontFamily,
                    fontSize = input.fontSize ?: target.style.fontSize,
                    color = input.color ?: target.style.color,
                    backgroundColor = when (input.backgroundColor) {
                        null -> target.style.backgroundColor
                        "" -> null
                        else -> input.backgroundColor
                    },
                    bold = input.bold ?: target.style.bold,
                    italic = input.italic ?: target.style.italic,
                )
                val newClip = target.copy(
                    text = input.newText ?: target.text,
                    style = newStyle,
                )
                replaceClip(track, target, newClip)
            }
            project.copy(timeline = project.timeline.copy(tracks = newTracks))
        }
        if (!found) error("clip ${input.clipId} not found in project ${input.projectId}")

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "edit text clip ${input.clipId}",
            outputForLlm = "Updated text clip ${input.clipId} (${changed.joinToString(", ")}). " +
                "Timeline snapshot: ${snapshotId.value}",
            data = Output(input.clipId, changed),
        )
    }

    private fun replaceClip(track: Track, removed: Clip, replacement: Clip): Track {
        val clips = track.clips.map { if (it.id == removed.id) replacement else it }
        return when (track) {
            is Track.Video -> track.copy(clips = clips)
            is Track.Audio -> track.copy(clips = clips)
            is Track.Subtitle -> track.copy(clips = clips)
            is Track.Effect -> track.copy(clips = clips)
        }
    }
}
