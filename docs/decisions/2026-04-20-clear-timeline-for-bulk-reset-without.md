## 2026-04-20 — `clear_timeline` for bulk reset without
`revert_timeline`

**Context.** Once a timeline has ~dozens of clips, "scrap this and
start over" is a common mid-session move — the source bible
changed, the storyboard pivoted, or the agent's first draft went
off-rail. The only paths today were:

1. Call `remove_clip` N times. Painful, pollutes the snapshot
   stack, and the agent routinely missed clips on the Effect /
   Subtitle tracks.
2. Call `revert_timeline` to a pre-edit snapshot. Requires one to
   exist; rolls back EVERY edit since, which usually throws away
   correct work too.
3. Call `delete_project` + `create_project`. Loses assets, source
   DAG, lockfile, snapshots — a sledgehammer.

All three miss the real intent: reset the *timeline content*
without touching the context the user has already built
(imported assets, source character refs, approved AIGC outputs in
the lockfile, named snapshots for "final cut v1").

**Decision.** Ship `clear_timeline(projectId, preserveTracks=true)`:

1. Walk every track, replace its clip list with `emptyList()`.
2. If `preserveTracks=false`, drop the tracks entirely.
3. Reset `Timeline.duration` to `Duration.ZERO`.
4. Leave assets, source DAG, lockfile, render cache, snapshots,
   and output profile untouched — they're not part of "the cut".
5. Emit one `Part.TimelineSnapshot` so `revert_timeline` can
   restore the pre-clear timeline in a single step.

**Permission.** `project.destructive`. Even with
`revert_timeline` available, nuking an entire user-authored
timeline is the kind of thing the user should consciously
confirm. Keeps the tool on the same tier as `delete_project` and
`delete_snapshot` (if we add it later).

**`preserveTracks` default of `true`.** Tracks carry ids the
agent may already have referenced ("I'll put the voiceover on
track audio-1") earlier in the conversation. Dropping them would
break those references on the very next `add_clip`. Most real
"start over" flows want to reuse the same track skeleton, so
that's the default. Keep the `false` option for the narrower
case where the old track shape is wrong (e.g. going from 3-track
to 1-track).

**Alternatives considered.**

- *`truncate_timeline(startSeconds, endSeconds)`* — general
  range-delete. Strictly more powerful, but requires splitting
  clips that straddle the boundaries, which needs the same
  engine-aware logic `split_clip` owns. Punt until a concrete
  driver (e.g. ripple-delete over range) appears.
- *Auto-clear as a flag on `revert_timeline`* — e.g. "revert to
  empty". Overloads a tool whose semantics are "walk the snapshot
  history"; zero is not in that history. Better as its own verb.
- *Have it also clear assets / source* — rejected. Users
  consistently want to keep imported media + source refs across
  cut-rewrites; that's the whole point of separating authoring
  state from the cut.

**Coverage.** 8 JVM tests:
- Clears all clips; tracks preserved by default.
- Drops tracks when `preserveTracks=false`.
- Non-timeline state (assets, source, lockfile, renderCache,
  snapshots, outputProfile, project id) unchanged.
- Emits exactly one timeline snapshot.
- No-op on already-empty timeline still succeeds.
- Rejects missing project (throws).
- Preserved track ids match pre-clear set.
- Cleared state persists across repeated reads.

**Registration.** Registered in all 5 composition roots
(`CliContainer`, desktop `AppContainer`, `ServerContainer`,
`AndroidAppContainer`, iOS `AppContainer.swift`) immediately
after `revert_timeline` — bulk-timeline verbs grouped together.

---
