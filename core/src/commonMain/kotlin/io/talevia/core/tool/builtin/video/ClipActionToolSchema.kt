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
            itemArray("action=add. Clips to append.", required = listOf("assetId")) {
                stringProp("assetId")
                numberProp("timelineStartSeconds", "Default: append after last clip on track.")
                numberProp("sourceStartSeconds", "Source-trim offset (s).")
                numberProp("durationSeconds", "Default: asset's remaining duration.")
                stringProp("trackId", "Default: first Video track (created if absent).")
            }
        }
        putJsonObject("clipIds") {
            put("type", "array")
            put("description", "action=remove. Clip ids to delete.")
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("ripple") {
            put("type", "boolean")
            put("description", "action=remove. Close the gap on each removed clip's track. Default false.")
        }
        putJsonObject("duplicateItems") {
            itemArray(
                "action=duplicate. Clones to produce.",
                required = listOf("clipId", "timelineStartSeconds"),
            ) {
                stringProp("clipId")
                numberProp("timelineStartSeconds", "Timeline start (s, >= 0).")
                stringProp("trackId", "Optional same-kind target. Default: source's track.")
            }
        }
        putJsonObject("moveItems") {
            itemArray(
                "action=move. Reposition operations.",
                required = listOf("clipId"),
            ) {
                stringProp("clipId")
                numberProp(
                    "timelineStartSeconds",
                    "Timeline start (s, >= 0). Omit only when toTrackId is set.",
                )
                stringProp("toTrackId", "Optional same-kind target. Omit for same-track reposition.")
            }
        }
        putJsonObject("splitItems") {
            itemArray(
                "action=split. Split operations.",
                required = listOf("clipId", "atTimelineSeconds"),
            ) {
                stringProp("clipId")
                numberProp("atTimelineSeconds", "Timeline split point (strictly inside the clip).")
            }
        }
        putJsonObject("trimItems") {
            itemArray(
                "action=trim. Trim operations.",
                required = listOf("clipId"),
            ) {
                stringProp("clipId")
                numberProp("newSourceStartSeconds", "Source-trim offset (s, >= 0). Omit to keep.")
                numberProp("newDurationSeconds", "New duration (s, > 0); rewrites timeRange + sourceRange. Omit to keep.")
            }
        }
        putJsonObject("replaceItems") {
            itemArray(
                "action=replace. Clip → new-asset swaps.",
                required = listOf("clipId", "newAssetId"),
            ) {
                stringProp("clipId")
                stringProp("newAssetId", "Must already be in the project's asset catalog.")
            }
        }
        putJsonObject("fadeItems") {
            itemArray(
                "action=fade. Audio-clip fade envelope edits.",
                required = listOf("clipId"),
            ) {
                stringProp("clipId")
                numberProp("fadeInSeconds", "Fade-in ramp (s). 0.0 disables. Omit to keep.")
                numberProp("fadeOutSeconds", "Fade-out ramp (s). 0.0 disables. Omit to keep.")
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
