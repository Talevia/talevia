package io.talevia.core.tool.builtin.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema for [ImportSourceNodeTool.Input] — pulled out of the
 * dispatcher file so the main class stays focused on dispatch.
 * Byte-identical to the previous inline definition; every field
 * description is preserved verbatim so the LLM-visible schema does not
 * change. Mirrors `ImportMediaToolSchema`, `ProjectQueryToolSchema`,
 * `SourceQueryToolSchema`, etc.
 */
internal val IMPORT_SOURCE_NODE_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("toProjectId") {
            put("type", "string")
            put(
                "description",
                "Optional — omit to use the session's current project (set via switch_project).",
            )
        }
        putJsonObject("fromProjectId") {
            put("type", "string")
            put(
                "description",
                "Live cross-project source project id (pair with fromNodeId). Mutually exclusive with envelope.",
            )
        }
        putJsonObject("fromNodeId") {
            put("type", "string")
            put("description", "Live cross-project source node id. Required when fromProjectId is set.")
        }
        putJsonObject("envelope") {
            put("type", "string")
            put(
                "description",
                "JSON envelope string produced by export_source_node (data.envelope output). " +
                    "Mutually exclusive with fromProjectId / fromNodeId.",
            )
        }
        putJsonObject("newNodeId") {
            put("type", "string")
            put(
                "description",
                "Optional rename for the imported leaf node only (parents keep their original ids). " +
                    "Use when the original id would collide with a different-content node in the target.",
            )
        }
    }
    put("required", JsonArray(emptyList()))
    put("additionalProperties", false)
}
