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
            put("description", "delete / rename / set_output_profile / remove_asset: required. create: optional hint.")
        }
        putJsonObject("title") {
            put("type", "string")
            put("description", "create / create_from_template: initial title. rename: new title. Non-blank.")
        }
        putJsonObject("path") {
            put("type", "string")
            put(
                "description",
                "create / create_from_template / open: filesystem path. " +
                    "create*: bundle location (no talevia.json); omit for default home. " +
                    "open: absolute path to existing bundle.",
            )
        }
        putJsonObject("resolutionPreset") {
            put("type", "string")
            put("description", "create / create_from_template: 720p | 1080p (default) | 4k.")
        }
        putJsonObject("fps") {
            put("type", "integer")
            put("description", "create*: 24 | 30 (default) | 60. set_output_profile: any positive int.")
        }
        putJsonObject("deleteFiles") {
            put("type", "boolean")
            put(
                "description",
                "delete: also drop on-disk bundle (talevia.json, media/, .talevia-cache/). " +
                    "Default false (just unregister from recents).",
            )
        }
        putJsonObject("assetId") {
            put("type", "string")
            put("description", "remove_asset: asset id.")
        }
        putJsonObject("force") {
            put("type", "boolean")
            put("description", "remove_asset: remove even if referenced (default false; lists deps in error).")
        }
        putJsonObject("resolutionWidth") {
            put("type", "integer")
            put("description", "set_output_profile: px (with resolutionHeight).")
        }
        putJsonObject("resolutionHeight") {
            put("type", "integer")
            put("description", "set_output_profile: px (with resolutionWidth).")
        }
        putJsonObject("videoCodec") {
            put("type", "string")
            put("description", "set_output_profile: h264 / h265 / prores / vp9.")
        }
        putJsonObject("audioCodec") {
            put("type", "string")
            put("description", "set_output_profile: aac / opus / mp3.")
        }
        putJsonObject("videoBitrate") {
            put("type", "integer")
            put("description", "set_output_profile: bits/s (8000000 = 8 Mbps).")
        }
        putJsonObject("audioBitrate") {
            put("type", "integer")
            put("description", "set_output_profile: bits/s (192000 = 192 kbps).")
        }
        putJsonObject("container") {
            put("type", "string")
            put("description", "set_output_profile: mp4 / mov / mkv / webm.")
        }
        putJsonObject("template") {
            put("type", "string")
            put(
                "description",
                "create_from_template: narrative / vlog / ad / musicmv / tutorial / auto. " +
                    "auto needs `intent` and classifies on-device.",
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
            put("description", "create_from_template + template=auto: one-sentence intent for keyword classifier.")
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("action"))))
    put("additionalProperties", false)
}
