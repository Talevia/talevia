## 2026-04-19 — Project-level named snapshots (VISION §3.4 — "可版本化")

**Context.** `revert_timeline` already lets the user undo the last few mutations
*within a chat session* — every mutating tool emits a `Part.TimelineSnapshot`, the
revert tool restores it. That covers "oops, undo that". It does **not** cover
"keep a copy of the project as-of today's review so I can return to it next week"
— those snapshots die when the session ends. VISION §3.4 explicitly asks for
"可版本化：历史可追溯、可回滚、可分支", and a session-scoped lane is not it.

**Decision.** Add a third snapshot lane: project-level, named, persistent.
- New domain type `ProjectSnapshot(id, label, capturedAtEpochMs, project)` stored
  inline as `Project.snapshots: List<ProjectSnapshot> = emptyList()`.
- Three new tools under `core/tool/builtin/project/`:
  - `save_project_snapshot` (permission `project.write`)
  - `list_project_snapshots` (permission `project.read`)
  - `restore_project_snapshot` (permission `project.destructive` → user is asked)

**Why inline storage instead of a separate `project_snapshots` SQL table?**
- `ProjectStore.mutate(...)` already gives us atomicity under a mutex — putting
  snapshots in a sibling table would mean a second store + a second lock + a
  cross-store consistency story. Inline keeps save-and-restore mechanically
  identical to every other Project mutation.
- The Project JSON blob is already what we serialize; inline adds zero schema
  migration on JVM, iOS, and Android.
- The cost — JSON blob grows linearly with snapshot count — is fine for v0
  (target: <100 snapshots per project). When a real user blows that envelope
  we'll add eviction or migrate to a sub-table. We are not going to design for
  hypothetical thousand-snapshot projects today.

**Why restore preserves the snapshots list itself.** Without this rule, restoring
to v3 would delete v1, v2, and any post-v3 snapshots — restore becomes a one-way
trapdoor and "可版本化" stops meaning anything. With it, restore behaves like
`git checkout <snapshot>`: state changes, history stays. The snapshots list +
project id are the two preserved fields; everything else (timeline, source,
lockfile, render cache, assets, output profile) is replaced wholesale from the
captured payload.

**Why save clears nested snapshots before storing.** A project with N snapshots,
captured M times, would otherwise carry O(M·N) snapshot copies inside snapshots
inside snapshots. The captured payload's own `snapshots` field is set to empty
at save time; restore re-attaches the live list. Quadratic blow-up avoided
without giving up the "restore preserves history" rule.

**Why restore is `project.destructive`, not `project.write`.** Restore overwrites
the live timeline + source + lockfile + render cache wholesale. Users can re-enter
the prior state via another snapshot, but if they hadn't saved one first, there's
nothing to roll back to. That matches the bar we already set for `delete_project`
— irreversible-from-the-user's-perspective gets ASK by default. The system prompt
tells the agent to suggest `save_project_snapshot` first when the live state
hasn't been captured.

**Why asset bytes are not snapshotted.** Snapshots reference `AssetId`s in the
shared `MediaStorage`; we do not deep-copy the underlying mp4/png blobs. This is
the same trade-off git makes vs. LFS — saving the manifest is cheap, copying
every blob would balloon storage and make snapshots a first-class media-management
concern. If a user deletes the underlying file, restore will succeed but
downstream renders may miss the asset; that's a future "snapshot integrity" tool's
problem, not a load-bearing invariant we promise here.

**Alternatives considered.**
- **Sibling `project_snapshots` SQL table.** Rejected for the reasons above —
  second store, second lock, second migration, no benefit at v0 scale.
- **Make restore non-destructive (always require explicit confirm flag).** Rejected:
  permission-system-as-confirmation is the established pattern (see
  `delete_project`); reinventing per-tool confirmation flags fragments the UX.
- **Auto-snapshot on every mutation (silent versioning).** Rejected: that's what
  `revert_timeline` already does within a session. Project-level snapshots are
  *named, intentional* checkpoints — silent auto-snapshots would dilute the lane
  into noise within weeks.
- **Reuse `revert_timeline`'s session-scoped snapshots and just persist them to
  the Project on session end.** Rejected: timeline-only snapshots miss the
  source DAG, lockfile, and render cache, all of which need to round-trip for
  "go back to the state I had on Tuesday" to mean what the user thinks it means.

---
