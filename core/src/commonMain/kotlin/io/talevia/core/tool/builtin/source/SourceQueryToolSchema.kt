package io.talevia.core.tool.builtin.source

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema for [SourceQueryTool.Input] — pulled out of the dispatcher
 * file so the main file stays under the 500-line R.5.4 debt threshold.
 * Byte-identical to the previous inline definition; every field
 * description is preserved verbatim so the LLM-visible schema does not
 * change. Mirrors `SessionQueryToolSchema` / `ProjectQueryToolSchema` /
 * `ImportMediaToolSchema`.
 */
internal val SOURCE_QUERY_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("select") {
            put("type", "string")
            put(
                "description",
                "What to query: nodes | dag_summary | dot | ascii_tree | orphans | leaves | " +
                    "descendants | ancestors | history | node_detail (case-insensitive).",
            )
        }
        putJsonObject("projectId") {
            put("type", "string")
            put("description", "Target project. Required unless scope='all_projects' (which rejects it).")
        }
        putJsonObject("scope") {
            put("type", "string")
            put(
                "description",
                "project (default) | all_projects (nodes only — cross-project enumeration; " +
                    "each row carries projectId).",
            )
        }
        putJsonObject("kind") {
            put("type", "string")
            put("description", "Exact kind filter. nodes only.")
        }
        putJsonObject("kindPrefix") {
            put("type", "string")
            put("description", "Kind prefix filter. nodes only.")
        }
        putJsonObject("contentSubstring") {
            put("type", "string")
            put(
                "description",
                "Substring match on JSON body; rows carry snippet + matchOffset. nodes only.",
            )
        }
        putJsonObject("caseSensitive") {
            put("type", "boolean")
            put("description", "Case-sensitive match. Default false. nodes only.")
        }
        putJsonObject("id") {
            put("type", "string")
            put(
                "description",
                "Exact node id. nodes (optional filter, ≤1 row) | node_detail (required — " +
                    "the node to drill into).",
            )
        }
        putJsonObject("includeBody") {
            put("type", "boolean")
            put("description", "Include each node's JSON body. Default false. nodes only.")
        }
        putJsonObject("sortBy") {
            put("type", "string")
            put("description", "id (default) | kind | revision-desc. nodes only.")
        }
        putJsonObject("hasParent") {
            put("type", "boolean")
            put("description", "true=children, false=roots. nodes only.")
        }
        putJsonObject("hotspotLimit") {
            put("type", "integer")
            put("description", "Max hotspots in row (default 5). dag_summary only.")
        }
        putJsonObject("root") {
            put("type", "string")
            put("description", "Source node id to traverse from. Required for descendants/ancestors/history.")
        }
        putJsonObject("depth") {
            put("type", "integer")
            put(
                "description",
                "Max hops from root (0=root only, positive=bounded, null/negative=unbounded). " +
                    "descendants/ancestors only.",
            )
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("description", "Default 100, [1..500]. nodes only.")
        }
        putJsonObject("offset") {
            put("type", "integer")
            put("description", "Default 0. nodes only.")
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("select"))))
    put("additionalProperties", false)
}
