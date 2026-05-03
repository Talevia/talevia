package io.talevia.core.tool.builtin.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON schema surface for [SessionActionTool]. Extracted to its own
 * file in cycle 150 to mirror `ClipActionToolSchema.kt` /
 * `ProjectLifecycleActionToolSchema.kt` / `SourceNodeActionToolSchema.kt` —
 * keeps the dispatcher class focused on shape + business logic and
 * lets the schema grow (per-action enums, future verbs) without
 * re-puffing the tool body past R.5.4's 800 LOC strong-P0 threshold.
 *
 * Trigger for the split: post cycle-147's `action="compact"` fold,
 * SessionActionTool.kt was 802 LOC (`debt-split-session-action-tool`).
 * The schema body alone is ~175 lines of JSON-builder DSL —
 * mechanically the largest single block in the file.
 *
 * The schema is byte-identical to what the dispatcher used to emit;
 * `SessionActionToolSchemaTest` round-trips the new top-level constant
 * against the dispatcher's `inputSchema` to lock that in.
 */
internal val SESSION_ACTION_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("sessionId") {
            put("type", "string")
            put("description", "delete: required. archive/unarchive/rename: defaults to current session.")
        }
        putJsonObject("action") {
            put("type", "string")
            put(
                "enum",
                JsonArray(
                    listOf(
                        JsonPrimitive("archive"),
                        JsonPrimitive("unarchive"),
                        JsonPrimitive("rename"),
                        JsonPrimitive("delete"),
                        JsonPrimitive("remove_permission_rule"),
                        JsonPrimitive("import"),
                        JsonPrimitive("set_system_prompt"),
                        JsonPrimitive("export_bus_trace"),
                        JsonPrimitive("set_tool_enabled"),
                        JsonPrimitive("set_spend_cap"),
                        JsonPrimitive("fork"),
                        JsonPrimitive("export"),
                        JsonPrimitive("revert"),
                        JsonPrimitive("compact"),
                    ),
                ),
            )
        }
        putJsonObject("newTitle") {
            put("type", "string")
            put("description", "rename: required, non-blank. fork: optional (default '<parent> (fork)').")
        }
        putJsonObject("permission") {
            put("type", "string")
            put("description", "remove_permission_rule: required (e.g. fs.write).")
        }
        putJsonObject("pattern") {
            put("type", "string")
            put("description", "remove_permission_rule: required, exact-match.")
        }
        putJsonObject("envelope") {
            put("type", "string")
            put(
                "description",
                "import: envelope string from action=export(format=json). " +
                    "formatVersion checked; target projectId must exist; sessionId collision fails.",
            )
        }
        putJsonObject("systemPromptOverride") {
            put("type", "string")
            put(
                "description",
                "set_system_prompt: non-null sets override (empty = no-prompt override); " +
                    "omitting the field clears (falls back to Agent default).",
            )
        }
        putJsonObject("format") {
            put("type", "string")
            put(
                "description",
                "export_bus_trace: jsonl (default) | json. export: json (default) | markdown (alias md).",
            )
            put(
                "enum",
                JsonArray(
                    listOf(
                        JsonPrimitive("jsonl"),
                        JsonPrimitive("json"),
                        JsonPrimitive("markdown"),
                        JsonPrimitive("md"),
                    ),
                ),
            )
        }
        putJsonObject("limit") {
            put("type", "integer")
            put("description", "export_bus_trace: most-recent N entries (default = full); ≥ 1.")
        }
        putJsonObject("toolId") {
            put("type", "string")
            put(
                "description",
                "set_tool_enabled: tool id to flip in disabledToolIds (e.g. generate_video). " +
                    "Not validated against registry (env-gated tools allowed).",
            )
        }
        putJsonObject("enabled") {
            put("type", "boolean")
            put("description", "set_tool_enabled: true = enable, false = disable. No-op if already in state.")
        }
        putJsonObject("capCents") {
            put(
                "type",
                JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null"))),
            )
            put(
                "description",
                "set_spend_cap: AIGC cap (cents). null clears; 0 blocks paid AIGC; positive sets " +
                    "budget (500 = \$5.00); ≥ 0 when non-null.",
            )
        }
        putJsonObject("anchorMessageId") {
            put("type", "string")
            put(
                "description",
                "fork: optional partial-copy anchor (omit = copy all). " +
                    "revert: REQUIRED rewind target (messages strictly after deleted). " +
                    "Anchor not in action's session fails.",
            )
        }
        putJsonObject("prettyPrint") {
            put("type", "boolean")
            put("description", "export: pretty-print JSON envelope (default false). Markdown ignores.")
        }
        putJsonObject("projectId") {
            put("type", "string")
            put(
                "description",
                "revert: project whose timeline rolls back to the most-recent Part.TimelineSnapshot " +
                    "at-or-before anchorMessageId. Pass session's bound projectId.",
            )
        }
        putJsonObject("strategy") {
            put("type", "string")
            put(
                "description",
                "compact: summarize_and_prune (default; prunes oldest tool outputs + writes LLM " +
                    "summary part) | prune_only (alias prune / no_summary; prunes only). " +
                    "Unknown → default.",
            )
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("action"))))
    put("additionalProperties", false)
}
