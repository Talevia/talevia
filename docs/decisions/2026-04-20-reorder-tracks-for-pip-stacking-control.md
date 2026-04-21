## 2026-04-20 — `reorder_tracks` for PiP stacking control

**Context.** `add_track` + `list_tracks` together unlocked
multi-track layouts this round. But [Timeline.tracks] is an
ordered `List` and the engines composite in that order —
first = bottom, later = top. `add_track` always appends at the
tail. If the agent creates a background video track first,
then a foreground PiP track, then later realises it should
actually composite the fg over a third mid-layer, it has no
tool to fix the stack. The only workarounds:

- `remove_track` both, re-add in the new order — destroys clips.
- `clear_timeline` + rebuild — destroys everything.

Neither is acceptable. Every other list-order property we've
exposed (track contents, transitions-on-effect, lockfile
entries) can be queried + mutated; the track list itself was
the blind spot.

**Decision.** Ship `reorder_tracks(projectId, trackIds[])`:

1. **Partial ordering** is the contract: listed ids move to
   the front in the given order; unlisted tracks keep their
   current relative positions at the tail. Covers the common
   "pin this one to the top" case without forcing the agent
   to enumerate every other id.
2. Duplicates in `trackIds` → loud error (typo guard).
3. Unknown id → loud error (same).
4. Empty list → loud error: "nothing to reorder, omit the
   call entirely if the order is correct" — an empty call is
   almost always a mistake.
5. **No clip movement** — clips inside each track stay put.
   This tool only reshapes the enclosing list.
6. Emits `Part.TimelineSnapshot` so `revert_timeline` can
   undo the reorder.
7. Permission: `timeline.write`, same tier as `add_track`.

**Alternatives considered.**

- *Full ordering required.* Rejected — forcing the agent to
  pass all ids to pin one is busywork. Tail-preservation is
  cheaper with no loss of expressiveness (the full-list case
  still works).
- *Index-based API (`move_track(trackId, newIndex)`).*
  Rejected — pinning multiple ids at once would need multiple
  calls, and inter-call indices shift after each mutation,
  which is exactly the kind of contract the agent struggles
  with. List-reorder expresses the final state directly.
- *Auto-sort by kind.* Rejected — opinionated ordering
  (e.g. "video at bottom, subtitles at top") is rendered
  obsolete the moment a multi-video PiP layout appears.
  Let the caller decide.

**Coverage.** 10 JVM tests:
- Listed track moves to front, rest keep order.
- Full reorder works (all ids listed).
- Tail relative-order preserved when only one id pinned.
- Clip contents are untouched after reorder.
- Emits one snapshot, timeline in snapshot reflects new order.
- Rejects empty list.
- Rejects duplicate ids.
- Rejects unknown track id.
- Rejects missing project.
- Output echoes final order.

**Registration.** Registered in all 5 composition roots
directly after `RemoveTrackTool` — track lifecycle verbs
(add / duplicate / remove / reorder) clustered together.

**SHA.** 232a72e4769c15c4f23746a19a3ef1b54c82cfee

---
