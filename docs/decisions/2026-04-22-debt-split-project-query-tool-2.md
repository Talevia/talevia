## 2026-04-22 — ProjectQueryTool.kt 638 → 540 lines: helpText + filter guard moved to siblings (VISION §5.2 file hygiene)

Commit: `6e7bd8f`

**Context.** `ProjectQueryTool.kt` was flagged by R.5.3 at 638 lines,
default-P1 debt (500–800 tier). The earlier
`debt-split-project-query-tool` cycle (commit `96bca96`,
`2026-04-22-debt-split-project-query-tool.md`) moved the
~170-line JSON schema to a sibling `ProjectQueryToolSchema.kt`.
Separately, every `SELECT_*` handler (`runTracksQuery`,
`runAssetsQuery`, etc.) had already been extracted to
`project/query/<select>.kt` siblings following the SessionQueryTool
pattern. Backlog bullet `debt-split-project-query-tool` (this cycle)
was written assuming the handlers were still inline — that premise
was stale. This cycle tackles the *remaining* bulk.

After those earlier extractions, the 638-line residual broke down:
- Input data class (~85 lines)
- Output + 17 nested `@Serializable data class` row types (~290 lines)
- `helpText` (~40 lines)
- `execute` dispatch (~30 lines)
- `rejectIncompatibleFilters` (~60 lines)
- Companion object constants (~25 lines)

SessionQueryTool — the sibling precedent — sits at 534 lines with a
similar shape (17 nested row classes, dispatch + helpText + filter
guard). The ~100-line gap between ProjectQueryTool's 638 and
SessionQueryTool's 534 was concentrated in two places: a longer
helpText and a much bigger `rejectIncompatibleFilters` body (13
branch groups vs ~8). Those are exactly the pieces that
factor cleanly out to siblings without disturbing the public
`ProjectQueryTool.XRow` nested-type reference path (77+ call sites
across tests + `query/` handlers + the desktop `SnapshotPanel`).

**Decision.** Two pieces extracted, zero public-API disturbance:

1. **`helpText` body → `PROJECT_QUERY_HELP_TEXT` const** in the
   existing `ProjectQueryToolSchema.kt` sibling. Same package, so
   `ProjectQueryTool.helpText = PROJECT_QUERY_HELP_TEXT` is a
   single-line reference. Byte-identical content — every bullet
   point and description sentence preserved verbatim, preserving the
   LLM's memorised phrasing. ~40 lines out.

2. **`rejectIncompatibleFilters` body → `rejectIncompatibleProjectQueryFilters`**
   in a new `core/src/commonMain/kotlin/io/talevia/core/tool/builtin/project/query/ProjectQueryFilterGuard.kt`
   sibling. The private `rejectIncompatibleFilters` method becomes a
   one-line delegation so the call site in `execute` is unchanged.
   Handler siblings in `project/query/` can see the guard if they
   ever need per-select validation; currently only `execute` calls
   it. ~60 lines out (net after the 3-line delegating wrapper).

Result: **638 → 540 lines.** Matches SessionQueryTool's 534-line
shape. Both remain slightly over the 500 soft threshold because of
the 17 nested row classes — which are the public decoding API the
LLM / UI / tests decode into (`ProjectQueryTool.TrackRow.serializer()`
is the ubiquitous pattern, same as
`SessionQueryTool.SessionRow.serializer()`). Moving rows out breaks
the nested-access path across 77+ sites; not worth the churn for a
dozen lines past the soft threshold when the sibling precedent also
sits there.

**Alternatives considered.**

1. **Extract the 17 Row data classes to a top-level
   `ProjectQueryRows.kt`.** Rejected. Would drop line count to ~250
   (well under 500) but break every `ProjectQueryTool.TrackRow`
   reference across 77+ call sites (tests, `query/` handlers, the
   desktop `SnapshotPanel.kt`). Nested data classes can't be
   preserved at the same qualified name via typealias — Kotlin
   typealiases are top-level only, not class-member. The mechanical
   find-replace would be huge churn for a cosmetic gain, and
   SessionQueryTool has the same nested-row layout at the same
   line tier, so the debt threshold is satisfied on the same terms.

2. **Leave the file at 638 lines and bump priority.** Rejected —
   R.5.3 flagged the size; kicking the can isn't a decision.

3. **Factor `rejectIncompatibleFilters` into a table-driven
   validator (map of filter field → allowed selects) to cut its
   body further.** Considered. The current if-chain has 13
   branches, some compound (`onlyPinned` valid for two selects,
   `sourceNodeId` for three). A table-driven form would be
   marginally shorter but less readable when the combinations are
   non-uniform. Extracting the function wholesale to a sibling is
   the simpler and more localised refactor; a table-driven form
   can come later if the guard grows further.

4. **Extract the companion constants (`SELECT_*`) too.** Considered.
   Another ~25 lines and would put the file at ~513. Rejected for
   now — the constants are the dispatcher's vocabulary, they should
   live with the dispatcher and be visible at a glance when reading
   `execute`. The sibling handlers already reference them via
   `ProjectQueryTool.SELECT_X`; moving them out would add imports
   to every handler without proportional clarity gain.

**Coverage.** Pure internal reorganization — all existing tests
validate the move by passing unchanged. Specifically:

- `ProjectQueryToolTest` — exercises all 13 select variants + filter
  rejection paths. Every `rejectIncompatibleFilters` branch is
  covered here (wrong-field-for-select tests).
- `ProjectQuerySpendTest`, `ProjectQueryLockfileFiltersTest`,
  `ProjectSnapshotToolsTest` — each decodes via
  `ProjectQueryTool.XRow.serializer()` round-trip to catch nested-
  type regressions.
- `:core:jvmTest` + `:platform-impls:video-ffmpeg-jvm:test` +
  `:apps:server:test` + `:apps:desktop:assemble` +
  `:apps:android:assembleDebug` +
  `:core:compileKotlinIosSimulatorArm64` + `ktlintCheck` — all
  green.

**Registration.** No AppContainer change — pure refactor. helpText
content unchanged (byte-identical), schema unchanged, filter-guard
error message strings unchanged, row types unchanged.

Filename note: this decision is `-2.md` because the previous
schema-extraction cycle used `debt-split-project-query-tool.md`.
Same slug, same debt line, different cut of the same file — `-2`
suffix is the skill's canonical collision resolution.

---
