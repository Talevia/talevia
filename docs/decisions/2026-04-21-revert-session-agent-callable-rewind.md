## 2026-04-21 — revert_session agent-callable rewind (VISION §5.4 专家路径)

Commit: `551abfe`

**Context.** `SessionRevert` lives in `core/session/SessionRevert.kt` —
the hard-revert primitive that deletes every message strictly after an
anchor and rolls the project's `timeline` back to the nearest
`Part.TimelineSnapshot` at-or-before that anchor. CLI exposes it via
`/revert`; the agent itself couldn't call it. That leaves a gap in the
session-lane write surface: the prior cycles added `fork_session` and
`rename_session`, but "undo back to where we defined Mei" still
required the user to bail out of the chat and run the slash command.

**Decision.** `RevertSessionTool(sessionId, anchorMessageId, projectId)`
— thin adapter over `SessionRevert.revertToMessage` with the existing
contract preserved verbatim:

- Every message strictly after the anchor is deleted; the anchor itself
  remains.
- Timeline rolls back to the nearest snapshot at-or-before the anchor.
  When no snapshot exists (fresh session, pure-reasoning turns only),
  the timeline is left untouched and `appliedSnapshotPartId` is null.
- Publishes `BusEvent.SessionReverted` so UIs refresh.

Destructive — **no un-revert through tools**. Help text flags the
irreversibility so the agent can warn the user. `save_project_snapshot`
+ `restore_project_snapshot` exist for project-level safety; this tool
is specifically the session half.

Not cancel-safe — the primitive's kdoc calls this out. The typical
call pattern is "on the current session, after the user explicitly
asks"; the cancel is implicit because the agent is the only writer on
that session.

Reuses `session.write` permission (introduced with `fork_session`).
Registered in all five AppContainers — the iOS Swift wiring passes
the existing `bus` + `projects` dependencies.

**Alternatives considered.**

1. *Soft revert (record a diff overlay so un-revert is possible, like
   OpenCode's `session/revert.ts`).* Rejected for this cycle —
   SessionRevert's kdoc already documents the hard-revert choice and
   the reasoning (timeline snapshots cover the reversible surface we
   care about). This tool should mirror the primitive's semantics, not
   introduce a divergent abstraction.
2. *Refuse to revert when `Agent.cancel(sessionId)` hasn't been called
   first.* Considered but rejected — the primitive's kdoc explicitly
   chose "caller responsibility" over "assert idle" to keep the core
   primitive permissive (same stance as `deleteSession` / `fork`).
   Adding a strictness override at the tool layer would diverge from
   the primitive; instead the tool's help text documents the
   expectation. Follow-up: a `session.quiesce` helper or an
   Agent.isRunning check could shim this cleanly if autonomous agents
   start stepping on each other.
3. *Return the list of deleted MessageIds.* Rejected — the primitive
   returns `deletedMessages: Int`, and surfacing the ids would require
   a second read pre-mutation. `list_messages` before the revert
   already gives the agent that visibility if it wants to log.
4. *Split `anchorMessageId` between `"at or before"` and `"strictly
   before"` flavors.* Rejected — the primitive's semantics are `"strictly
   after the anchor is deleted; anchor is kept"`, i.e. the anchor itself
   survives. That's the only sensible point of reference ("rewind to
   here"); adding a strictly-before variant would complicate the tool
   surface for no concrete flow.

**Coverage.** `RevertSessionToolTest` — four tests: revert drops
subsequent messages; anchor == latest is a no-op; unknown anchor
fails loud with `IllegalArgumentException`; anchor from a different
session fails loud with "belongs to session X" message (routes through
the primitive's `require`).

**Registration.** `RevertSessionTool` registered in `CliContainer.kt`,
`apps/desktop/AppContainer.kt`, `apps/server/ServerContainer.kt`,
`apps/android/AndroidAppContainer.kt`,
`apps/ios/Talevia/Platform/AppContainer.swift`. No new permission
(reuses `session.write`).
