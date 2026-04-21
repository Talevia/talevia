## 2026-04-21 — list_transitions gains onlyOrphaned + limit (VISION §5.4 Agent / UX — GC orientation)

Commit: `33319b5`

**Context.** `list_transitions` is the agent's transition-slice
orientation tool — one row per transition with name, start/duration,
and the recovered `(fromClipId, toClipId)` pair, plus an `orphaned`
boolean when both flanking video clips were removed and the transition
now renders nothing. That shape was already the right shape, but two
real-world GC / orientation frictions showed up:

1. The natural agent question is "which fades can I GC?" — exactly the
   `orphaned == true` subset. Today the agent has to page the full
   list and scan each row's `orphaned` field client-side. Every other
   recent list tool exposes a positive boolean for the interesting
   subset (`list_lockfile_entries.onlyPinned`,
   `list_timeline_clips.onlySourceBound`,
   `list_tracks.onlyNonEmpty`, `list_assets.onlySourceBound`). The GC
   question deserves the same single-field filter.
2. No `limit` cap. A pathological timeline with many transitions could
   blow past a reasonable orientation budget. Every neighbouring list
   tool already carries a silent-clamp `limit`. `list_transitions` was
   the outlier.

Mirrors the single-field filter pattern of
`list_lockfile_entries.onlyPinned` (2026-04-21) and
`list_tracks.onlyNonEmpty` (2026-04-21) plus the
default+`coerceIn` clamp pattern used across the list-tool family.

**Decision.** Two additive input fields on
`ListTransitionsTool.Input`:

- `onlyOrphaned: Boolean? = null` — when `true`, restrict the result
  to rows with `orphaned == true`. `null` or `false` preserves today's
  behaviour (returns every row). Composes with `limit` — the filter is
  applied before the cap so "give me up to 20 orphaned transitions"
  works.
- `limit: Int? = null` — default `50`, silently
  `coerceIn(1, 500)`. No exception on overflow so the LLM can pass any
  integer and get a sensible result. Applied after the filter and
  after the existing chronological `sortedBy { it.startSeconds }` sort,
  so the cap takes the earliest transitions first — the natural
  behaviour for "show me the first N".

`totalTransitionCount` and `orphanedCount` keep meaning **pre-filter
totals** — same as `list_lockfile_entries.totalEntries` — so the agent
can compare returned rows against the true project size and notice
when the filter or cap hid rows. The `outputForLlm` summary now reads
"Project X: 3 returned of 8 total (2 orphaned) (onlyOrphaned)." which
keeps both numbers visible on the same line for LLM grounding.

**Alternatives considered.**

1. *Invert the flag to `onlyHealthy: Boolean` — only return
   non-orphaned rows.* Rejected — the GC question ("what can I
   remove?") is the interesting subset for this tool. A positive flag
   for that subset matches the
   `onlyPinned` / `onlySourceBound` / `onlyNonEmpty` house style
   (positive boolean = "only the interesting sub-slice"). Inverting
   would force the agent to ask for the uninteresting side by default.
   When a non-orphaned question emerges (unlikely — the default already
   returns everything, so the agent can filter client-side) we can add
   it without breaking the positive-flag convention.
2. *Make `totalTransitionCount` reflect the post-filter size.*
   Rejected — loses the "of 8 total" grounding that lets the LLM
   reason about scope ("I asked for orphaned, got 3, but the project
   has 8 transitions total — so 5 are healthy"). Matches
   `list_lockfile_entries.totalEntries` which is also pre-filter, and
   the `returnedEntries` / `totalEntries` split. We chose to keep the
   existing `transitions.size` (= returned count) visible via the
   array length and the summary string, rather than add a third field,
   to keep the schema additive only.
3. *Separate `list_orphaned_transitions` tool.* Rejected — tool-set
   fanout is the wrong knob. A boolean on the base tool composes
   cleanly with future discriminators (project scope, time-window
   filters, track-id scope) without duplicating the `projectId` +
   schema + ordering logic. Same argument that landed
   `list_lockfile_entries.onlyPinned` rather than
   `list_pinned_lockfile_entries`.
4. *Throw on out-of-range `limit` instead of silent clamp.* Rejected —
   inconsistent with the established `coerceIn` house pattern
   (`list_lockfile_entries`, `list_tracks`, `list_timeline_clips`).
   The schema description advertises the `[1, 500]` bound so a
   well-formed caller hits it immediately; loud exceptions here would
   surface as noisy agent retries without adding safety.
5. *Tri-state enum (`orphanState: "any" | "orphaned" | "connected"`).*
   Rejected for YAGNI — no current flow asks for "only connected
   transitions". If it appears we can widen `Boolean?` to a
   string-discriminated enum later; existing `null` / `false` callers
   collapse cleanly to the `"any"` branch.

**Coverage.** `ListTransitionsToolTest` grows seven new cases and
preserves every existing test. New tests build orphaned transitions
directly (synthetic `Clip.Video` on an Effect track with
`transition:` prefix, far from any Video clip boundary) to seed
precise orphan/connected mixes without chaining `removeClip` through
partial-pair edge cases:

- `onlyOrphanedTrueReturnsOnlyOrphanedRows` — 2 orphaned + 2 connected
  transitions; `onlyOrphaned=true` returns the 2 orphaned;
  `totalTransitionCount=4, orphanedCount=2` (both pre-filter).
- `onlyOrphanedFalseIsSameAsDefault` — explicit `false` matches the
  omitted-field default (all 4 rows returned).
- `onlyOrphanedNullIsSameAsDefault` — explicit `null` matches default.
- `limitCapsResponse` — 5 orphaned; `limit=2` returns the earliest 2
  in chronological order, `totalTransitionCount=5` pre-filter.
- `limitClampedToMax` — `limit=999_999` clamps silently to 500, still
  returns all 3 seeded (no exception).
- `limitAtZeroClampsToOne` — `limit=0` clamps silently to 1, exercises
  the lower `coerceIn` bound.
- `onlyOrphanedComposesWithLimit` — 3 orphaned + 1 connected;
  `onlyOrphaned=true, limit=2` returns exactly 2 orphaned rows with
  counts unchanged (pre-filter).

**Registration.** No-op — `list_transitions` is already wired in every
`AppContainer` (`CliContainer`, `apps/desktop/AppContainer`,
`apps/server/ServerContainer`, `apps/android/AndroidAppContainer`,
`apps/ios/Talevia/Platform/AppContainer.swift`). This is a pure
additive input surface extension.
