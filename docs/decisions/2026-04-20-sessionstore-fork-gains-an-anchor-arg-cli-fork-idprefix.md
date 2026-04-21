## 2026-04-20 — `SessionStore.fork` gains an anchor arg + CLI `/fork [idPrefix]`

**Context.** The revert primitive shipped earlier today destroys
history in-place. The non-destructive twin — branch this
conversation from an earlier point, keep the original — was missing.
`SessionStore.fork` already deep-copies a whole session, which covers
the "save-before-I-experiment" case but not "rewind-and-try-a-
different-direction without losing what we had".

**Decision.**
- `SessionStore.fork` grows an optional `anchorMessageId: MessageId?`
  parameter. `null` keeps the existing "copy everything" behaviour.
  Non-null: copy only messages at-or-before the anchor in
  `(createdAt, id)` order (the same ordering `deleteMessagesAfter`
  uses). Throws `IllegalArgumentException` if the anchor isn't in
  the parent session — same shape as `SessionRevert.revertToMessage`'s
  contract.
- New CLI slash command `/fork [<messageId-prefix>]`:
  - no arg → full-history fork (equivalent to existing behaviour).
  - prefix → truncate at that anchor.
  - On success, the REPL **switches into the new branch** (same
    affordance as `/resume`), because the mental model after
    `/fork` is "now keep editing in the branch I just made". Users
    who want to stay in the parent can `/resume <parent-prefix>`
    back.
- Parent session and project state are both left untouched — fork
  is purely additive. Project timeline is *not* restored to the
  anchor's snapshot. Rationale:
  - Sessions and projects are N:1 — a project can have multiple
    sessions. "Fork session" implying "fork project" or "mutate
    project" would introduce surprising cross-talk between
    branches sharing a project.
  - We already have `fork_project` tool + project snapshots for
    explicit project branching. Users who want "branch a
    timeline-aware conversation" compose the two:
    `save_project_snapshot` → `/fork` → (optionally later)
    `restore_project_snapshot` in the branch.
- **Not shipped**: an `anchorPartId` parameter. OpenCode's revert
  supports partID precision (revert mid-message, dropping later
  parts of the same assistant turn). Our Part model has
  `TimelineSnapshot` parts between tool calls which makes this
  tempting for "fork before the bad tool call", but: (a) the UX of
  typing a part-id prefix is worse than choosing a message-id
  prefix from `/history`, and (b) revert already doesn't support
  it, so fork doing so unilaterally would feel asymmetric. Revisit
  if a clear driver emerges.

**Alternatives considered.**
- **Do nothing; tell users to `save_project_snapshot` before
  experiments.** That saves project state, not conversation
  state, so "save" still costs the chat history if the agent goes
  off the rails. Fork preserves both.
- **Implement fork as "revert on a copy"** (copy the whole
  session, then run revert on the new id). Simpler to write but
  ~2× the IO for large sessions, since we'd insert every message
  only to immediately delete the tail. The current implementation
  only copies up to the anchor.
- **`/branch` instead of `/fork`.** Closer to git terminology but
  OpenCode + the existing `fork_project` tool both already use
  "fork" — staying consistent keeps one mental model.

**Why this matches VISION.**
- §3.4 "可版本化 · 可分支": paired with project snapshots, fork
  gives every collaborator (user + agent) a way to experiment
  without losing the good state they already reached — which is
  the whole point of the "codebase" framing.

**Files touched.**
- `core/src/commonMain/.../session/SessionStore.kt` (interface +
  doc).
- `core/src/commonMain/.../session/SqlDelightSessionStore.kt`
  (optional-anchor truncation; existing call sites still work
  via default arg).
- `apps/cli/src/main/.../repl/SlashCommands.kt` + `Repl.kt`
  (`/fork` handler, switches session on success, ambiguous /
  not-found cases print candidates).
- `core/src/jvmTest/.../session/SessionForkAnchorTest.kt` (new —
  3 cases: anchor truncates correctly, no-anchor preserves old
  behaviour, foreign anchor throws).

---
