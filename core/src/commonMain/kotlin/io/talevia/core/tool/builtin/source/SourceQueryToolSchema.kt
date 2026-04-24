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
                "What to query: nodes | dag_summary | dot | ascii_tree | orphans | " +
                    "descendants | ancestors | history (case-insensitive).",
            )
        }
        putJsonObject("projectId") {
            put("type", "string")
            put(
                "description",
                "Target project. Required unless scope='all_projects'. Rejected when " +
                    "scope='all_projects' (the cross-project search enumerates every project).",
            )
        }
        putJsonObject("scope") {
            put("type", "string")
            put(
                "description",
                "project (default, single-project query) | all_projects (select=nodes only — " +
                    "enumerates every project in the store; each row carries its owning projectId).",
            )
        }
        putJsonObject("kind") {
            put("type", "string")
            put(
                "description",
                "Exact kind filter (e.g. core.consistency.character_ref). select=nodes only.",
            )
        }
        putJsonObject("kindPrefix") {
            put("type", "string")
            put(
                "description",
                "Kind prefix filter (e.g. core.consistency.). select=nodes only.",
            )
        }
        putJsonObject("contentSubstring") {
            put("type", "string")
            put(
                "description",
                "Substring match against each node's JSON-serialized body. " +
                    "Matching rows carry snippet + matchOffset. select=nodes only.",
            )
        }
        putJsonObject("caseSensitive") {
            put("type", "boolean")
            put(
                "description",
                "Case-sensitive match for contentSubstring. Default false. select=nodes only.",
            )
        }
        putJsonObject("id") {
            put("type", "string")
            put(
                "description",
                "Exact node id — returns ≤1 row. select=nodes only. Use describe_source_node " +
                    "for full body + parent/children relations.",
            )
        }
        putJsonObject("includeBody") {
            put("type", "boolean")
            put(
                "description",
                "Include each node's full JSON body in the result. Default false. select=nodes only.",
            )
        }
        putJsonObject("sortBy") {
            put("type", "string")
            put(
                "description",
                "Sort key — id (default), kind, revision-desc. select=nodes only.",
            )
        }
        putJsonObject("hasParent") {
            put("type", "boolean")
            put("description", "true=children, false=roots. select=nodes.")
        }
        putJsonObject("hotspotLimit") {
            put("type", "integer")
            put(
                "description",
                "Max hotspots in the dag_summary row. Default 5. select=dag_summary only.",
            )
        }
        putJsonObject("root") {
            put("type", "string")
            put(
                "description",
                "Source node id to traverse from. Required for select=descendants / " +
                    "select=ancestors / select=history; rejected elsewhere.",
            )
        }
        putJsonObject("depth") {
            put("type", "integer")
            put(
                "description",
                "Max hop count from root (0 = root only, positive = bounded, null or " +
                    "negative = unbounded). select=descendants / select=ancestors only.",
            )
        }
        putJsonObject("limit") {
            put("type", "integer")
            put(
                "description",
                "Cap on returned rows (default 100, clamped 1..500). select=nodes only.",
            )
        }
        putJsonObject("offset") {
            put("type", "integer")
            put("description", "Skip N rows after filter+sort. select=nodes only. Default 0.")
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("select"))))
    put("additionalProperties", false)
}
