## 2026-04-23 — Split apps/desktop/TimelinePanel.kt (584 → 372 lines) — extract TimelineClipRow (VISION §5.6)

**Context.** `apps/desktop/src/main/kotlin/io/talevia/desktop/TimelinePanel.kt`
was 584 lines (R.5 #4 long-file — 500–800 → default P1). The file had
fused the main `@Composable fun TimelinePanel(…)` surface with per-clip
inspector `ClipRow`, its `InlineClipActions` + `FilterPresetButton`
children, a `Chip` sub-Composable, the per-clip body-inspector
`PrettyJson` instance, and four helper functions
(`clipHeadline`, `clipChips`, `isNonDefaultTransform`, `formatSeconds`).

Rubric delta §5.6: long-file 584 → 372 for `TimelinePanel.kt`; the
extracted sibling is 255 lines (below the 500 watch threshold).

**Decision.** Extract `internal fun ClipRow` + its supporting
Composables + inspector helpers to
`apps/desktop/src/main/kotlin/io/talevia/desktop/TimelineClipRow.kt`.
Moved:

- `@Composable internal fun ClipRow(…)` (was `private`) — per-clip
  inspector row with expand-to-inspector behaviour.
- `@Composable private fun Chip(…)` — the small colored badge; stays
  `private` to the new file (only `ClipRow` uses it).
- `@Composable private fun InlineClipActions(…)` — filter/volume/LUT/
  caption action row inside the expanded clip panel.
- `@Composable private fun FilterPresetButton(…)` — minimal text
  button wrapper; stays `private` to the new file.
- `internal fun clipHeadline(clip)` + `internal fun clipChips(clip)`
  (was `private`) — used exclusively by `ClipRow`; kept on the new
  file, flipped to `internal` so ClipRow can call them.
- `private fun isNonDefaultTransform(t)` — used only by `clipChips`;
  stays `private` to the new file.
- `internal val TimelinePrettyJson: Json` (was `private val PrettyJson`)
  — renamed to avoid the homonym collision with `LockfilePanel.kt`'s
  `private val PrettyJson` (and the new `SourcePrettyJson` from the
  previous cycle). All three panels still have their own instance;
  this is still `debt-centralise-pretty-json-desktop` territory
  (P2 follow-up already on the backlog from cycle 8).

`TimelinePanel.kt` keeps:
- `@Composable fun TimelinePanel(…)` — layout + state hoist + dispatch
  closure + per-clip callback wiring.
- `@Composable private fun TrackHeader(track)` — per-track label, used
  only from the `TimelinePanel` LazyColumn. Kept local because
  moving it out would leave `TimelinePanel.kt` with a lone
  `Track` import that only exists to feed the forward callback.
- `internal fun formatSeconds(s)` — called from both `TimelinePanel`
  (header bar) and `ClipRow.clipHeadline`; lives with the main
  composable since `TimelinePanel`'s header uses it directly.

**Axis.** "Per-clip inspector grows a new control" (new filter preset,
new per-clip action, more body-inspector fields). Those pressures stay
in `TimelineClipRow.kt`; TimelinePanel's header + layout stay stable.
The other speculative axis — per-track headers growing into
`TrackRow` + drag/playhead Composables from the bullet's
`TrackRow` / `ClipDrag` / `PlayheadScrubber` wish list — hasn't
materialised yet. Extracting a `TrackRow` now would be speculative;
when a second per-track control lands (e.g. mute/solo toggle), that
cycle spawns `TimelineTrackHeader.kt`.

**Alternatives considered.**

- **Extract only `InlineClipActions` + `FilterPresetButton`.** Would
  drop ~80 lines from TimelinePanel but leave `ClipRow` (the biggest
  single non-main Composable) + all clip-inspector helpers still
  entangled with the main surface. Rejected: `ClipRow` is where new
  clip-inspector behaviour lands (per-clip filters, per-clip
  transforms, per-clip volume), so it's the natural thing to
  isolate.

- **Merge TimelinePanel-local `TrackHeader` into TimelineClipRow.kt
  too.** Would centralise per-item rendering but `TrackHeader` is
  called from `TimelinePanel`'s LazyColumn directly, and it doesn't
  share any helpers with `ClipRow`. Keeping them apart follows the
  "one file per significant composable" shape the
  `SourcePanel` split established last cycle.

- **Centralise the three `*PrettyJson` instances (LockfilePanel /
  TimelinePanel / Source's `SourcePrettyJson`) into one
  `DesktopPrettyJson.kt`.** Already tracked as P2 bullet
  `debt-centralise-pretty-json-desktop` (added in cycle 8). Rejected
  for this cycle — the previous cycle's decision noted the configs
  differ on `JsonConfig.default` base vs. plain `Json`, so unifying
  needs its own cycle. This cycle's `TimelinePrettyJson` rename keeps
  the cross-file collision resolved while the P2 debt waits.

**Coverage.** `:apps:desktop:test` (pre-existing
`MacOsInfoPlistExtraXmlTest`) + `:apps:desktop:assemble` +
`:apps:desktop:compileKotlin` + `ktlintCheck` all green. No new UI
tests — Desktop has no automated Compose UI test harness wired.
`:core:jvmTest` / `:apps:server:test` / `:apps:cli:test` unaffected.

**Registration.** No tool / AppContainer change. Package unchanged;
the caller site in `AppRoot.kt` (`TimelinePanel(container = …,
projectId = …, …)`) still sees the same public `fun TimelinePanel`
signature. Build globs pick up the new sibling automatically.
