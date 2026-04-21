## 2026-04-21 — rename_session agent retitling (VISION §5.4 专家路径)

Commit: `d1bc0c2`

**Context.** `SessionTitler` auto-assigns a title when the session
receives its first user turn (derived from the prompt via an LLM call).
The user frequently wants to retitle later once the session's real focus
becomes clear: "call this 'Mei's story arc' now that we've settled
that." The CLI has a `/rename` slash command, but the agent couldn't
fire the verb itself — the user had to drop out of the chat, rename,
then resume.

With `fork_session` in place (prior cycle, `1b2416b`), rename was the
smallest remaining write-verb on the session lane. Completes the basic
write surface: create (via Agent.run seeding), fork, rename.

**Decision.** `RenameSessionTool(sessionId, newTitle)`:
- Reads the session, copies with new `title` + refreshed `updatedAt`,
  writes via `SessionStore.updateSession` (which publishes
  `BusEvent.SessionUpdated` so UI consumers refresh).
- Rejects blank `newTitle` — SessionTitler would immediately reset a
  blank title on the next user turn, and silently "succeeding" would
  confuse the agent.
- Same-title call is a no-op: no store write, no `updatedAt` bump,
  no event. Matches the "idempotent describe/update" convention used
  by `pin_lockfile_entry` / `set_source_node_parents` — the Output
  still reports the prior title so the agent can log what it intended.
- Missing session fails loud with a `list_sessions` hint, same error
  shape as `describe_session` / `fork_session`.
- Clock is injected (defaults to `Clock.System`) for deterministic tests,
  matching `GcLockfileTool` / `SaveProjectSnapshotTool`.

Permission: reuses the `session.write` keyword introduced for fork.

**Alternatives considered.**

1. *Let `SessionTitler` handle rename by re-running titling on user
   request.* Rejected — SessionTitler derives from the first-prompt
   content and would produce a new machine-generated title, not the
   user's chosen phrase. A user who says "rename this to 'Mei's story
   arc'" expects that exact string; anything else is surprise behaviour.
   The primitive is `updateSession`, not `regenerateTitle`.
2. *Return the timestamp of the rename as part of Output.* Rejected for
   now as scope creep — the Bus event carries the information for UIs
   that care, and the agent already has the value via a follow-up
   `describe_session` call. YAGNI.
3. *Use `permission = "session.rename"` as a third-tier keyword.*
   Rejected — the risk profile is identical to fork (local state,
   bounded blast radius) and carving a third permission for every
   verb would balloon the rule count. The `read`/`write` split is the
   industry-consensus granularity (S3 IAM, Postgres role privileges,
   Linux rwx).

**Coverage.** `RenameSessionToolTest` — four tests: rename succeeds and
bumps `updatedAt`; same-title no-op leaves `updatedAt` alone; blank
title rejected; missing session fails loud with hint.

**Registration.** `RenameSessionTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission.
