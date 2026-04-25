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
 * `ProjectActionToolSchema.kt` / `SourceNodeActionToolSchema.kt` —
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
            put(
                "description",
                "Required for action=delete. Optional for archive/unarchive/rename (defaults to the owning session).",
            )
        }
        putJsonObject("action") {
            put("type", "string")
            put(
                "description",
                "`archive`, `unarchive`, `rename`, `delete`, `remove_permission_rule`, `import`, `set_system_prompt`, `export_bus_trace`, `set_tool_enabled`, `set_spend_cap`, `fork`, `export`, `revert`, or `compact`.",
            )
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
            put(
                "description",
                "Required for action=rename (must be non-blank). Optional for action=fork " +
                    "(defaults to '<parent title> (fork)').",
            )
        }
        putJsonObject("permission") {
            put("type", "string")
            put("description", "Required for action=remove_permission_rule. e.g. fs.write.")
        }
        putJsonObject("pattern") {
            put("type", "string")
            put("description", "Required for action=remove_permission_rule. Exact-match.")
        }
        putJsonObject("envelope") {
            put("type", "string")
            put(
                "description",
                "Required for action=import. The exact envelope string returned by " +
                    "session_action(action=export, format=json) — formatVersion will be checked, target " +
                    "projectId must already exist on this machine, sessionId collision " +
                    "fails loud.",
            )
        }
        putJsonObject("systemPromptOverride") {
            put("type", "string")
            put(
                "description",
                "Used by action=set_system_prompt. Verbatim new value: " +
                    "non-null sets the override (empty string = legitimate no-prompt " +
                    "override, NOT conflated with null), omitting the field clears the " +
                    "override so subsequent turns fall back to the Agent default.",
            )
        }
        putJsonObject("format") {
            put("type", "string")
            put(
                "description",
                "Used by action=export_bus_trace (`\"jsonl\"` default | `\"json\"`) and " +
                    "action=export (`\"json\"` default for the portable envelope | " +
                    "`\"markdown\"` alias `\"md\"` for a human-readable transcript).",
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
            put(
                "description",
                "Used by action=export_bus_trace. Cap on most-recent entries to include " +
                    "(default = full ring buffer). Must be ≥ 1 if set.",
            )
        }
        putJsonObject("toolId") {
            put("type", "string")
            put(
                "description",
                "Required for action=set_tool_enabled. Tool id to flip in the session's " +
                    "disabledToolIds set, e.g. 'generate_video'. Not validated against the " +
                    "registry — disabledToolIds may legitimately reference an env-gated " +
                    "tool that isn't loaded right now.",
            )
        }
        putJsonObject("enabled") {
            put("type", "boolean")
            put(
                "description",
                "Required for action=set_tool_enabled. true = enable (remove from " +
                    "disabled set); false = disable (add). No-op when already in the " +
                    "requested state.",
            )
        }
        putJsonObject("capCents") {
            put(
                "type",
                JsonArray(listOf(JsonPrimitive("integer"), JsonPrimitive("null"))),
            )
            put(
                "description",
                "Used by action=set_spend_cap. AIGC spend cap in cents. null clears " +
                    "the cap. 0 blocks all paid AIGC calls (each one ASKs). Positive " +
                    "cents sets the budget (e.g. 500 = $5.00). Must be ≥ 0 when " +
                    "non-null. No-op when already in the requested state.",
            )
        }
        putJsonObject("anchorMessageId") {
            put("type", "string")
            put(
                "description",
                "Used by action=fork (optional — anchor for partial copy; omit to copy " +
                    "whole history) and action=revert (REQUIRED — rewind target; every " +
                    "message strictly after this id is deleted). Anchor that doesn't " +
                    "belong to the action's session fails loud.",
            )
        }
        putJsonObject("prettyPrint") {
            put("type", "boolean")
            put(
                "description",
                "Used by action=export. Pretty-print the JSON envelope. Default false " +
                    "(compact wire shape). Markdown format ignores this flag.",
            )
        }
        putJsonObject("projectId") {
            put("type", "string")
            put(
                "description",
                "Required for action=revert. Project whose timeline rolls back to the " +
                    "most recent `Part.TimelineSnapshot` at-or-before anchorMessageId. " +
                    "Pass through the session's bound projectId — mismatch is caller's " +
                    "responsibility (no implicit derive).",
            )
        }
        putJsonObject("strategy") {
            put("type", "string")
            put(
                "description",
                "Used by action=compact. summarize_and_prune (default) prunes oldest " +
                    "tool outputs and writes an LLM-generated summary part. prune_only " +
                    "(alias prune, no_summary) prunes only — no provider call, no " +
                    "summary part written. Unknown values fall back to the default.",
            )
        }
    }
    put("required", JsonArray(listOf(JsonPrimitive("action"))))
    put("additionalProperties", false)
}
