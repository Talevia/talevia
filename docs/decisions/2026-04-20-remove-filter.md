## 2026-04-20 — `remove_filter` (ApplyFilter counterpart)

**Context.** `ApplyFilterTool` ships with an explicit note in its
kdoc: "remove and replace are out of scope for v0 (a future tool can
take an index)." Until today that note was still true — the only way
to undo an applied filter was `revert_timeline`, which rewinds the
*entire* timeline to the last snapshot. That means filter iteration
("try blur, hate it, try vignette instead") forces the agent to
either (a) throw away every subsequent edit or (b) start the turn
over from scratch. Both are terrible.

**Decision.** Ship a `remove_filter` tool keyed on filter **name**:

```
remove_filter(projectId, clipId, filterName)
→ { clipId, removedCount, remainingFilterCount }
```

Removes every Filter on the target clip whose `name == filterName`.
Video clips only (filters live only on `Clip.Video`). Emits a
timeline snapshot so the removal itself is revertable. Idempotent
when the filter isn't present (`removedCount: 0`, no error) so an
agent doing speculative cleanup ("drop the blur if it's there") can
issue the call without a probe first.

**Alternatives considered.**

- *Key on an index instead of a name.* Rejected — the agent doesn't
  naturally know filter indices. It applied the filter by name and
  will want to remove it by name. The ApplyFilter kdoc floated index
  as a possibility, but in practice name is what the agent has
  access to from `list_timeline_clips` output (filter names are
  displayed; positions aren't stable as other filters are added).

- *Remove only the first matching filter; require explicit "all"
  flag.* Rejected — duplicates are rare, and when they exist it's
  almost always because the agent applied `blur` twice and now wants
  *the blur* gone. Removing all by default matches user intent 95%
  of the time. If the agent really wants to keep one of two `blur`
  filters, the right answer is a future indexed-remove or filter-ids
  on the Filter data class, not an extra flag here.

- *Fold into a unified `update_filters(clipId, filters)` that
  replaces the whole list.* Rejected — forces the agent to round-trip
  the current list (via `list_timeline_clips` or similar) for every
  remove. The single-purpose `remove_filter` is the minimal surface
  that fixes the real problem.

- *Ship `clear_filters(clipId)` instead/also.* Deferred — current
  pattern is "apply one, maybe undo one"; wholesale wipes haven't
  shown up yet. Trivial to add later if needed (one more tool, same
  shape).

**Reasoning.** Closing this asymmetry is a small-code, big-behavior
win: ~130 lines + 7 tests, but it unblocks iterative filter tuning
which was previously forcing a full timeline rewind per undo. The
idempotent semantics mirror how the AIGC tools handle missing
consistency bindings — cheap no-op instead of a stacktrace — which
keeps agent turn plans shorter.

---
