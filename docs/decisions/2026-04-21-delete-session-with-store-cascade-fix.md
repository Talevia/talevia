## 2026-04-21 — delete_session + store cascade fix (VISION §5.4 专家路径)

Commit: `8002f8e`

**Context.** The session mutation surface after prior cycles: fork,
rename, revert, archive/unarchive. The remaining hole was the
destructive verb — "permanently delete this session." Starting the
cycle turned up a latent data-integrity bug in the store: SQLite's
`foreign_keys` pragma is off in our driver setup (the existing
`deleteMessage` already cascades parts manually because of this), but
`deleteSession` was the one primitive that *didn't* cascade. Every
prior `deleteSession` call orphaned every message + part on the target
session — rows that no query could ever surface again, slowly bloating
the DB file.

Two-part fix in one cycle:

**1. Store cascade.** Add `deleteBySession` to Parts.sq (Messages.sq
already had one). Update `SqlDelightSessionStore.deleteSession` to run
`partsQueries.deleteBySession` → `messagesQueries.deleteBySession` →
`sessionsQueries.delete` → publish `SessionDeleted`. Ordering matters:
if SQLite's `foreign_keys` ever gets flipped on in production, the
reverse order would hit constraint errors; parts-first leaves children
detached before their parent disappears.

**2. Tool layer.** `DeleteSessionTool(sessionId)` as a thin adapter.
Takes a snapshot of the session metadata before the delete so the
Output reports what was removed. Missing id fails loud with a
`list_sessions` hint. No un-delete lane — help text says
"IRREVERSIBLE" and points at `archive_session` for the reversible
alternative.

New `session.destructive` permission keyword (default ASK), matching
the `project.write` / `project.destructive` split already in
`DefaultPermissionRuleset`. Operators that want "every destructive
action always asks" get uniform treatment.

**Alternatives considered.**

1. *Flip SQLite's `foreign_keys` pragma on at driver construction so
   the ON DELETE CASCADE in the schema takes over.* Rejected for this
   cycle — it would change behavior across every existing delete path
   (including the manually-cascading deleteMessage), and the
   correctness risk of silently switching on FK enforcement in a
   running system is higher than the narrow fix here. Worth revisiting
   as a dedicated cycle with its own regression sweep.
2. *Require `confirm: Boolean = true` on the tool input.* Rejected —
   that's what the `session.destructive` permission prompt is for
   (default ASK surfaces the dialog). Adding a `confirm` flag on top
   is double-gating and doesn't add safety the permission doesn't
   already provide (OpenCode's `delete-session` also leans on
   permissions, not an input flag).
3. *Bundle with `list_sessions.includeArchived=true` / archived-session
   SQL to close the archive round-trip.* Deferred — that's the runner-
   up for the next cycle, scope for a separate changeset. This one
   strictly deals with delete + its store bug.

**Coverage.** `DeleteSessionToolTest` — four tests: delete session
cascades messages (fails before the store fix, passes after); missing
id fails loud; deleting an archived session reports `archived=true`;
other sessions survive a targeted delete.

**Registration.** `DeleteSessionTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. New rule
`session.destructive=ASK` added to `DefaultPermissionRuleset`.
Also: new `deleteBySession` query in `Parts.sq`.
