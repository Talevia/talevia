## 2026-04-19 — Rich Timeline inspector on desktop (VISION §5.2 / §5.4 expert view)

**Context.** The old centre-panel timeline was a flat list of clip
`id-range` strings with a coloured block — no notion of tracks, no
visibility into filters / volume / transforms / subtitles / source
bindings, no stale signal. VISION §4 expert path needs direct-manipulate
access to each of those; Task 4 of the current gap list.

**Decision.**
- New `TimelinePanel.kt` composable that replaces the old flat list.
  Structure:
  - Header row: "Tracks · duration · N clips".
  - Per-track header (`[kind] <track-id-prefix> · N clips`) ordered as
    the Timeline stores them — `Video` / `Audio` / `Effect` / `Subtitle`.
  - Per-clip row: collapsed summary (kind, id prefix, time range, chips
    for `Nfx` / `xform` / `vol` / `fi` / `fo`) + stale highlight.
    Expanded: full clip JSON (via `Clip` serializer + pretty Json) plus
    `track` / `clip` / `bindings` lines, a `Remove` button that
    dispatches `remove_clip` through the shared registry.
- **Stale detection today: `Project.staleClips(allNodeIds)`.** We don't
  yet track "which source nodes changed since the last render" — so the
  initial badge flags every clip whose `sourceBinding` can go stale
  against any node in the DAG. Accepts false positives in exchange for
  a cheap, correct signal while we add a real stale-since-render ledger.
- **Dropped the synthetic `ClipRow` bag + per-click manual list
  refresh from `Main.kt`.** The new panel subscribes to
  `BusEvent.PartUpdated` like `SourcePanel` and reads the full
  `Project` — there's no parallel state to drift anymore.

**Alternatives considered.**
- **Pixel-scaled track lanes (clips rendered as sized blocks on a
  horizontal time ruler).** Closer to the Premiere / FCP visual but
  needs playhead / zoom / scrub controls to be useful, and we don't
  have a playhead concept yet (the preview panel is post-export). That
  UI is a project unto itself; the row-based inspector is the honest
  minimum viable step that exposes state we couldn't see before.
- **Filter / transform inline editing inside the inspector.** Out of
  scope this iteration. Edit round-trips through chat or a future
  per-kind dialog — we prioritised breadth (every clip kind, every
  applied-effect chip, source binding, stale badge) over depth (mutate
  each knob from a form).
- **Per-clip tools: `split_clip`, `trim_clip`, `move_clip`, …** Only
  `remove_clip` is wired so far; the rest have Tool registrations, just
  no inspector button yet. Follow-up.

**Known limitations.**
- Track-lane view is still row-based, not the graphical waveform /
  thumbnail lane most DAWs ship. The chips + stale tint carry a lot of
  what a full lane view would show; upgrade when we build live preview.
- Stale set uses "any node id changed" as the proxy — a node that has
  never changed since the last render still flags bound clips. Fix
  needs a render-lockfile delta store (post-Task-6).
- No drag-to-reorder / drag-to-trim. That's the next layer of interaction
  and wants a playhead.

**Follow-ups.**
- Per-clip inspector actions: Split / Trim / Move / Duplicate wired to
  the existing tools.
- Highlight-on-source-change: click a source node in `SourcePanel` →
  outline bound clips in `TimelinePanel`. Requires shared
  `selected-source-node` state — promote both panels into a single
  `ProjectWorkbenchState` when the second cross-panel interaction
  lands.
- Real "stale since last render" signal from the lockfile / render cache.

---
