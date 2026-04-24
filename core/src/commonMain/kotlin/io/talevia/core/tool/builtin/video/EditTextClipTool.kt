package io.talevia.core.tool.builtin.video

import io.talevia.core.domain.Clip
import io.talevia.core.domain.ProjectStore
import io.talevia.core.domain.TextStyle
import io.talevia.core.domain.Track
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
 * Surgical edits on one or many [Clip.Text] clips — text body and/or style
 * fields, in a single atomic call. Per-item shape: each entry carries its
 * own clipId + any subset of fields; at least one field per item must be
 * set.
 *
 * Partial-patch semantics (per item):
 *   - `null` → keep current value.
 *   - A provided value → replace.
 *   - `""` on `backgroundColor` → clear (set to null).
 *
 * Works on text clips on any track kind (Subtitle or Effect). `timeRange`
 * is not touched — use `clip_action(action=move|trim)` for positional edits.
 * All-or-nothing; one snapshot per call.
 */
class EditTextClipTool(
    private val store: ProjectStore,
) : Tool<EditTextClipTool.Input, EditTextClipTool.Output> {

    @Serializable data class Item(
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
        val updatedFields: List<String>,
    )

    @Serializable data class Output(
        val projectId: String,
        val results: List<ItemResult>,
        val snapshotId: String,
    )

    override val id: String = "edit_text_clips"
    override val helpText: String =
        "Edit one or many text clips' body and/or style fields in place, atomically. Each item " +
            "carries its own clipId + any subset of { newText, fontFamily, fontSize, color, " +
            "backgroundColor, bold, italic }; at least one field per item is required. Preserves " +
            "clip ids, tracks, transforms, and timeRanges. All-or-nothing; one snapshot per call."
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
            putJsonObject("items") {
                put("type", "array")
                put("description", "Text edits to apply. At least one required.")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("clipId") { put("type", "string") }
                        putJsonObject("newText") {
                            put("type", "string")
                            put("description", "Replace the text body. Must be non-blank.")
                        }
                        putJsonObject("fontFamily") { put("type", "string") }
                        putJsonObject("fontSize") {
                            put("type", "number")
                            put("description", "Point size; must be > 0.")
                        }
                        putJsonObject("color") {
                            put("type", "string")
                            put("description", "CSS-style hex (e.g. #FFFFFF).")
                        }
                        putJsonObject("backgroundColor") {
                            put("type", "string")
                            put("description", "Background hex; '' clears (transparent).")
                        }
                        putJsonObject("bold") { put("type", "boolean") }
                        putJsonObject("italic") { put("type", "boolean") }
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
            val changed = buildList {
                if (item.newText != null) add("text")
                if (item.fontFamily != null) add("fontFamily")
                if (item.fontSize != null) add("fontSize")
                if (item.color != null) add("color")
                if (item.backgroundColor != null) add("backgroundColor")
                if (item.bold != null) add("bold")
                if (item.italic != null) add("italic")
            }
            require(changed.isNotEmpty()) {
                "items[$idx] (${item.clipId}): at least one field to change is required"
            }
            item.newText?.let { require(it.isNotBlank()) { "items[$idx] (${item.clipId}): newText must be non-blank" } }
            item.fontFamily?.let { require(it.isNotBlank()) { "items[$idx] (${item.clipId}): fontFamily must be non-blank" } }
            item.fontSize?.let { require(it > 0f) { "items[$idx] (${item.clipId}): fontSize must be > 0 (got $it)" } }
            item.color?.let { require(it.isNotBlank()) { "items[$idx] (${item.clipId}): color must be non-blank" } }
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
                val target = clip as? Clip.Text
                    ?: error("items[$idx]: edit_text_clips only supports text clips (clip ${item.clipId})")

                val changed = buildList {
                    if (item.newText != null) add("text")
                    if (item.fontFamily != null) add("fontFamily")
                    if (item.fontSize != null) add("fontSize")
                    if (item.color != null) add("color")
                    if (item.backgroundColor != null) add("backgroundColor")
                    if (item.bold != null) add("bold")
                    if (item.italic != null) add("italic")
                }
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
                results += ItemResult(clipId = item.clipId, updatedFields = changed)
            }
            project.copy(timeline = project.timeline.copy(tracks = tracks))
        }

        val snapshotId = emitTimelineSnapshot(ctx, updated.timeline)
        return ToolResult(
            title = "edit text × ${results.size}",
            outputForLlm = "Updated ${results.size} text clip(s). Snapshot: ${snapshotId.value}",
            data = Output(pid.value, results, snapshotId.value),
        )
    }
}
