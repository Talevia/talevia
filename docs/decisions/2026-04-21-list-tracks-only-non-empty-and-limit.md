## 2026-04-21 — list_tracks gains onlyNonEmpty + limit (VISION §5.4 Agent / UX — timeline orientation)

Commit: `781dee7`

**Context.** `list_tracks` is the agent's track-skeleton orientation tool:
one row per track with kind, clip count, time span, and stacking index —
the signal the agent uses to plan PiP layering, multi-stem audio mixes
and localised subtitle variants without paging through every clip. The
shape was good, but two frictions kept showing up in real orientation
reads:

1. Projects accumulate scaffold tracks from `add_track` calls that the
   agent later abandons or prepares for future use. Those empty tracks
   clutter the read ("why are there 3 subtitle tracks? oh, two were
   never populated"). The agent wanted a "show me the tracks that have
   content" filter for the common case.
2. A pathological project with many scaffolded / per-language subtitle
   / per-stem audio tracks could blow past a reasonable orientation
   budget. The other list tools (`list_lockfile_entries`,
   `list_timeline_clips`, `list_assets`, `list_projects`, …) all already
   carry a silent-clamp `limit` for exactly this reason.

Mirrors the single-field filter pattern of
`list_lockfile_entries.onlyPinned` (2026-04-21) and
`list_timeline_clips.onlySourceBound` (2026-04-21), plus the
limit+`coerceIn` pattern from several recently-landed tools.

**Decision.** Two additive input fields on `ListTracksTool.Input`:

- `onlyNonEmpty: Boolean? = null` — when `true`, drop tracks whose
  `clips` list is empty *before* they are mapped to `TrackInfo`. `null`
  or `false` preserves today's behaviour. Composes with `trackKind`:
  kind filter applies first, then empty-skip.
- `limit: Int? = null` — default `100`, silently `coerceIn(1, 500)`.
  No exception on overflow (matches the house pattern) so the LLM can
  pass any integer and get a sensible result.

`totalTrackCount` keeps meaning **pre-filter** track count
(`allTracks.size`) — same as today — so the agent can compare returned
rows against the true project size and notice when filters or the cap
hid rows. `returnedTrackCount` is the capped final size. The
`outputForLlm` summary grows a scope suffix ("… N of M track(s),
kind=audio, non-empty") mirroring
`list_lockfile_entries`'s `scopeParts` composition so the LLM
understands what was applied without re-reading the input.

**Alternatives considered.**

1. *Separate `list_non_empty_tracks` tool.* Rejected — filter
   composition beats tool fanout. The existing
   `onlyPinned` / `onlySourceBound` pattern is already the agreed
   precedent for this kind of "adjective-on-a-list-tool" scope, and a
   separate tool would duplicate `projectId` / `trackKind` / `limit` /
   schema / ordering logic while making kind+non-empty composition
   awkward ("call this OR that, then filter client-side"). Boolean on
   the base tool scales to future discriminators without tool-set
   explosion.
2. *Full predicate `minClipCount: Int?` instead of a boolean.*
   Rejected — boolean covers the 95% case ("has any clip yes/no"), and
   a min-count threshold is premature optionality until a real flow
   asks for "tracks with at least 3 clips". When that flow exists we
   can add `minClipCount` alongside `onlyNonEmpty` without breaking
   callers (or deprecate `onlyNonEmpty` in favour of a `minClipCount`
   that defaults to 0). Until then the two-field surface stays narrow.
3. *Tri-state enum (`clipState: "any" | "empty" | "non-empty"`).*
   Rejected for YAGNI — no current flow asks for "only the empty
   scaffold tracks". If it shows up (e.g. a janitor/GC flow) we can
   widen `Boolean?` to a string-discriminated enum later; existing
   `null` / `false` callers collapse cleanly to the `"any"` branch.
4. *Throw on out-of-range `limit` instead of silent clamp.* Rejected —
   inconsistent with the established `coerceIn` house pattern. The
   schema description advertises the `[1, 500]` bound so a well-formed
   caller hits it immediately; loud exceptions here would surface as
   noisy agent retries without adding safety.

**Coverage.** `ListTracksToolTest` grows six new cases and preserves
every existing test:

- `onlyNonEmptyTrueSkipsEmptyTracks` — 2 empty + 2 populated;
  `onlyNonEmpty=true` returns the 2 populated with
  `totalTrackCount=4, returnedTrackCount=2`.
- `onlyNonEmptyFalseIsSameAsDefault` — same fixture; explicit `false`
  returns identical rows to the omitted-field default.
- `onlyNonEmptyComposesWithKindFilter` — 1 empty + 1 populated video,
  1 empty + 1 populated audio; `trackKind="video", onlyNonEmpty=true`
  yields the single populated video track, proving orthogonal
  composition.
- `limitCapsResponse` — 5 populated tracks; `limit=2` returns 2,
  `totalTrackCount=5`.
- `limitClampedToMax` — `limit=999_999` clamps silently to 500,
  returns all 5 seeded (no exception).
- `limitWithZeroIsClampedToMin` — `limit=0` clamps silently to 1,
  returns exactly one track. Exercises the lower `coerceIn` bound.

**Registration.** No-op — `list_tracks` is already wired in every
`AppContainer` (`CliContainer`, `apps/desktop/AppContainer`,
`apps/server/ServerContainer`, `apps/android/AndroidAppContainer`,
`apps/ios/Talevia/Platform/AppContainer.swift`). This is a pure
additive input surface extension.
