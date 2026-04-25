package io.talevia.core.tool.builtin.project

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON schema surface for [ProjectActionTool]. Extracted to its own file
 * to mirror `ClipActionToolSchema.kt` / `ProjectQueryToolSchema.kt` —
 * keeps the dispatcher class focused on shape + business logic and lets
 * the schema grow (per-action enums, future verbs) without re-puffing
 * the tool body past the long-file threshold.
 */
internal val PROJECT_ACTION_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("action") {
            put("type", "string")
            put(
                "description",
                "`create`, `create_from_template`, `open`, `delete`, `rename`, " +
                    "`set_output_profile`, or `remove_asset`.",
            )
            put(
                "enum",
                JsonArray(
                    listOf(
                        JsonPrimitive("create"),
                        JsonPrimitive("create_from_template"),
                        JsonPrimitive("open"),
                        JsonPrimitive("delete"),
                        JsonPrimitive("rename"),
                        JsonPrimitive("set_output_profile"),
                        JsonPrimitive("remove_asset"),
                    ),
                ),
            )
        }
        putJsonObject("projectId") {
            put("type", "string")
            put(
                "description",
                "Required for delete / rename / set_output_profile / remove_asset. " +
                    "Optional naming hint for create. Ignored by open.",
            )
        }
        putJsonObject("title") {
            put("type", "string")
            put(
                "description",
                "create / create_from_template: human-readable initial title. rename: new title. " +
                    "Must not be blank.",
            )
        }
        putJsonObject("path") {
            put("type", "string")
            put(
                "description",
                "create / create_from_template / open: filesystem path. create / " +
                    "create_from_template: optional bundle location (must not already contain " +
                    "talevia.json); omit for the store's default home. open: absolute path to an " +
                    "existing bundle directory containing talevia.json.",
            )
        }
        putJsonObject("resolutionPreset") {
            put("type", "string")
            put(
                "description",
                "create / create_from_template only. 720p, 1080p (default), or 4k.",
            )
        }
        putJsonObject("fps") {
            put("type", "integer")
            put(
                "description",
                "create / create_from_template: 24, 30 (default), or 60. " +
                    "set_output_profile: any positive integer fps.",
            )
        }
        putJsonObject("deleteFiles") {
            put("type", "boolean")
            put(
                "description",
                "delete only. When true, also delete the on-disk bundle (talevia.json, media/, " +
                    ".talevia-cache/). Default false: only unregister from the recents list.",
            )
        }
        putJsonObject("assetId") {
            put("type", "string")
            put("description", "remove_asset only. Asset id to drop from project.assets.")
        }
        putJsonObject("force") {
            put("type", "boolean")
            put(
                "description",
                "remove_asset only. Remove even if clips still reference the asset. " +
                    "Default false (refuses with the dependent clip ids in the error).",
            )
        }
        putJsonObject("resolutionWidth") {
            put("type", "integer")
            put("description", "set_output_profile only. Pixels. Pair with resolutionHeight.")
        }
        putJsonObject("resolutionHeight") {
            put("type", "integer")
            put("description", "set_output_profile only. Pixels. Pair with resolutionWidth.")
        }
        putJsonObject("videoCodec") {
            put("type", "string")
            put("description", "set_output_profile only. e.g. h264, h265, prores, vp9.")
        }
        putJsonObject("audioCodec") {
            put("type", "string")
            put("description", "set_output_profile only. e.g. aac, opus, mp3.")
        }
        putJsonObject("videoBitrate") {
            put("type", "integer")
            put(
                "description",
                "set_output_profile only. Bits per second (e.g. 8000000 for 8 Mbps).",
            )
        }
        putJsonObject("audioBitrate") {
            put("type", "integer")
            put(
                "description",
                "set_output_profile only. Bits per second (e.g. 192000 for 192 kbps).",
            )
        }
        putJsonObject("container") {
            put("type", "string")
            put("description", "set_output_profile only. e.g. mp4, mov, mkv, webm.")
        }
        putJsonObject("template") {
            put("type", "string")
            put(
                "description",
                "create_from_template only. Genre template id: narrative, vlog, ad, musicmv, " +
                    "tutorial, or auto. auto requires `intent` and classifies the genre from " +
                    "keywords (no LLM round-trip).",
            )
            put(
                "enum",
                JsonArray(
                    listOf(
                        JsonPrimitive("narrative"),
                        JsonPrimitive("vlog"),
                        JsonPrimitive("ad"),
                        JsonPrimitive("musicmv"),
                        JsonPrimitive("tutorial"),
                        JsonPrimitive("auto"),
                    ),
                ),
            )
        }
        putJsonObject("intent") {
            put("type", "string")
            put(
                "description",
                "create_from_template only. One-sentence user intent. Required when " +
                    "template='auto'; ignored otherwise. Used to classify the genre via " +
                    "on-device keyword match (no LLM round-trip).",
            )
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("action"))))
    put("additionalProperties", false)
}
