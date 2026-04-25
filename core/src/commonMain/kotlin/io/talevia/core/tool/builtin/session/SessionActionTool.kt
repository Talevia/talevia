package io.talevia.core.tool.builtin.session

import io.talevia.core.permission.PermissionRulesPersistence
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.action.executeRemovePermissionRule
import io.talevia.core.tool.builtin.session.action.executeSessionArchive
import io.talevia.core.tool.builtin.session.action.executeSessionDelete
import io.talevia.core.tool.builtin.session.action.executeSessionExportBusTrace
import io.talevia.core.tool.builtin.session.action.executeSessionImport
import io.talevia.core.tool.builtin.session.action.executeSessionRename
import io.talevia.core.tool.builtin.session.action.executeSessionSetSystemPrompt
import io.talevia.core.tool.builtin.session.action.executeSessionSetToolEnabled
import io.talevia.core.tool.builtin.session.action.executeSessionUnarchive
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer

/**
 * Session lifecycle verbs — consolidated form that replaces
 * `ArchiveSessionTool` + `UnarchiveSessionTool` + `RenameSessionTool`
 * + `DeleteSessionTool`
 * (`debt-consolidate-session-lifecycle-verbs`, 2026-04-23).
 *
 * Four action branches, all preserving the original tool's behaviour
 * verbatim:
 *
 * - `action="archive"` — flip `Session.archived` on. Idempotent:
 *   `wasArchived=true` on an already-archived session is a no-op.
 *   `sessionId` optional (defaults to `ToolContext.sessionId`).
 *   Permission `session.write`.
 * - `action="unarchive"` — flip `Session.archived` off. Idempotent.
 *   `sessionId` optional. Permission `session.write`.
 * - `action="rename"` — update `Session.title`. `sessionId` optional.
 *   `newTitle` required + must be non-blank. Identical-title is a
 *   no-op. Permission `session.write`.
 * - `action="delete"` — permanently delete the session + all its
 *   messages/parts (ON DELETE CASCADE). `sessionId` REQUIRED
 *   (deleting the owning session by context would be self-destructive
 *   mid-dispatch). Permission `session.destructive` — enforced
 *   via [PermissionSpec.permissionFrom] (cycle 21 extension) so the
 *   other three actions stay at the `session.write` tier without a
 *   permission regression.
 *
 * ## Output
 *
 * Shared headers (`sessionId`, `action`, `title`). Action-specific
 * extras carried in optional fields populated only on their branch:
 * `wasAlreadyInTargetState` (archive/unarchive idempotency marker),
 * `previousTitle` + `newTitle` (rename), `archived` (delete snapshot).
 * Unused fields default to empty/false/null — matches the
 * cycle-19 / 21 / 22 consolidation output shape.
 */
class SessionActionTool(
    private val sessions: SessionStore,
    private val clock: Clock = Clock.System,
    /**
     * Permission-rules persistence used by
     * `action=remove_permission_rule`. Default
     * [PermissionRulesPersistence.Noop] preserves pre-cycle-95
     * behaviour for test rigs / containers that don't wire the file-
     * backed persistence (Desktop / Server / Android / iOS today).
     * On Noop, remove_permission_rule loads an empty list and reports
     * `removedRuleCount=0` rather than failing — same convention as
     * the other optional aggregator wires.
     */
    private val permissionRulesPersistence: PermissionRulesPersistence = PermissionRulesPersistence.Noop,
    /**
     * Project store used by `action=import` to verify the envelope's
     * target project exists on this machine before creating the
     * imported session. Default `null` keeps test rigs that only
     * exercise lifecycle actions source-compatible; `import` fails
     * loud at dispatch time when the field is missing.
     */
    private val projects: io.talevia.core.domain.ProjectStore? = null,
    /**
     * In-memory ring-buffer recorder used by `action=export_bus_trace`
     * to flush a session's recent `BusEvent` history as JSONL / JSON.
     * Default `null` keeps existing test rigs source-compatible;
     * the export action fails loud when the field is unset, mirroring
     * the `import` / `remove_permission_rule` "missing dep" pattern.
     */
    private val busTrace: io.talevia.core.bus.BusEventTraceRecorder? = null,
) : Tool<SessionActionTool.Input, SessionActionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional for archive / unarchive / rename (defaults to
         * `ToolContext.sessionId`). REQUIRED for delete — deleting the
         * owning session by context would be self-destructive while
         * the dispatch is running.
         */
        val sessionId: String? = null,
        /** `"archive"`, `"unarchive"`, `"rename"`, `"delete"`, `"remove_permission_rule"`, `"import"`, `"set_system_prompt"`, `"export_bus_trace"`, or `"set_tool_enabled"`. */
        val action: String,
        /** Required for `action="rename"`. Must be non-blank. Ignored on other actions. */
        val newTitle: String? = null,
        /**
         * Permission keyword (e.g. `"fs.write"`) the rule to remove
         * matches against. Required for `action=remove_permission_rule`;
         * ignored elsewhere. The persistent ruleset can hold multiple
         * rules with the same `permission` but different `pattern`s —
         * pair this with [pattern] to drop one specific entry.
         */
        val permission: String? = null,
        /**
         * Pattern the rule to remove matches against (e.g.
         * `https://example.com` or `/tmp/...`). Required for
         * `action=remove_permission_rule`; ignored elsewhere.
         * Exact-match against the persisted rule's `pattern`. To
         * clear every rule for a permission, the caller can list
         * rules first via `session_query(select=permission_rules)`
         * and remove each pair in turn.
         */
        val pattern: String? = null,
        /**
         * Required for `action=import`. The serialized envelope
         * produced by `export_session` (format
         * `talevia-session-export-v1`). The import path verifies
         * `formatVersion`, requires the envelope's target
         * `projectId` to already exist on this machine (import a
         * project first if the target is missing — this tool
         * does not create projects), refuses to overwrite an
         * existing session id (delete the existing one first if
         * that's the intent), then materialises the session +
         * messages + parts verbatim. Ignored on every other action.
         */
        val envelope: String? = null,
        /**
         * Used by `action=set_system_prompt`. Verbatim new value of
         * `Session.systemPromptOverride`: non-null sets the override
         * (empty string is a legitimate "run with no system prompt"
         * override and is NOT conflated with null), null clears it
         * so subsequent turns fall back to the Agent's default
         * system prompt. Ignored on every other action — including
         * actions that don't carry this field at all.
         */
        val systemPromptOverride: String? = null,
        /**
         * Used by `action=export_bus_trace`. `"jsonl"` (default) emits
         * one `BusEventTraceRecorder.Entry` JSON object per line —
         * stream-friendly + grep-friendly. `"json"` emits a single
         * JSON array — easier for one-shot tools that decode a whole
         * file. Ignored on every other action.
         */
        val format: String? = null,
        /**
         * Used by `action=export_bus_trace`. Cap on number of most-
         * recent entries to include. Null = no cap (full ring buffer
         * up to `BusEventTraceRecorder.DEFAULT_CAPACITY_PER_SESSION`).
         * Ignored on every other action.
         */
        val limit: Int? = null,
        /**
         * Required for `action=set_tool_enabled`. Tool id to flip in
         * the session's `disabledToolIds` set, e.g. `"generate_video"`.
         * Ignored on every other action. NOT validated against the
         * registry — `disabledToolIds` is per-session persisted state
         * and may legitimately reference an env-gated tool that isn't
         * loaded right now (it'll still be filtered out if it ever is).
         */
        val toolId: String? = null,
        /**
         * Required for `action=set_tool_enabled`. Upsert flag:
         * `false` adds `toolId` to the disabled set (no-op when already
         * disabled); `true` removes it (no-op when already enabled).
         * Ignored on every other action.
         */
        val enabled: Boolean? = null,
    )

    @Serializable data class Output(
        val sessionId: String,
        val action: String,
        val title: String,
        /** `archive` / `unarchive`: true when the session was already in the target state. */
        val wasAlreadyInTargetState: Boolean = false,
        /** `rename` only: the title as seen before this call. */
        val previousTitle: String? = null,
        /** `rename` only: the title set by this call (echoed for convenience). */
        val newTitle: String? = null,
        /** `delete` only: `Session.archived` as seen before the session was removed. */
        val archived: Boolean = false,
        /**
         * `remove_permission_rule` only: count of persisted rules
         * removed by this call. Zero when no rule matched (or when
         * persistence is [PermissionRulesPersistence.Noop]).
         */
        val removedRuleCount: Int = 0,
        /**
         * `remove_permission_rule` only: count of persisted rules
         * remaining in the file after the remove. Helpful for the
         * agent to confirm "rule cleared, N other Always rules
         * still active".
         */
        val remainingRuleCount: Int = 0,
        /**
         * `import` only: the envelope's `formatVersion` echoed back
         * after a successful round-trip. Empty string when the
         * action is not import.
         */
        val importedFormatVersion: String = "",
        /** `import` only: number of messages the envelope landed. Zero otherwise. */
        val importedMessageCount: Int = 0,
        /** `import` only: number of parts the envelope landed. Zero otherwise. */
        val importedPartCount: Int = 0,
        /**
         * `set_system_prompt` only: previous value of
         * `Session.systemPromptOverride` as seen before this call (so
         * the caller can reason about idempotency / undo). Empty string
         * when the action is not set_system_prompt.
         */
        val previousSystemPromptOverride: String? = null,
        /**
         * `set_system_prompt` only: the override after this call —
         * echoes the input. Null when the call cleared the override.
         * Always-null when the action is not set_system_prompt.
         */
        val newSystemPromptOverride: String? = null,
        /**
         * `export_bus_trace` only: rendered JSONL or JSON body
         * containing the captured `BusEventTraceRecorder.Entry` rows
         * for the target session. Empty string when the action is
         * not export_bus_trace.
         */
        val exportedBusTrace: String = "",
        /**
         * `export_bus_trace` only: number of trace entries the
         * exported body contains (post-limit). Zero otherwise.
         */
        val exportedTraceEntryCount: Int = 0,
        /**
         * `export_bus_trace` only: format string the export landed in
         * (`"jsonl"` or `"json"`), echoed for caller convenience.
         * Empty string otherwise.
         */
        val exportedTraceFormat: String = "",
        /**
         * `set_tool_enabled` only: tool id the call targeted, echoed
         * for caller convenience. Empty string otherwise.
         */
        val toolId: String = "",
        /**
         * `set_tool_enabled` only: state after the write (`true` =
         * enabled / removed from disabled set; `false` = disabled /
         * added). Always-false on every other action.
         */
        val enabled: Boolean = false,
        /**
         * `set_tool_enabled` only: `true` when the call mutated the
         * session (toggle); `false` when it was a no-op. Always-false
         * on every other action.
         */
        val toolEnabledChanged: Boolean = false,
    )

    override val id: String = "session_action"
    override val helpText: String =
        "Session lifecycle: `archive`/`unarchive` (idempotent), `rename`+`newTitle`, " +
            "`delete`+required `sessionId` (irreversible), `remove_permission_rule`+" +
            "(`permission`,`pattern`) drops a persisted Always rule, `import`+`envelope` " +
            "materialises a previously-exported session (format `talevia-session-export-v1`; " +
            "envelope's target projectId must already exist; refuses to overwrite an existing " +
            "session id), `set_system_prompt`+`systemPromptOverride` swaps this session's " +
            "system prompt without spinning up a second Agent (null=clear, empty string=valid " +
            "no-prompt override), `export_bus_trace`+optional `format`/`limit` flushes the " +
            "session's `BusEventTraceRecorder` ring buffer to JSONL (default) or JSON for " +
            "offline triage, `set_tool_enabled`+`toolId`+`enabled` flips a tool in the " +
            "session's `disabledToolIds` set (disabled tools are filtered from the next " +
            "turn's tool spec — use to enforce 'stop using <tool>'; no-op when already in " +
            "the requested state). sessionId defaults to owning session except on delete " +
            "and import. Permission: session.write (delete=session.destructive)."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()

    /**
     * Base tier `session.write`. [permissionFrom] upgrades to
     * `session.destructive` only when the raw input JSON contains
     * `"action":"delete"` — any other action (or malformed input) stays
     * at `session.write`. Malformed-input defaulting to the LOWER tier
     * is safe here because every action except delete is already a
     * non-destructive mutation; a malformed input will fail validation
     * in [execute] before touching the store. The permission layer
     * just needs to not block the action from reaching that validation.
     */
    override val permission: PermissionSpec = PermissionSpec(
        permission = "session.write",
        permissionFrom = { inputJson ->
            if (isDeleteAction(inputJson)) "session.destructive" else "session.write"
        },
    )

    override val inputSchema: JsonObject = buildJsonObject {
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
                    "`archive`, `unarchive`, `rename`, `delete`, `remove_permission_rule`, `import`, `set_system_prompt`, `export_bus_trace`, or `set_tool_enabled`.",
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
                        ),
                    ),
                )
            }
            putJsonObject("newTitle") {
                put("type", "string")
                put("description", "Required for action=rename. Must be non-blank.")
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
                        "export_session(format=json) — formatVersion will be checked, target " +
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
                    "Used by action=export_bus_trace. `\"jsonl\"` (default) or `\"json\"`.",
                )
                put(
                    "enum",
                    JsonArray(listOf(JsonPrimitive("jsonl"), JsonPrimitive("json"))),
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
        }
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
        put("additionalProperties", false)
    }

    override suspend fun execute(input: Input, ctx: ToolContext): ToolResult<Output> {
        return when (input.action) {
            "archive" -> executeSessionArchive(sessions, clock, input, ctx)
            "unarchive" -> executeSessionUnarchive(sessions, clock, input, ctx)
            "rename" -> executeSessionRename(sessions, clock, input, ctx)
            "delete" -> executeSessionDelete(sessions, input)
            "remove_permission_rule" -> executeRemovePermissionRule(
                sessions, permissionRulesPersistence, input, ctx,
            )
            "import" -> executeSessionImport(sessions, projects, input)
            "set_system_prompt" -> executeSessionSetSystemPrompt(sessions, clock, input, ctx)
            "export_bus_trace" -> executeSessionExportBusTrace(busTrace, input, ctx)
            "set_tool_enabled" -> executeSessionSetToolEnabled(sessions, clock, input, ctx)
            else -> error(
                "unknown action '${input.action}'; accepted: archive, unarchive, rename, delete, " +
                    "remove_permission_rule, import, set_system_prompt, export_bus_trace, set_tool_enabled",
            )
        }
    }

    private companion object {
        /**
         * Regex-check for `"action":"delete"` in the raw input JSON, case-insensitive.
         * Runs before kotlinx.serialization decode for `permissionFrom`'s tier gate.
         * Any failed match defaults to the LOWER tier — safe here because a
         * malformed input can't reach the destructive branch (execute() fails first).
         */
        private val DELETE_ACTION_REGEX = Regex(
            pattern = """"action"\s*:\s*"delete"""",
            option = RegexOption.IGNORE_CASE,
        )

        fun isDeleteAction(inputJson: String): Boolean = DELETE_ACTION_REGEX.containsMatchIn(inputJson)
    }
}
