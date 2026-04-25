package io.talevia.core.tool.builtin.session

import io.talevia.core.permission.PermissionRulesPersistence
import io.talevia.core.permission.PermissionSpec
import io.talevia.core.session.SessionStore
import io.talevia.core.tool.Tool
import io.talevia.core.tool.ToolContext
import io.talevia.core.tool.ToolResult
import io.talevia.core.tool.builtin.session.action.executeRemovePermissionRule
import io.talevia.core.tool.builtin.session.action.executeSessionArchive
import io.talevia.core.tool.builtin.session.action.executeSessionCompact
import io.talevia.core.tool.builtin.session.action.executeSessionDelete
import io.talevia.core.tool.builtin.session.action.executeSessionExport
import io.talevia.core.tool.builtin.session.action.executeSessionExportBusTrace
import io.talevia.core.tool.builtin.session.action.executeSessionFork
import io.talevia.core.tool.builtin.session.action.executeSessionImport
import io.talevia.core.tool.builtin.session.action.executeSessionRename
import io.talevia.core.tool.builtin.session.action.executeSessionRevert
import io.talevia.core.tool.builtin.session.action.executeSessionSetSpendCap
import io.talevia.core.tool.builtin.session.action.executeSessionSetSystemPrompt
import io.talevia.core.tool.builtin.session.action.executeSessionSetToolEnabled
import io.talevia.core.tool.builtin.session.action.executeSessionUnarchive
import kotlinx.datetime.Clock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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
    /**
     * Bus used by `action=revert` to publish
     * `BusEvent.SessionReverted` so UIs refresh atomically, and by
     * `action=compact` to feed the Compactor's progress events.
     * Default `null` keeps existing test rigs source-compatible;
     * revert / compact fail loud when missing, mirroring the other
     * "missing dep" patterns above.
     */
    private val bus: io.talevia.core.bus.EventBus? = null,
    /**
     * Provider registry used by `action=compact` to look up the
     * session's bound model and run the compactor's summary
     * round-trip. Default `null` because `ProviderRegistry` is
     * built FROM the same registry this tool registers into — the
     * first-pass `registerSessionAndMetaTools` registration must
     * stay null-providers, and each `AppContainer` re-registers
     * `SessionActionTool` in the second pass with the now-built
     * providers (per the existing
     * `DefaultBuiltinRegistrations` chicken-and-egg comment).
     * Compact fails loud at dispatch time when null, so test rigs
     * that don't exercise compaction stay source-compatible.
     */
    private val providers: io.talevia.core.provider.ProviderRegistry? = null,
) : Tool<SessionActionTool.Input, SessionActionTool.Output> {

    @Serializable data class Input(
        /**
         * Optional for archive / unarchive / rename (defaults to
         * `ToolContext.sessionId`). REQUIRED for delete — deleting the
         * owning session by context would be self-destructive while
         * the dispatch is running.
         */
        val sessionId: String? = null,
        /** `"archive"`, `"unarchive"`, `"rename"`, `"delete"`, `"remove_permission_rule"`, `"import"`, `"set_system_prompt"`, `"export_bus_trace"`, `"set_tool_enabled"`, `"set_spend_cap"`, `"fork"`, `"export"`, `"revert"`, or `"compact"`. */
        val action: String,
        /**
         * `action="rename"`: required, must be non-blank — the renamed
         * session's new title.
         * `action="fork"`: optional new title for the branch. Defaults
         * to `"<parent title> (fork)"` when null/blank.
         * Ignored on every other action.
         */
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
         * produced by `session_action(action="export")` (format
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
         * Used by `action=export_bus_trace` and `action=export`.
         *
         * For `export_bus_trace`: `"jsonl"` (default) emits one
         * `BusEventTraceRecorder.Entry` JSON object per line —
         * stream-friendly + grep-friendly. `"json"` emits a single
         * JSON array — easier for one-shot tools that decode a whole
         * file.
         *
         * For `export`: `"json"` (default) emits the portable
         * envelope `talevia-session-export-v1` consumed by
         * `action="import"`. `"markdown"` (alias `"md"`) emits a
         * human-readable transcript with tool calls folded into
         * GitHub-style callouts — meant for bug reports / docs /
         * offline reading, NOT for re-import. Unknown values fall
         * back to `"json"`.
         *
         * Ignored on every other action.
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
        /**
         * Used by `action=set_spend_cap`. Cents cap for AIGC spend on
         * this session: `null` = clear (no budget gating), `0` = block
         * every paid AIGC call (each one ASKs), positive integer =
         * cap in cents (e.g. `500` = $5.00). Negative values are
         * rejected loud — the most common shape of that mistake is
         * the user confusing dollars with cents.
         *
         * The cap is consulted by
         * [io.talevia.core.tool.builtin.aigc.AigcBudgetGuard] on every
         * AIGC dispatch; once cumulative session spend reaches the cap
         * the guard raises an `aigc.budget` permission ASK and the
         * user decides whether to continue, stop, or persist an
         * override. Inspect current spend via
         * `session_query(select=spend)`.
         */
        val capCents: Long? = null,
        /**
         * Used by `action=fork` and `action=revert`.
         *
         * `fork`: optional anchor — only messages at-or-before this id
         * (in parent's `(createdAt, id)` order) are copied to the branch.
         * Everything after is dropped. Omit / null to copy the whole
         * parent history.
         *
         * `revert`: REQUIRED. Rewind target — every message strictly
         * after this id (in the session's `(createdAt, id)` order)
         * is deleted, parts included.
         *
         * Anchor that doesn't belong to the action's session fails
         * loud (the store's `require` surfaces it verbatim).
         */
        val anchorMessageId: String? = null,
        /**
         * `action=export` only. Pretty-print the JSON envelope?
         * Default false (compact wire shape). Markdown format
         * ignores this flag (its rendering doesn't have a "compact"
         * variant). Ignored on every other action.
         */
        val prettyPrint: Boolean = false,
        /**
         * `action=revert` only. Project whose timeline is rolled back
         * to the most recent `Part.TimelineSnapshot` at-or-before
         * `anchorMessageId`. Required for revert; ignored on every
         * other action. The session's own `Session.projectId` binding
         * is the natural source — pass it through explicitly so the
         * caller can audit the cross-store mutation; mismatch is
         * caller's responsibility (no implicit derive, no §3a #6
         * silent fallback).
         */
        val projectId: String? = null,
        /**
         * `action=compact` only. Optional compaction strategy:
         * `"summarize_and_prune"` (default; alias `"default"`,
         * `"summarise_and_prune"`) prunes oldest tool outputs and
         * writes an LLM-generated summary part. `"prune_only"`
         * (alias `"prune"`, `"no_summary"`) prunes only — no
         * provider call, no summary part written; right pick when
         * the session is mostly tool calls + outputs with very
         * little prose between them. Unknown values fall back to
         * the default — typos can't silently skip the summary call.
         */
        val strategy: String? = null,
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
        /**
         * `set_spend_cap` only: cents cap as it stood before this
         * call (so the caller can reason about idempotency / undo).
         * Null when the action is not set_spend_cap, OR when the
         * action was set_spend_cap and the prior cap was null
         * ("cleared"). The action echo discriminates the two cases.
         */
        val previousSpendCapCents: Long? = null,
        /**
         * `set_spend_cap` only: cents cap after this call — echoes
         * the input. Null when the action is not set_spend_cap, OR
         * when the action was set_spend_cap and the new cap is null
         * (the cap was cleared). The action echo discriminates.
         */
        val spendCapCents: Long? = null,
        /**
         * `fork` only: id of the new branch session minted by the
         * fork. The parent session id is in [sessionId] (the action's
         * input target). Null on every other action.
         */
        val forkedSessionId: String? = null,
        /**
         * `fork` only: anchor message id echoed for caller convenience
         * — null means the whole parent history was copied. Always-null
         * on every other action.
         */
        val forkAnchorMessageId: String? = null,
        /**
         * `fork` only: number of parent messages copied into the
         * branch. Zero on every other action.
         */
        val forkCopiedMessageCount: Int = 0,
        /**
         * `export` only: serialized session envelope (JSON or
         * markdown depending on requested format). Empty string on
         * every other action. Pair with `write_file` to persist.
         */
        val exportedSessionEnvelope: String = "",
        /**
         * `export` only: format-version tag the export landed in
         * (`talevia-session-export-v1` for JSON,
         * `talevia-session-export-md-v1` for markdown). Empty string
         * on every other action.
         */
        val exportedSessionFormatVersion: String = "",
        /**
         * `export` only: number of messages the envelope contains.
         * Zero on every other action.
         */
        val exportedSessionMessageCount: Int = 0,
        /**
         * `export` only: number of parts the envelope contains
         * (lossless — includes compacted parts so a round-trip
         * yields a bit-equal session). Zero on every other action.
         */
        val exportedSessionPartCount: Int = 0,
        /**
         * `export` only: format string the export landed in
         * (`"json"` or `"markdown"`), echoed for caller convenience.
         * Empty string on every other action.
         */
        val exportedSessionFormat: String = "",
        /**
         * `export` only: project id the exported session is bound to
         * (re-importing the envelope on another instance requires
         * the target to have the same project). Empty string on
         * every other action.
         */
        val exportedSessionProjectId: String = "",
        /**
         * `revert` only: number of messages strictly after the anchor
         * that were deleted from the session (parts included). Zero
         * on every other action.
         */
        val revertDeletedMessages: Int = 0,
        /**
         * `revert` only: id of the `Part.TimelineSnapshot` whose body
         * was applied to the project's timeline. Null when no
         * snapshot existed at-or-before the anchor (timeline left
         * untouched), or when the action is not revert.
         */
        val revertAppliedSnapshotPartId: String? = null,
        /**
         * `revert` only: number of clips in the timeline after the
         * snapshot restore. Zero on every other action.
         */
        val revertRestoredClipCount: Int = 0,
        /**
         * `revert` only: number of tracks in the timeline after the
         * snapshot restore. Zero on every other action.
         */
        val revertRestoredTrackCount: Int = 0,
        /**
         * `compact` only: `true` when the run actually compacted
         * (either summarise+prune or prune-only path). `false` on
         * skip / no-op. Always-false on every other action.
         */
        val compacted: Boolean = false,
        /**
         * `compact` only: count of parts whose `time_compacted`
         * stamp was written in this run. Zero on every other action
         * or on a skip.
         */
        val compactPrunedPartCount: Int = 0,
        /**
         * `compact` only: id of the new `Part.Compaction` attached
         * to the latest assistant message (summarise path only).
         * Null on prune-only / skip / non-compact actions.
         */
        val compactPartId: String? = null,
        /**
         * `compact` only: first 200 chars of the LLM-generated
         * summary (summarise path only). Null on prune-only / skip /
         * non-compact actions. Use `read_part` for the full text.
         */
        val compactSummaryPreview: String? = null,
        /**
         * `compact` only: human-readable reason populated when the
         * run was a no-op (no assistant messages, missing provider,
         * compactor decided nothing to do). Null when compaction
         * actually ran or on every other action.
         */
        val compactSkipReason: String? = null,
        /**
         * `compact` only: echoed strategy that ran —
         * `"summarize_and_prune"` or `"prune_only"`. Empty string
         * on every other action.
         */
        val compactStrategy: String = "",
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
            "the requested state), `set_spend_cap`+`capCents` configures the AIGC spend " +
            "cap (capCents=null clears, 0 blocks, positive = cents), `fork` branches the " +
            "session — creates a new session with parentId pointing at the source, copies " +
            "messages up to optional `anchorMessageId` (omit to copy whole history); use " +
            "for 'try a different approach from here' flows. `newTitle` optional (defaults " +
            "to '<parent title> (fork)'). `export`+optional `format`/`prettyPrint` serializes " +
            "the session (metadata + every message + every part, including compacted) into " +
            "a portable JSON envelope (`format=json`, default — pair with action=import for " +
            "round-trip) or a human-readable markdown transcript (`format=markdown`, alias " +
            "`md`). `revert`+`sessionId`+`anchorMessageId`+`projectId` DESTRUCTIVELY rewinds: " +
            "deletes every message strictly after the anchor and rolls the project timeline " +
            "back to the most recent `Part.TimelineSnapshot` at-or-before the anchor (no " +
            "un-revert; pair with project_snapshot_action(action=save) for project-level " +
            "safety nets). `compact`+optional `strategy` proactively triggers two-phase " +
            "context compaction (the same Compactor the Agent runs automatically at the " +
            "120k-token threshold); `strategy=prune_only` skips the LLM summary call (cheaper; " +
            "right for tool-heavy sessions); default `summarize_and_prune` keeps current " +
            "behaviour. sessionId defaults to owning session except on delete and import. " +
            "Permission: session.write (delete=session.destructive, " +
            "export/export_bus_trace=session.read)."
    override val inputSerializer: KSerializer<Input> = serializer()
    override val outputSerializer: KSerializer<Output> = serializer()

    /**
     * Base tier `session.write`. [permissionFrom] upgrades to
     * `session.destructive` only when the raw input JSON contains
     * `"action":"delete"` and downgrades to `session.read` for
     * `"action":"export"` / `"action":"export_bus_trace"` (both pure
     * read paths). Any other action (or malformed input) stays at the
     * base `session.write` tier — defaulting to a tier no LOWER than
     * the base on parse failure ensures a malformed input cannot bypass
     * a stricter gate. The downgrade is safe in the other direction:
     * the actual export handlers fail loud at dispatch if the input
     * doesn't match the action's contract, so a malformed export
     * payload never gets to write anything.
     */
    override val permission: PermissionSpec = PermissionSpec(
        permission = "session.write",
        permissionFrom = { inputJson ->
            when {
                isDeleteAction(inputJson) -> "session.destructive"
                isExportAction(inputJson) || isExportBusTraceAction(inputJson) -> "session.read"
                else -> "session.write"
            }
        },
    )

    override val inputSchema: JsonObject = SESSION_ACTION_INPUT_SCHEMA

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
            "set_spend_cap" -> executeSessionSetSpendCap(sessions, clock, input, ctx)
            "fork" -> executeSessionFork(sessions, input, ctx)
            "export" -> executeSessionExport(sessions, input, ctx)
            "revert" -> executeSessionRevert(sessions, projects, bus, input)
            "compact" -> executeSessionCompact(sessions, providers, bus, input, ctx)
            else -> error(
                "unknown action '${input.action}'; accepted: archive, unarchive, rename, delete, " +
                    "remove_permission_rule, import, set_system_prompt, export_bus_trace, " +
                    "set_tool_enabled, set_spend_cap, fork, export, revert, compact",
            )
        }
    }

    private companion object {
        /**
         * Regex-checks for action discrimination before kotlinx.serialization
         * decode, used by `permissionFrom`'s tier gate. Any failed match
         * defaults to the BASE tier (`session.write`) — safe in both
         * directions:
         *  - Malformed delete input stays at write (can't bypass to a
         *    looser tier).
         *  - Malformed export input stays at write (the pure-read tier
         *    is a downgrade; defaulting to write is a no-loss).
         *
         * Each action's actual handler runs after decode and re-validates
         * the input shape, so malformed payloads fail loud at dispatch
         * without ever touching the store.
         */
        private val DELETE_ACTION_REGEX = Regex(
            pattern = """"action"\s*:\s*"delete"""",
            option = RegexOption.IGNORE_CASE,
        )
        private val EXPORT_ACTION_REGEX = Regex(
            pattern = """"action"\s*:\s*"export"""",
            option = RegexOption.IGNORE_CASE,
        )
        private val EXPORT_BUS_TRACE_ACTION_REGEX = Regex(
            pattern = """"action"\s*:\s*"export_bus_trace"""",
            option = RegexOption.IGNORE_CASE,
        )

        fun isDeleteAction(inputJson: String): Boolean = DELETE_ACTION_REGEX.containsMatchIn(inputJson)
        fun isExportAction(inputJson: String): Boolean = EXPORT_ACTION_REGEX.containsMatchIn(inputJson)
        fun isExportBusTraceAction(inputJson: String): Boolean =
            EXPORT_BUS_TRACE_ACTION_REGEX.containsMatchIn(inputJson)
    }
}
