## 2026-04-21 — archive_session / unarchive_session (VISION §5.4 专家路径)

Commit: `59d35aa`

**Context.** The session-lane write verb surface after prior cycles:
`fork_session`, `rename_session`, `revert_session`. The one remaining
common action — "put this session away but don't lose it" — was still
missing from the tool layer. `Session.archived: Boolean` has been on
the data class since M4 (the store's `selectAll` / `selectByProject` SQL
already filters `archived = 0`) but no tool flipped it.

Real flows needing this:
- User says "archive the vlog experiment session, we're done with it"
  — single verb, reversible, no data loss.
- The user has 40+ sessions and wants to declutter `list_sessions`
  output.

**Decision.** Two idempotent, symmetric tools in one file —
`ArchiveSessionTool` + `UnarchiveSessionTool`, matching the
`pin_lockfile_entry` / `unpin_lockfile_entry` shape. Both take
`(sessionId)`, both return `Output(sessionId, title, wasArchived?)`,
both mutate via `SessionStore.updateSession` on a copy with the
archived flag flipped and `updatedAt` bumped. Same-state call is a
no-op — matches the pin / rename idempotency contract.

Both reuse the `session.write` permission (introduced with
`fork_session`). Registered in all five AppContainers.

**Known asymmetry (documented, not resolved):** Archived sessions are
not returned by `list_sessions` — the store's SQL hardcodes
`archived = 0` on both `selectAll` and `selectByProject`. Once archived
via this tool, the session id becomes the only recovery handle: the
user has to hold onto it (e.g. in chat history) or have a CLI / shell
view that runs a different query. A follow-up that adds
`selectAllIncludingArchived` + a flag on `list_sessions` would close
this — but the archive/unarchive pair is useful on its own, and the
help text flags the caveat so the agent doesn't promise what doesn't
work.

**Alternatives considered.**

1. *Replace archive with `delete_session`.* Rejected — `deleteSession`
   is an existing primitive, archive is the *soft* verb. Archive
   preserves data; delete nukes it. Users want the reversible one for
   "I'm done with this for now."
2. *Add `selectAllIncludingArchived` in the same cycle so
   `list_sessions` could gain `includeArchived=true`.* Deferred —
   that's a SQL + store-interface + tool change in one cycle, and the
   archive / unarchive pair is valuable even without the listing flow
   (a user who knows the id can still reach it). Kept the scope tight;
   a dedicated cycle can flip the list_sessions flag back on.
3. *Fold archive + unarchive into a single `set_session_archived(id,
   archived: Boolean)` tool.* Rejected — two verbs mirror the existing
   `pin_lockfile_entry` / `unpin_lockfile_entry` ergonomic, and the
   LLM's mental model handles pair-of-verbs more reliably than a
   boolean flag it has to decide.
4. *Archive cascades to delete the messages (to save DB space).*
   Rejected — that's `delete_session`. Archive = soft hide, explicit
   intent.

**Coverage.** `ArchiveSessionToolTest` — eight tests: archive live
session (and assert it vanishes from `list_sessions`), archive already-
archived is idempotent, unarchive archived restores, unarchive live
is idempotent, both fail loud on missing session, archive bumps
`updatedAt`.

**Registration.** `ArchiveSessionTool` + `UnarchiveSessionTool`
registered in `CliContainer.kt`, `apps/desktop/AppContainer.kt`,
`apps/server/ServerContainer.kt`, `apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission
(reuses `session.write`).
