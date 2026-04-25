package io.talevia.core.tool.builtin.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema for [UpdateSourceNodeBodyTool.Input] — pulled out of the
 * dispatcher file so the main class stays focused on dispatch.
 * Byte-identical to the previous inline definition; every field
 * description is preserved verbatim so the LLM-visible schema does not
 * change. Mirrors `ImportSourceNodeToolSchema`,
 * `ProjectQueryToolSchema`, etc.
 */
internal val UPDATE_SOURCE_NODE_BODY_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("projectId") { put("type", "string") }
        putJsonObject("nodeId") {
            put("type", "string")
            put("description", "Id of the node whose body is being replaced.")
        }
        putJsonObject("body") {
            put("type", "object")
            put(
                "description",
                "Complete new body — full replacement, not a partial patch. Required " +
                    "unless restoreFromRevisionIndex is set (mutually exclusive).",
            )
            put("minProperties", 1)
            put("additionalProperties", true)
            put(
                "examples",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("framing", JsonPrimitive("close-up"))
                            put("dialogue", JsonPrimitive("Where are we?"))
                            put("duration_seconds", JsonPrimitive(2.5))
                        },
                    )
                },
            )
        }
        putJsonObject("restoreFromRevisionIndex") {
            put("type", "integer")
            put("minimum", 0)
            put(
                "description",
                "Restore whole body from history (0=newest). Mutually exclusive with " +
                    "body and mergeFromRevisionIndex.",
            )
        }
        putJsonObject("mergeFromRevisionIndex") {
            put("type", "integer")
            put("minimum", 0)
            put(
                "description",
                "Per-field merge from history (0=newest). Pair with mergeFieldPaths to " +
                    "name the top-level keys to copy from the historical body over the " +
                    "current body. Mutually exclusive with body and restoreFromRevisionIndex.",
            )
        }
        putJsonObject("mergeFieldPaths") {
            put("type", "array")
            put(
                "description",
                "Top-level body keys to take from mergeFromRevisionIndex's historical " +
                    "body. Required when mergeFromRevisionIndex is set; rejected otherwise.",
            )
            putJsonObject("items") { put("type", "string") }
        }
    }
    put(
        "required",
        JsonArray(
            listOf(JsonPrimitive("projectId"), JsonPrimitive("nodeId")),
        ),
    )
    put("additionalProperties", false)
}
