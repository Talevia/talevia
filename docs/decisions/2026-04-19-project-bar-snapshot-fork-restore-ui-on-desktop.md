## 2026-04-19 — Project bar + snapshot / fork / restore UI on desktop (VISION §3.4)

**Context.** With persistence landed (Task 1), the desktop app was still
minting a fresh random project id on every launch — so "可版本化 / 可分支"
(VISION §3.4) was theoretically there but un-exerciseable without typing
tool calls into chat. Task 5 of the current gap list.

**Decision.**
- New `ProjectBar` composable at the top of the window:
  - Shows the active project's title + id prefix + snapshot count.
  - `Actions ▾` dropdown: New / Fork / Save snapshot… / Switch project… /
    Delete current.
  - `Save snapshot…` opens a dialog with a label input + a list of the
    project's existing snapshots (each with a `Restore` button).
  - `Switch project…` opens a dialog listing every project the store
    knows about, with a `Switch` button per row; the active one shows a
    "• " bullet and an `Active` disabled button.
- **`projectId` is now mutable state in `AppRoot`.** `ProjectBar`'s
  `onProjectChange(ProjectId)` callback flips it; every downstream panel
  (`SourcePanel`, `TimelinePanel`, `ChatPanel`) already keyed refresh
  effects on `projectId`, so switching projects re-keys the side effects
  and the whole workbench re-renders against the new project.
- **Boot picks the most-recently-updated persisted project.** With
  persistent SQLite (Task 1), a returning user lands back on their
  last project on launch instead of a fresh random one. If no project
  exists we bootstrap one just like before. A one-shot "Loading…"
  state blocks the rest of the UI until the bootstrap `LaunchedEffect`
  finishes, so we never render panels against an empty sentinel id.
- **All lifecycle goes through the existing tools
  (`create_project` / `fork_project` / `save_project_snapshot` /
  `restore_project_snapshot` / `delete_project`).** The bar is a UI onto
  the registry, not a second mutation path.

**Alternatives considered.**
- **Sidebar of all projects (always visible), no switch-dialog.** Nicer
  when you have many projects, but eats horizontal space we don't have
  — the workbench is already three columns + a right-column tab
  strip. Keeping the project list behind a dialog trade screen space
  for one extra click.
- **`diff_projects` in the bar.** The tool exists; surfacing it needs
  a two-picker dialog + a diff renderer that deserves its own panel.
  Out of scope; fold into the same follow-up as "diff viewer".
- **Track active project id in the DB (last-opened row) instead of
  recomputing `maxByOrNull { updatedAtEpochMs }`.** The recompute is
  O(#projects) on every launch and reads an already-indexed summary
  list; adding a "last opened" column for the same result is
  premature. Revisit when the heuristic proves wrong (it won't for a
  long time).

**Known limitations.**
- Delete has no confirmation dialog. Current guard: the menu item is
  disabled when there's exactly one project (can't delete your only
  project), and the deletion logs to the activity pane. A confirm
  prompt + an Undo toast is the obvious follow-up.
- The Snapshots dialog shows epoch-ms rather than a formatted
  timestamp. Readable enough for expert users; pretty-time formatting
  is a v1 nicety.
- Switch dialog has no search — fine at tens of projects, painful at
  hundreds. Same bucket as the Source panel filter follow-up.

**Follow-ups.**
- `diff_projects` viewer (side-by-side JSON diff or a summary table).
- Delete confirmation + Undo affordance.
- Formatted timestamps (localised) across the Project + Snapshot UI.
- Last-opened persistence (if launch-time project-pick proves wrong).

---
