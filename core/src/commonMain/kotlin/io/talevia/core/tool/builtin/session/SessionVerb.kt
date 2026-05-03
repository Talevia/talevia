package io.talevia.core.tool.builtin.session

/**
 * Phase 1a of `debt-split-session-action-tool-input` (cycle 47).
 *
 * Sealed-type representation of [SessionActionTool.Input]'s 14 verbs.
 * Each subclass carries ONLY the fields its verb needs — no more
 * 17-field flat data class with 11 nullable optionals where each
 * verb actually uses 0–3 of them.
 *
 * **Phase 1a scope is pure addition.** This file is brand-new; the
 * existing flat `SessionActionTool.Input` data class stays unchanged;
 * the dispatcher's `when (input.action)` continues to read the flat
 * shape; tests + handlers keep their current signatures. The only
 * cross-cutting touchpoint is the `Input.toVerb()` decoder below —
 * call sites get to opt in over multiple cycles.
 *
 * **Phasing forward:**
 * - Phase 1b: rewrite the dispatcher's `when (input.action)` into
 *   `when (input.toVerb())` — single-cycle, ~30 LOC delta in
 *   SessionActionTool.kt only.
 * - Phase 1c: migrate handler signatures from `(SessionActionTool.Input, ...)`
 *   to `(SessionVerb.Archive, ...)` etc. — per-handler smart-cast
 *   becomes typed parameter; ~50 LOC across 11 handler files.
 * - Phase 1d: drop the flat `Input.toVerb()` indirection by routing
 *   the LLM JSON Schema through `@JsonClassDiscriminator("action")` on
 *   the sealed interface itself; tests migrate to construct verb
 *   subclasses directly.
 *
 * **Why interface, not sealed class:** no shared state across verbs
 * (sessionId is optional on 11 verbs, required on 1 — putting it in a
 * shared base class would force the required-on-1 case into a runtime
 * check anyway). Each subclass is independent; the `sealed` marker
 * keeps the family closed (no rogue verb impls outside this file's
 * package).
 *
 * **Why a separate file rather than nested under SessionActionTool**:
 * SessionActionTool.kt is already 606 LOC (R.5 #4 P0 split target).
 * Phase 1a's ~70 new LOC would push it past 670 — the splits the
 * later phases will do then have to relocate even more code. New
 * file holds the line.
 */
internal sealed interface SessionVerb {
    data class Archive(val sessionId: String?) : SessionVerb

    data class Unarchive(val sessionId: String?) : SessionVerb

    data class Rename(
        val sessionId: String?,
        val newTitle: String,
    ) : SessionVerb

    /** sessionId is REQUIRED — deleting via context binding is self-destructive while dispatching. */
    data class Delete(val sessionId: String) : SessionVerb

    data class RemovePermissionRule(
        val sessionId: String?,
        val permission: String,
        val pattern: String,
    ) : SessionVerb

    data class Import(val envelope: String) : SessionVerb

    data class SetSystemPrompt(
        val sessionId: String?,
        /**
         * Verbatim new value of `Session.systemPromptOverride`: non-null sets
         * the override, null clears it. Empty string is a legitimate
         * "run with no system prompt" override and is NOT conflated with null.
         */
        val systemPromptOverride: String?,
    ) : SessionVerb

    data class ExportBusTrace(
        val sessionId: String?,
        val format: String?,
        val limit: Int?,
    ) : SessionVerb

    data class SetToolEnabled(
        val sessionId: String?,
        val toolId: String,
        val enabled: Boolean,
    ) : SessionVerb

    data class SetSpendCap(
        val sessionId: String?,
        /** null = clear (no budget gating); ≥ 0 = cap in cents; negative = reject loud. */
        val capCents: Long?,
    ) : SessionVerb

    data class Fork(
        val sessionId: String?,
        val newTitle: String?,
        val anchorMessageId: String?,
    ) : SessionVerb

    data class Export(
        val sessionId: String?,
        val format: String?,
        val prettyPrint: Boolean,
    ) : SessionVerb

    data class Revert(
        val sessionId: String?,
        val anchorMessageId: String,
        val projectId: String,
    ) : SessionVerb

    data class Compact(
        val sessionId: String?,
        val strategy: String?,
    ) : SessionVerb
}

/**
 * Decode the flat [SessionActionTool.Input] into the typed sealed
 * verb. Required-but-missing fields fail loud here so the eventual
 * dispatcher rewrite (phase 1b) sees the same `IllegalArgumentException`
 * shape it would have thrown from the handler bodies — no behaviour
 * change in fail-loud paths.
 *
 * Unknown `action` strings fall through to [error] with the same
 * accepted-list message the dispatcher emits today. Phase 1b will
 * route through this decoder so the dispatcher's `else ->` branch
 * goes away.
 */
@Suppress("CyclomaticComplexMethod")
internal fun SessionActionTool.Input.toVerb(): SessionVerb = when (action) {
    "archive" -> SessionVerb.Archive(sessionId)
    "unarchive" -> SessionVerb.Unarchive(sessionId)
    "rename" -> SessionVerb.Rename(
        sessionId = sessionId,
        newTitle = requireNotNull(newTitle) { "action=rename requires `newTitle`" },
    )
    "delete" -> SessionVerb.Delete(
        sessionId = requireNotNull(sessionId) {
            "action=delete requires explicit `sessionId` — deleting the owning session by " +
                "context binding is self-destructive while the dispatch is running"
        },
    )
    "remove_permission_rule" -> SessionVerb.RemovePermissionRule(
        sessionId = sessionId,
        permission = requireNotNull(permission) { "action=remove_permission_rule requires `permission`" },
        pattern = requireNotNull(pattern) { "action=remove_permission_rule requires `pattern`" },
    )
    "import" -> SessionVerb.Import(
        envelope = requireNotNull(envelope) { "action=import requires `envelope`" },
    )
    "set_system_prompt" -> SessionVerb.SetSystemPrompt(sessionId, systemPromptOverride)
    "export_bus_trace" -> SessionVerb.ExportBusTrace(sessionId, format, limit)
    "set_tool_enabled" -> SessionVerb.SetToolEnabled(
        sessionId = sessionId,
        toolId = requireNotNull(toolId) { "action=set_tool_enabled requires `toolId`" },
        enabled = requireNotNull(enabled) { "action=set_tool_enabled requires `enabled`" },
    )
    "set_spend_cap" -> SessionVerb.SetSpendCap(sessionId, capCents)
    "fork" -> SessionVerb.Fork(sessionId, newTitle, anchorMessageId)
    "export" -> SessionVerb.Export(sessionId, format, prettyPrint)
    "revert" -> SessionVerb.Revert(
        sessionId = sessionId,
        anchorMessageId = requireNotNull(anchorMessageId) { "action=revert requires `anchorMessageId`" },
        projectId = requireNotNull(projectId) { "action=revert requires `projectId`" },
    )
    "compact" -> SessionVerb.Compact(sessionId, strategy)
    else -> error(
        "unknown action '$action'; accepted: archive, unarchive, rename, delete, " +
            "remove_permission_rule, import, set_system_prompt, export_bus_trace, " +
            "set_tool_enabled, set_spend_cap, fork, export, revert, compact",
    )
}
