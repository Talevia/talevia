## 2026-04-20 — `list_tracks` for track-level layout introspection

**Context.** `list_timeline_clips` already covers clip-level
introspection and `list_assets` covers the asset catalog, but
there was no cheap way to ask "what tracks do I have, and in
what stacking order?". Three recurring agent flows hit this
gap:

- **PiP layering reasoning.** After `add_track` creates a
  second `video` track, the agent needs to confirm which
  track ends up on top. `get_project_state` returns the full
  project JSON (timeline + assets + lockfile + snapshots +
  source graph) just to read `timeline.tracks.size`.
- **Multi-stem audio mixes.** "Lower the music track but keep
  dialogue loud" — the agent needs each track's id + clip
  count to dispatch `set_clip_volume` per stem.
- **Localised subtitle variants.** Confirming whether a
  dedicated EN / ES / JA track exists before calling
  `add_subtitle` with the right `trackId`.

**Decision.** Ship `list_tracks(projectId, trackKind?)`:

1. Returns one row per track in `Timeline.tracks` order (=
   engine stacking order: first is drawn on bottom).
2. Each row: `trackId`, `trackKind`, `index` (0-based stack
   position), `clipCount`, `isEmpty`, and for non-empty
   tracks `firstClipStartSeconds` / `lastClipEndSeconds` /
   `spanSeconds`.
3. `spanSeconds = lastEnd - firstStart` — wall-clock span of
   clip coverage on this track, not the sum of clip
   durations. The gap-vs-packed distinction is what the
   agent actually reasons about.
4. Optional `trackKind` filter (video / audio / subtitle /
   effect), case-insensitive, rejects typos loud. Mirrors
   `list_timeline_clips`'s validation.
5. `project.read` permission — read-only, no snapshot.

**Alternatives considered.**

- *Extend `list_timeline_clips` with a `groupByTrack` flag.*
  Rejected — the agent frequently wants tracks **without**
  clips (just imported the layout, haven't populated yet),
  and conflating clip introspection with layout
  introspection muddles the tool surface.
- *Return `sumOfDurations` instead of `span`.* Either is
  derivable from `list_timeline_clips`, but span is the
  unique value-add over that tool — how packed vs. how
  total.
- *Emit structural track-order info as part of
  `describe_project`.* That tool targets top-level
  orientation ("what kind of project is this, how big");
  track-level layout doesn't belong there.

**Coverage.** 10 JVM tests:
- Every track returned in stacking order.
- Kinds classified (video / audio / subtitle / effect).
- Clip counts accurate.
- Empty track sets `isEmpty=true` + null span.
- Span covers gaps between clips (0..5 + 6..10 → span 10,
  not 9).
- `trackKind=video` filters down to two PiP tracks.
- Filter is case-insensitive + whitespace-tolerant.
- Rejects unknown kind.
- Rejects missing project.
- Index preserves stacking order (PiP bg=0, fg=1).

**Registration.** Registered in all 5 composition roots
directly after `list_timeline_clips`, before
`list_clips_bound_to_asset` / `list_assets` — the read
cluster grows in layout → clip → asset order.

**SHA.** 302b9e5312729060da7db6737eaaacf0bfc4c3af

---
