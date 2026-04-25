package io.talevia.core.tool.builtin.project

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema for [ForkProjectTool.Input] — pulled out of the dispatcher
 * file so the main class stays focused on orchestration. Byte-identical
 * to the previous inline definition; every field description is preserved
 * verbatim so the LLM-visible schema does not change. Mirrors
 * [ImportMediaToolSchema], [ProjectQueryToolSchema], etc.
 */
internal val FORK_PROJECT_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("sourceProjectId") { put("type", "string") }
        putJsonObject("newTitle") {
            put("type", "string")
            put("description", "Title for the forked project (also drives the default newProjectId).")
        }
        putJsonObject("newProjectId") {
            put("type", "string")
            put("description", "Optional explicit id for the fork; defaults to a slug of newTitle.")
        }
        putJsonObject("snapshotId") {
            put("type", "string")
            put(
                "description",
                "Optional snapshot to fork from; defaults to the source project's current state.",
            )
        }
        putJsonObject("path") {
            put("type", "string")
            put(
                "description",
                "Optional absolute filesystem path for the fork's bundle. Defaults to the " +
                    "store's default-projects-home. The directory must not already contain a " +
                    "talevia.json.",
            )
        }
        putJsonObject("variantSpec") {
            put("type", "object")
            put(
                "description",
                "Optional reshape spec. Set fields trigger post-fork transforms: aspectRatio " +
                    "remaps resolution (16:9 / 9:16 / 1:1 / 4:5 / 21:9); durationSecondsMax caps " +
                    "the timeline at that many seconds.",
            )
            putJsonObject("properties") {
                putJsonObject("aspectRatio") {
                    put("type", "string")
                    put(
                        "description",
                        "Target aspect preset: 16:9, 9:16, 1:1, 4:5, or 21:9 (case-insensitive).",
                    )
                }
                putJsonObject("durationSecondsMax") {
                    put("type", "number")
                    put("description", "Cap the timeline at this many seconds (must be > 0).")
                }
                putJsonObject("language") {
                    put("type", "string")
                    put(
                        "description",
                        "ISO-639-1 language code (e.g. en / es / zh). Regenerates TTS for " +
                            "every non-blank text clip in the fork against this language; " +
                            "results land in Output.languageRegeneratedClips as (clipId, " +
                            "assetId, cacheHit). The fork's timeline isn't rewired — chain " +
                            "clip_action(action=\"replace\") per entry to swap audio.",
                    )
                }
            }
            put("additionalProperties", false)
        }
    }
    put(
        "required",
        JsonArray(listOf(JsonPrimitive("sourceProjectId"), JsonPrimitive("newTitle"))),
    )
    put("additionalProperties", false)
}
