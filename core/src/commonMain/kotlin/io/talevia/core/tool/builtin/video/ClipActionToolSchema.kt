package io.talevia.core.tool.builtin.video

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema for [ClipActionTool.Input] — pulled out of the dispatcher
 * file so the main file stays under the R.5.4 500-LOC threshold.
 * Byte-identical to the previous inline definition; every field
 * description is preserved verbatim so the LLM-visible schema does not
 * change. Mirrors `SessionQueryToolSchema` / `ProjectQueryToolSchema`
 * / `SourceQueryToolSchema` / `ImportMediaToolSchema`.
 */
internal val CLIP_ACTION_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("projectId") {
            put("type", "string")
            put("description", "Optional — omit to use the session's current project (set via switch_project).")
        }
        putJsonObject("action") {
            put("type", "string")
            put("description", "One of: add, remove, duplicate, move, split, trim, replace, fade.")
            put(
                "enum",
                JsonArray(
                    listOf("add", "remove", "duplicate", "move", "split", "trim", "replace", "fade")
                        .map(::JsonPrimitive),
                ),
            )
        }
        putJsonObject("addItems") {
            itemArray("Required when action=add. Clips to append; at least one.", required = listOf("assetId")) {
                stringProp("assetId")
                numberProp("timelineStartSeconds", "If omitted, append after the last clip on the target track.")
                numberProp("sourceStartSeconds", "Trim offset into the source media.")
                numberProp("durationSeconds", "If omitted, use the asset's full remaining duration.")
                stringProp("trackId", "Optional track; defaults to the first Video track (created if absent).")
            }
        }
        putJsonObject("clipIds") {
            put("type", "array")
            put("description", "Required when action=remove. Clip ids to delete; at least one.")
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("ripple") {
            put("type", "boolean")
            put("description", "action=remove only. Close the gap on each removed clip's track. Default false.")
        }
        putJsonObject("duplicateItems") {
            itemArray(
                "Required when action=duplicate. Clones to produce; at least one.",
                required = listOf("clipId", "timelineStartSeconds"),
            ) {
                stringProp("clipId")
                numberProp("timelineStartSeconds", "New timeline start position in seconds (must be >= 0).")
                stringProp("trackId", "Optional target track id of the same kind. Defaults to the source clip's track.")
            }
        }
        putJsonObject("moveItems") {
            itemArray(
                "Required when action=move. Reposition operations; at least one.",
                required = listOf("clipId"),
            ) {
                stringProp("clipId")
                numberProp(
                    "timelineStartSeconds",
                    "New timeline start position in seconds (>= 0). Omit to keep current (valid only when toTrackId is set).",
                )
                stringProp(
                    "toTrackId",
                    "Optional target track id. Omit for same-track reposition. Must be same kind as the clip.",
                )
            }
        }
        putJsonObject("splitItems") {
            itemArray(
                "Required when action=split. Split operations; at least one.",
                required = listOf("clipId", "atTimelineSeconds"),
            ) {
                stringProp("clipId")
                numberProp(
                    "atTimelineSeconds",
                    "Absolute timeline position to split at (strictly between clip's start and end).",
                )
            }
        }
        putJsonObject("trimItems") {
            itemArray(
                "Required when action=trim. Trim operations; at least one.",
                required = listOf("clipId"),
            ) {
                stringProp("clipId")
                numberProp(
                    "newSourceStartSeconds",
                    "New trim offset into the source media (>= 0). Omit to keep current.",
                )
                numberProp(
                    "newDurationSeconds",
                    "New duration in seconds (> 0). Applied to both timeRange and sourceRange. Omit to keep current.",
                )
            }
        }
        putJsonObject("replaceItems") {
            itemArray(
                "Required when action=replace. Clip → new-asset swaps; at least one.",
                required = listOf("clipId", "newAssetId"),
            ) {
                stringProp("clipId")
                stringProp(
                    "newAssetId",
                    "Replacement asset; must already exist in the project's asset catalog.",
                )
            }
        }
        putJsonObject("fadeItems") {
            itemArray(
                "Required when action=fade. Audio-clip fade envelope edits; at least one.",
                required = listOf("clipId"),
            ) {
                stringProp("clipId")
                numberProp("fadeInSeconds", "Fade-in ramp seconds. 0.0 disables. Omit to keep current.")
                numberProp("fadeOutSeconds", "Fade-out ramp seconds. 0.0 disables. Omit to keep current.")
            }
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("action"))))
    put("additionalProperties", false)
}

/** DSL helpers for the JSON Schema builder — keep the schema block compact. */
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
