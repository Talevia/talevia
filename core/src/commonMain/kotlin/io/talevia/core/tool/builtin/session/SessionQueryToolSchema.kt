package io.talevia.core.tool.builtin.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema for [SessionQueryTool.Input] — pulled out of the dispatcher
 * file so the main file stays under the 500-line R.5 debt threshold.
 * Byte-identical to the previous inline definition; every field
 * description is preserved verbatim so the LLM-visible schema does not
 * change. See `docs/decisions/2026-04-22-debt-split-session-query-tool.md`.
 */
internal val SESSION_QUERY_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("select") {
            put("type", "string")
            put(
                "description",
                "sessions | messages | parts | forks | ancestors | tool_calls | " +
                    "compactions | status | session_metadata | message | spend | spend_summary | " +
                    "cache_stats | " +
                    "context_pressure | run_state_history | tool_spec_budget | run_failure | " +
                    "fallback_history | cancellation_history | permission_history | " +
                    "permission_rules | preflight_summary | recap | step_history | active_run_summary | bus_trace.",
            )
        }
        putJsonObject("sessionId") {
            put("type", "string")
            put(
                "description",
                "Session id. Required for messages/parts/forks/ancestors/tool_calls/compactions/status. " +
                    "Rejected for select=sessions.",
            )
        }
        putJsonObject("projectId") {
            put("type", "string")
            put("description", "Project filter. select=sessions only.")
        }
        putJsonObject("includeArchived") {
            put("type", "boolean")
            put("description", "Include archived sessions. select=sessions only. Default false.")
        }
        putJsonObject("role") {
            put("type", "string")
            put(
                "description",
                "Message role filter: user | assistant. select=messages only.",
            )
        }
        putJsonObject("kind") {
            put("type", "string")
            put(
                "description",
                "Part kind: text | reasoning | tool | media | timeline-snapshot | " +
                    "render-progress | step-start | step-finish | compaction | todos. " +
                    "select=parts only.",
            )
        }
        putJsonObject("includeCompacted") {
            put("type", "boolean")
            put(
                "description",
                "Include compacted rows. select=parts and select=tool_calls only. Default true.",
            )
        }
        putJsonObject("toolId") {
            put("type", "string")
            put("description", "Filter tool parts by toolId. select=tool_calls only.")
        }
        putJsonObject("messageId") {
            put("type", "string")
            put(
                "description",
                "Message id. Required for select=message; optional on run_failure.",
            )
        }
        putJsonObject("limit") {
            put("type", "integer")
            put(
                "description",
                "Max rows (default 100, clamped to [1, 1000]). Applied after filter+sort+offset.",
            )
        }
        putJsonObject("offset") {
            put("type", "integer")
            put("description", "Skip N rows after filter+sort (default 0).")
        }
        putJsonObject("sinceEpochMs") {
            put("type", "integer")
            put(
                "description",
                "Epoch-millis lower bound for select=run_state_history — drop transitions " +
                    "older than this. Null returns the full ring buffer (capped at 256 entries " +
                    "per session). Rejected for other selects.",
            )
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("select"))))
    put("additionalProperties", false)
}
