## 2026-04-20 — `SessionRevert` service + CLI `/revert` (hard revert; OpenCode parity)

**Context.** A chat-driven editor needs a cheap undo for agent turns —
without it, the only way to recover from "I didn't mean that" after a
mutating tool call is to start a new session, losing all the context
already built up (source nodes, character refs, the flow of iteration).
OpenCode ships `session/revert.ts` for this. We already had the
infrastructure half-done in an uncommitted WIP state:
`SessionStore.deleteMessage` / `deleteMessagesAfter` with matching
`BusEvent.MessageDeleted`. The service that consumes them didn't
exist.

**Decision.**
- New `SessionRevert` (`core/session/SessionRevert.kt`,
  commonMain) with a single public entry point
  `revertToMessage(sessionId, anchorMessageId, projectId): Result`.
  Behavior:
  1. Delete every message strictly after the anchor (via existing
     `deleteMessagesAfter`), which cascades through
     `Parts.deleteByMessage`.
  2. Walk `listSessionParts(includeCompacted = true)` filtered to
     messages at-or-before the anchor, take the last
     `Part.TimelineSnapshot` encountered, and restore it into the
     project via `ProjectStore.mutate`. No snapshot found → timeline
     untouched.
  3. Publish a single `BusEvent.SessionReverted` carrying the count
     and the applied-snapshot part id (null if none).
- Semantics: **anchor is kept**, everything strictly after is dropped.
  So `/revert <user message>` drops that user's assistant reply and
  all later turns — use `/revert <assistant message>` to keep the
  reply it produced (and its timeline snapshot).
- **Hard** revert, not OpenCode's soft overlay. See alternatives.
- New CLI slash commands:
  - `/history` — list every message in the session with a 12-char id
    prefix + role + first-line preview (or `(tool: id)` when the
    first part is a tool call). Same affordance as `/resume` for
    session ids.
  - `/revert <idPrefix>` — resolve prefix to a unique message in the
    current session, call `SessionRevert`, print a one-liner with
    dropped count + restored clip/track counts.
- Bus wire-up: `EventBusMetricsSink` counts `session.reverted` +
  `message.deleted`. Server SSE stream adds a `session.reverted`
  event carrying the same fields as the Kotlin event.

**Alternatives considered.**
- **Soft revert with unrevert (OpenCode's approach).** OpenCode
  keeps a diff overlay that `unrevert` can un-apply, because its
  analogous state is filesystem edits and an unapply patch has real
  value. Our analogous state is the canonical timeline, which
  already has explicit snapshot parts at every mutating tool call —
  so "drop messages + restore snapshot" is one read-modify-write
  that's easy to reason about. Unrevert on a hard revert would
  require storing another snapshot of "what the revert replaced"
  and defining edge cases (what if the user appended new messages
  after revert but before unrevert?). Not worth it on the first
  cut; we can add it later by introducing a single
  `Session.revert` metadata field mirroring OpenCode's shape.
- **Delete anchor too (strict "revert before the target").** Makes
  revert-to-user-message a round-trip-free re-edit, but breaks the
  common case of "keep this assistant answer, drop the tangent it
  spawned" because there's no way to express "keep this message".
  Current semantic preserves both; user who wants the stricter form
  can pass the parent-user id.
- **Tool surface (`revert_session` tool the agent can invoke).**
  Deliberately **not** shipped in this pass. The primary use case
  is a human typing "go back" after seeing output; letting the
  agent revert its own history opens up confusing loops (agent
  reverts to before its tool call, re-runs, loops). Revisit once
  there's a concrete driver — the service API is already shaped to
  become a tool cleanly.
- **CLI `/undo` with no anchor argument ("pop last turn").**
  Tempting for ergonomics, but "last turn" is ambiguous during a
  long agent trajectory with many assistant messages between two
  user turns. Requiring the id prefix keeps the mental model the
  same whether you're rolling back one reply or ten.
- **Concurrency guard (`assertNotBusy`, à la OpenCode's
  `SessionRunState`).** Skipped for now — the CLI already cancels
  the agent on Ctrl+C before the user can type a slash command,
  and tool-driven reverts don't exist yet. If we add them we'll
  need to check; documented in the Kotlin source as a caveat.

**Why this matches VISION.**
- §3.4 "Project / Timeline is codebase · 可版本化 · 可回滚":
  revert is the session-scoped counterpart of the project snapshot
  system we already have. Both compose — you can revert the
  session and restore a project snapshot independently.
- §5.3 rubric "增量编译": reverting to a snapshot puts the
  timeline hash back to a prior value; the export cache / stale
  guard / lockfile automatically re-validate against the reverted
  state with no extra wiring.

**Files touched.**
- `core/src/commonMain/.../session/SessionRevert.kt` (new)
- `core/src/commonMain/.../session/SessionStore.kt`
  (commit existing WIP: `deleteMessage`, `deleteMessagesAfter`)
- `core/src/commonMain/.../session/SqlDelightSessionStore.kt`
  (commit existing WIP: impls)
- `core/src/commonMain/.../bus/BusEvent.kt`
  (commit existing WIP: `MessageDeleted`; add `SessionReverted`)
- `core/src/commonMain/.../metrics/Metrics.kt`
  (counter names for both events)
- `core/src/commonMain/sqldelight/.../Messages.sq`
  (commit existing WIP: `delete` query)
- `core/src/commonMain/sqldelight/.../Parts.sq`
  (commit existing WIP: `deleteByMessage` query)
- `core/src/jvmTest/.../session/SessionRevertTest.kt` (new, 4
  cases: happy-path revert restores timeline; revert with no
  prior snapshot leaves timeline untouched; revert to latest is
  no-op; cross-session anchor + unknown anchor throw)
- `apps/cli/src/main/.../repl/SlashCommands.kt` (add `/history`,
  `/revert` to catalogue — autocompletion picks them up via
  `SlashCompletion`'s `SLASH_COMMANDS` loop)
- `apps/cli/src/main/.../repl/Repl.kt` (`historyTable`,
  `handleRevert` handlers)
- `apps/server/src/main/.../ServerModule.kt`
  (SSE event names + `BusEventDto` fields for both events)

---
