## 2026-04-19 — `fork_project` closes VISION §3.4 "可分支"

**Context.** Snapshots gave us "可追溯" (history) and "可回滚" (rollback). The
third leg of §3.4 — "可分支" (branching) — was still missing. Users who want to
explore "what if I cut this differently" without losing the original have to
either (a) save a snapshot and overwrite, or (b) export and re-import via JSON
gymnastics. Neither is the right primitive.

**Decision.** Add a `fork_project` tool (permission `project.write`) that creates
a new project from either:
- the source project's *current* state (no `snapshotId` argument), or
- a captured snapshot (`snapshotId` argument).

The new project gets a fresh id and an empty `snapshots` list. Everything else
(timeline, source DAG, lockfile, render cache, asset catalog ids, output
profile) is inherited from the chosen payload via a single `copy(id=…, snapshots=…)`.

**Why share asset bytes between source and fork instead of duplicating.** Same
trade-off as snapshots themselves — `MediaStorage` is content-addressed enough
that duplicating blobs would just consume disk for no benefit. The canonical
mutation pattern is "produce a *new* asset and `replace_clip`", so concurrent
forks won't step on each other's bytes. If we ever introduce in-place asset
mutation we'll need refcounting, but we are not going to design for that today.

**Why fork starts with an empty snapshots list.** A fork is a fresh trunk — its
own history starts at the moment of the fork. Carrying the source project's
snapshots into the fork would muddle the user's mental model ("if I restore
'final cut v1' on the fork, do I get the source-project state or the fork
state?"). Cleaner to make forks distinct timelines from the start; if the user
wants to cherry-pick a snapshot from the source project they can fork *from*
that snapshot.

**Why fork doesn't ASK like restore does.** Forks are non-destructive — the
source project is untouched. Permission `project.write` matches `create_project`
because the operation is a create. The user only sees a permission prompt if
they've tightened the rules.

**Alternatives considered.**
- **Auto-fork on every save_project_snapshot (CoW-style).** Rejected — silent
  proliferation of projects would clutter `list_projects` and break the user's
  intuition that a snapshot is a *checkpoint within a project*, not a sibling
  project. Forks should be intentional.
- **Cross-project snapshot sharing (one snapshot belongs to many projects).**
  Rejected — the inline-snapshot decision (see prior entry) already chose
  per-project storage. Cross-project sharing would require a sibling table and
  a refcount story for snapshot lifetime; unnecessary at v0.
- **`copy_project` instead of `fork_project`.** Rejected — "fork" maps cleanly
  onto the git mental model and pairs with snapshot vocabulary; "copy" suggests
  a one-shot shallow duplication with no semantic relationship to the source.
