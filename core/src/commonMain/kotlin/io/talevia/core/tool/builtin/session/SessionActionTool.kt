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
import io.talevia.core.tool.builtin.session.action.executeSessionRename
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
) : Tool<SessionActionTool.Input, SessionActionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional for archive / unarchive / rename (defaults to
         * `ToolContext.sessionId`). REQUIRED for delete — deleting the
         * owning session by context would be self-destructive while
         * the dispatch is running.
         */
        val sessionId: String? = null,
        /** `"archive"`, `"unarchive"`, `"rename"`, `"delete"`, or `"remove_permission_rule"`. */
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
    )

    override val id: String = "session_action"
    override val helpText: String =
        "Session lifecycle: `archive`/`unarchive` (idempotent), `rename`+`newTitle`, " +
            "`delete`+required `sessionId` (irreversible), `remove_permission_rule`+" +
            "(`permission`,`pattern`) drops a persisted Always rule. sessionId defaults to " +
            "owning session except on delete. Permission: session.write (delete=session.destructive)."
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
                    "`archive`, `unarchive`, `rename`, `delete`, or `remove_permission_rule`.",
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
            else -> error(
                "unknown action '${input.action}'; accepted: archive, unarchive, rename, delete, remove_permission_rule",
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
