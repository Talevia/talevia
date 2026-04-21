## 2026-04-20 — `rename_project` for in-place title changes

**Context.** The project catalog row stores a `title` column that
`list_projects`, `get_project_state`, and summary outputs all
display, but there was no public API to update it after
`create_project`. Users who picked a working title and later wanted
"Final cut — Q2 promo" had only two paths: (a) `fork_project`
(duplicates every asset, lockfile entry, snapshot — and changes
the id, breaking session references), or (b) `delete_project` +
re-create (loses everything). Both paths are absurd responses to
"change the label."

The domain model keeps title in the DB row, not in the [Project]
JSON blob, because it's catalog metadata — not part of the
render-affecting state we snapshot. That's still the right shape,
but it means the mutation tool has to go through a different store
path than the `ProjectStore.mutate` mutex used by timeline tools.

**Decision.** Ship `rename_project(projectId, title)` backed by a
new `ProjectStore.setTitle(id, title)` operation that:

1. Acquires the same mutex `mutate` uses (no title / data race).
2. Issues a `renameProject` SQL UPDATE — only `title` and
   `time_updated` change; the JSON blob is not re-serialized.
3. Fails loud if no row exists.
4. No-ops cleanly when the requested title matches the current one
   (returns a "no change" message rather than burning a writable
   permission).

The tool's output carries `previousTitle` so the agent can phrase
"Renamed X to Y" back to the user without a separate lookup.

**Alternatives considered.**

- *Add `title` to the [Project] model and mutate it inside
  `ProjectStore.mutate`.* Would force a schema migration for a
  field that's pure catalog metadata and doesn't affect render or
  timeline semantics. Also redundant — the DB row already has the
  field, indexed for `list_projects`. Rejected.
- *Re-use `upsert(title, project)` from the tool.* Works but races
  the `mutate` lock — an in-flight timeline write could stomp the
  title, or vice versa. Rejected in favor of a dedicated
  mutex-guarded path.
- *`PATCH`-style tool that takes multiple optional metadata fields
  (title, description, tags).* No other catalog fields exist yet,
  so this is speculative. Add when a second field appears.

**Permission.** `project.write`. Not `project.destructive`: this
doesn't touch any user-authored content and is trivially
reversible (rename back).

**Coverage.** 6 JVM tests:
- Renames an existing project; summary reflects new title.
- [Project] model (timeline / assets / source) is untouched.
- No-op when title identical.
- Rejects missing project (throws).
- Rejects blank title (throws).
- Rename persists across `listSummaries` calls.

**Registration.** Registered in all 5 composition roots
(`CliContainer`, desktop `AppContainer`, `ServerContainer`,
`AndroidAppContainer`, iOS `AppContainer.swift`), positioned after
`delete_project`, before `find_stale_clips`.

**Follow-ups.** When a second catalog field appears (description,
tags, favorite flag), consider generalising to
`update_project_metadata` and deprecating `rename_project`. Don't
pre-build it.

---
